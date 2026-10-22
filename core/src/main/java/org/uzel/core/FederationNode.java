package org.uzel.core;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Contact gossip, retained local publications, non-forwarding durable replicas, and bounded lookup. */
public final class FederationNode implements AutoCloseable {
 private static final int STATE_MAGIC=0x504e5333,MAX_STATE=32*1024*1024,MAX_STORED=640;
 private static final FederationResolver DNS=new FederationResolver();
 private final TlsIdentity identity;
 private final Path state;
 private final InetAddress bind;
 private final int requestedPort;
 private final String advertisedHost;
 private final boolean allowPrivate;
 private final DiscoveryStore publishedDiscovery=new DiscoveryStore();
 private final PeerDirectory publishedRoutes=new PeerDirectory();
 private final LinkedHashMap<String,FederationContact> contacts=new LinkedHashMap<>();
 private final Map<String,FederationRecord> published=new LinkedHashMap<>(),replicas=new LinkedHashMap<>(),queryCache=new LinkedHashMap<>();
 private final Map<String,String> publicationSources=new HashMap<>(),replicaSources=new HashMap<>();
 private final Set<String> durableReplicas=new HashSet<>();
 private final Map<String,Map<String,FederationAck>> acknowledgements=new HashMap<>();
 private final Map<String,Map<String,Long>> replicaRejectedUntil=new HashMap<>();
 private final Map<String,Long> cachedAt=new HashMap<>(),successes=new HashMap<>(),retryAfter=new HashMap<>();
 private final Map<String,Integer> failures=new HashMap<>();
 private final LinkedHashMap<String,Long> incoming=new LinkedHashMap<>(),queries=new LinkedHashMap<>();
 private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
 private final ThreadPoolExecutor workers;
 private final ScheduledExecutorService gossip;
 private SSLServerSocket server;
 private Thread acceptThread;
 private volatile boolean running,closed;
 private volatile String lastError="";
 private volatile boolean capacityLimited;
 private boolean dirty;
 private long lastPersist;
 private String lastWriteDurability=FederationAck.PROCESS_CRASH;
 private FederationContact own;
 private int cursor;
 public FederationNode(TlsIdentity identity,Path stateDirectory,InetAddress bind,int port,String advertisedHost,boolean allowPrivate)throws IOException {
  if(identity==null || stateDirectory==null || bind==null || port<0 || port>65535)throw new IllegalArgumentException("Federation configuration");
  this.identity=identity;state=stateDirectory.resolve("federation-state.bin");this.bind=bind;requestedPort=port;this.advertisedHost=advertisedHost;this.allowPrivate=allowPrivate;
  FederationContact.create(identity,advertisedHost,Math.max(1,port),System.currentTimeMillis());
  AtomicInteger number=new AtomicInteger();ThreadFactory threads=r->{Thread t=new Thread(r,"peernet-directory-"+number.incrementAndGet());t.setDaemon(true);return t;};
  workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),threads,new ThreadPoolExecutor.AbortPolicy());gossip=Executors.newSingleThreadScheduledExecutor(threads);load();
 }
 public synchronized void start()throws IOException {
  if(closed)throw new IOException("Directory is closed");if(running)return;
  try {
   server=(SSLServerSocket)identity.context(null).getServerSocketFactory().createServerSocket(requestedPort,16,bind);server.setNeedClientAuth(true);
   own=FederationContact.create(identity,advertisedHost,server.getLocalPort(),System.currentTimeMillis());running=true;
   acceptThread=new Thread(this::acceptLoop,"peernet-directory-accept");acceptThread.setDaemon(true);acceptThread.start();gossip.scheduleWithFixedDelay(this::tick,0,250,TimeUnit.MILLISECONDS);
  }catch(java.security.GeneralSecurityException e){throw new IOException("Directory TLS",e);}
 }
 /** Trusted local publisher input. Query results and incoming replicas never enter this store. */
 public DiscoveryStore discoveryStore(){return publishedDiscovery;}
 public synchronized boolean acceptRoute(PeerDirectory.Record record)throws IOException {boolean accepted=publishedRoutes.accept(PeerDirectory.Record.parse(record.encode()),System.currentTimeMillis());if(accepted){collectPublished();dirty=true;}return accepted;}
 public synchronized void addContact(String code)throws IOException {FederationContact contact=FederationContact.parse(code);DNS.resolve(contact,allowPrivate,System.currentTimeMillis()+2000);remember(contact);for(FederationContact backup:contact.backups)remember(backup);persistNow();}
 private synchronized void remember(FederationContact contact) {
  long now=System.currentTimeMillis();contacts.values().removeIf(c->c.expiresAt<=now);if(identity.fingerprint.equals(contact.fingerprint) || contact.expiresAt<=now)return;
  FederationContact old=contacts.get(contact.fingerprint);if(old!=null && contact.issuedAt<=old.issuedAt)return;if(old==null && contacts.size()>=8){capacityLimited=true;return;}contacts.put(contact.fingerprint,contact);dirty=true;
 }
 private synchronized void learn(List<FederationContact> learned){for(FederationContact c:learned){remember(c);for(FederationContact backup:c.backups)remember(backup);}}
 public synchronized FederationContact ownContact()throws IOException {
  if(!running || own==null)throw new IOException("Directory is stopped");long now=System.currentTimeMillis();if(now-own.issuedAt>24L*60*60*1000)own=FederationContact.create(identity,advertisedHost,server.getLocalPort(),now);
  List<FederationContact> backups=new ArrayList<>();for(FederationContact c:contacts.values())if(c.expiresAt>now && c.backups.isEmpty() && backups.size()<7)backups.add(c);
  return backups.isEmpty()?own:FederationContact.create(identity,advertisedHost,server.getLocalPort(),now,backups);
 }
 private synchronized List<FederationContact> contactFrame()throws IOException {
  ownContact();List<FederationContact> frame=new ArrayList<>();frame.add(own);for(FederationContact c:contacts.values())if(c.expiresAt>System.currentTimeMillis() && frame.size()<8)frame.add(c);return frame;
 }
 public static final class Snapshot {
  public final List<FederationContact> contacts;
  public final List<DiscoveryPresence> presences;
  public final List<PeerDirectory.Record> routes;
  public final int connectedNeighbors,publishedRecords,replicaRecords,cachedQueryRecords,underReplicatedRecords;
  /** Two authenticated retention promises, at least PROCESS_CRASH; this does not assert power-loss safety. */
  public final int fullyReplicatedRecords;
  public final String lastError;
  public final boolean capacityLimited,running,partial;
  Snapshot(List<FederationContact> contacts,List<FederationRecord> records,int connected,String error,boolean limited,boolean running,int local,int replicas,int cache,int full) {
   this.contacts=Collections.unmodifiableList(contacts);List<DiscoveryPresence> ps=new ArrayList<>();List<PeerDirectory.Record> rs=new ArrayList<>();for(FederationRecord r:records){if(r.kind==1)ps.add((DiscoveryPresence)r.value);if(r.kind==3)rs.add((PeerDirectory.Record)r.value);}presences=Collections.unmodifiableList(ps);routes=Collections.unmodifiableList(rs);connectedNeighbors=connected;lastError=error;capacityLimited=limited;this.running=running;publishedRecords=local;replicaRecords=replicas;cachedQueryRecords=cache;fullyReplicatedRecords=full;underReplicatedRecords=local-full;partial=true;
  }
 }
 public synchronized Snapshot snapshot() {
  try{collectPublished();}catch(IOException e){lastError="Не удалось прочитать локальные объявления";}expire();int full=0;for(FederationRecord record:published.values())if(healthyAcks(record)>=2)full++;
  return new Snapshot(new ArrayList<>(contacts.values()),new ArrayList<>(localRecords().values()),(int)successes.keySet().stream().filter(contacts::containsKey).count(),lastError,capacityLimited,running,published.size(),replicas.size(),queryCache.size(),full);
 }
 public static final class LookupResult {
  public final List<DiscoveryPresence> presences;
  public final List<DiscoverySignal> signals;
  public final List<PeerDirectory.Record> routes;
  public final boolean partial=true;
  public final int visitedServers;
  LookupResult(QueryAnswer answer) {
   List<DiscoveryPresence> ps=new ArrayList<>();List<DiscoverySignal> ss=new ArrayList<>();List<PeerDirectory.Record> rs=new ArrayList<>();for(FederationRecord r:answer.records){if(r.kind==1)ps.add((DiscoveryPresence)r.value);else if(r.kind==2)ss.add((DiscoverySignal)r.value);else rs.add((PeerDirectory.Record)r.value);}presences=Collections.unmodifiableList(ps);signals=Collections.unmodifiableList(ss);routes=Collections.unmodifiableList(rs);visitedServers=answer.visited.size();
  }
 }
 public LookupResult lookup()throws IOException {
  if(!running)throw new IOException("Directory is stopped");QueryAnswer answer=runQuery(TlsIdentity.randomSecret(),2,System.currentTimeMillis()+5000,Collections.emptyList(),"");cache(answer.records);return new LookupResult(answer);
 }
 private void acceptLoop() {
  while(running)try{SSLSocket socket=(SSLSocket)server.accept();socket.setSoTimeout(FederationExchange.TIMEOUT);sockets.add(socket);ScheduledFuture<?> timeout=FederationExchange.deadline(socket);try{workers.execute(()->serve(socket,timeout));}catch(RejectedExecutionException e){timeout.cancel(false);sockets.remove(socket);socket.close();}}catch(IOException e){if(running)lastError="Не удалось принять соединение";}
 }
 private void serve(SSLSocket socket,ScheduledFuture<?> timeout) {
  try(SSLSocket tls=socket) {
   tls.startHandshake();String source=TlsIdentity.fingerprint(tls.getSession().getPeerCertificates()[0]);FederationExchange.Message request=FederationExchange.receive(tls);rate(source,request.operation);learn(request.contacts);
   List<FederationRecord> records=Collections.emptyList();List<FederationAck> acks=Collections.emptyList();List<String> visited=Collections.emptyList();
   if(request.operation==FederationExchange.PUBLISH){acceptPublications(request.records,source);QueryAnswer answer=runQuery(TlsIdentity.randomSecret(),2,System.currentTimeMillis()+5000,Collections.emptyList(),source);records=forRecipient(answer.records,source);visited=answer.visited;}
   else if(request.operation==FederationExchange.REPLICA)acks=acceptReplicas(request.records,source);
   else if(request.operation==FederationExchange.QUERY){QueryAnswer answer=runQuery(request.queryId,request.hops,request.queryDeadline,request.visited,request.recipient);records=answer.records;visited=answer.visited;}
   else if(request.operation!=FederationExchange.GOSSIP)throw new IOException("Federation request operation");
   FederationExchange.Message response=new FederationExchange.Message(FederationExchange.RESPONSE,records,contactFrame(),acks,request.queryId,0,0,visited,true,request.recipient);FederationExchange.send(tls,FederationExchange.encode(response));markSuccess(source);
  }catch(IOException|java.security.GeneralSecurityException ignored){}finally{timeout.cancel(false);sockets.remove(socket);}
 }
 private synchronized void rate(String source,int operation)throws IOException {
  long now=System.currentTimeMillis();String key=source+":"+operation;Long previous=incoming.get(key);if(previous!=null && now-previous<(operation==FederationExchange.QUERY?100:500))throw new IOException("Federation rate limit");if(incoming.size()>=128)incoming.remove(incoming.keySet().iterator().next());incoming.put(key,now);
 }
 private synchronized void acceptPublications(List<FederationRecord> records,String source)throws IOException {
  long now=System.currentTimeMillis();for(FederationRecord r:records) {
   if(!r.owner.equals(source))continue;boolean accepted=false;if(r.kind==1)accepted=publishedDiscovery.accept((DiscoveryPresence)r.value,now);else if(r.kind==2)accepted=publishedDiscovery.accept((DiscoverySignal)r.value,now);else accepted=publishedRoutes.accept((PeerDirectory.Record)r.value,now);
   if(accepted){publicationSources.put(r.id,source);dirty=true;}
  }
  collectPublished();if(dirty && now-lastPersist>=1000)persistNow();
 }
 private synchronized List<FederationAck> acceptReplicas(List<FederationRecord> records,String source)throws IOException {
  expire();List<FederationRecord> accepted=new ArrayList<>();boolean needsDurable=false;
  for(FederationRecord r:records) {
   if(!replicas.containsKey(r.digest)) {
    int sourceVersions=0,sourceBytes=0,typeCount=0;
    for(Map.Entry<String,FederationRecord> entry:replicas.entrySet()) {
     FederationRecord old=entry.getValue();if(old.kind==r.kind)typeCount++;
     if(source.equals(replicaSources.get(entry.getKey()))){sourceBytes+=old.size();if(old.kind==r.kind)sourceVersions++;}
    }
    int quota=r.kind==2?16:32,global=r.kind==2?128:256;
    if(replicas.size()>=MAX_STORED || typeCount>=global || sourceVersions>=quota || sourceBytes+r.size()>512*1024){capacityLimited=true;continue;}
    replicas.put(r.digest,r);replicaSources.put(r.digest,source);dirty=true;
   }
   accepted.add(r);if(!durableReplicas.contains(r.digest))needsDurable=true;
  }
  // Never send a promise for memory-only data. Atomic rename must finish first.
  if(needsDurable)persistNow();List<FederationAck> acks=new ArrayList<>();for(FederationRecord r:accepted)if(durableReplicas.contains(r.digest))acks.add(FederationAck.create(identity,r,lastWriteDurability));return acks;
 }
 private synchronized void collectPublished()throws IOException {
  long now=System.currentTimeMillis();Map<String,FederationRecord> latest=new LinkedHashMap<>();for(DiscoveryPresence p:publishedDiscovery.presences(now)){FederationRecord r=FederationRecord.of(p);latest.put(r.id,r);}for(DiscoverySignal s:publishedDiscovery.signals(now)){FederationRecord r=FederationRecord.of(s);latest.put(r.id,r);}for(PeerDirectory.Record r:publishedRoutes.records()){FederationRecord record=FederationRecord.of(r);latest.put(record.id,record);}
  if(published.size()!=latest.size())dirty=true;for(FederationRecord r:latest.values()){FederationRecord old=published.get(r.id);if(old==null || !old.digest.equals(r.digest))dirty=true;publicationSources.putIfAbsent(r.id,identity.fingerprint);}published.clear();published.putAll(latest);publicationSources.keySet().retainAll(latest.keySet());Set<String> digests=new HashSet<>();for(FederationRecord r:published.values())digests.add(r.digest);acknowledgements.keySet().retainAll(digests);replicaRejectedUntil.keySet().retainAll(digests);
 }
 private synchronized void expire() {
  long now=System.currentTimeMillis();contacts.values().removeIf(c->c.expiresAt<=now);replicas.values().removeIf(r->r.expiresAt<=now);replicaSources.keySet().retainAll(replicas.keySet());durableReplicas.retainAll(replicas.keySet());
  queryCache.entrySet().removeIf(e->e.getValue().expiresAt<=now || cachedAt.getOrDefault(e.getKey(),0L)<now-15000);cachedAt.keySet().retainAll(queryCache.keySet());successes.values().removeIf(t->t<now-30000);retryAfter.keySet().retainAll(contacts.keySet());failures.keySet().retainAll(contacts.keySet());queries.values().removeIf(t->t<=now);
 }
 private synchronized Map<String,FederationRecord> localRecords() {
  Map<String,FederationRecord> records=new LinkedHashMap<>();for(FederationRecord r:published.values())FederationRecord.latest(records,r);for(FederationRecord r:replicas.values())FederationRecord.latest(records,r);for(FederationRecord r:queryCache.values())FederationRecord.latest(records,r);return records;
 }
 private synchronized void cache(List<FederationRecord> records) {
  expire();long now=System.currentTimeMillis();for(FederationRecord r:records){if(!queryCache.containsKey(r.id) && queryCache.size()>=64){String oldest=queryCache.keySet().iterator().next();queryCache.remove(oldest);cachedAt.remove(oldest);}FederationRecord.latest(queryCache,r);cachedAt.put(r.id,now);}
 }
 private static List<FederationRecord> forRecipient(List<FederationRecord> records,String recipient) {
  List<FederationRecord> result=new ArrayList<>();for(FederationRecord r:records)if(recipient.isEmpty() || r.kind!=2 || ((DiscoverySignal)r.value).recipientFingerprint.equals(recipient))result.add(r);return result;
 }
 private static final class QueryAnswer {
  final List<FederationRecord> records;final List<String> visited;
  QueryAnswer(List<FederationRecord> records,Collection<String> visited){this.records=records;this.visited=new ArrayList<>(visited);}
 }
 private QueryAnswer runQuery(String id,int hops,long deadline,List<String> path,String recipient)throws IOException {
  Map<String,FederationRecord> results;List<FederationContact> candidates;boolean forward;
  synchronized(this) {
   collectPublished();expire();results=new LinkedHashMap<>();for(FederationRecord r:forRecipient(new ArrayList<>(localRecords().values()),recipient))FederationRecord.latest(results,r);forward=!path.contains(identity.fingerprint) && !queries.containsKey(id) && queries.size()<256 && deadline>System.currentTimeMillis();
   if(forward)queries.put(id,deadline);candidates=new ArrayList<>(contacts.values());candidates.removeIf(c->path.contains(c.fingerprint));
   candidates.sort(Comparator.comparingInt(c->failures.getOrDefault(c.fingerprint,0)));
  }
  LinkedHashSet<String> visited=new LinkedHashSet<>(path);visited.add(identity.fingerprint);
  if(forward && hops>0 && visited.size()<7) {
   int asked=0;for(FederationContact contact:candidates) {
    if(asked>=2 || deadline-System.currentTimeMillis()<100)break;
    if(visited.contains(contact.fingerprint))continue;
    asked++;FederationExchange.Message request=new FederationExchange.Message(FederationExchange.QUERY,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),id,hops-1,deadline,new ArrayList<>(visited),true,recipient);
    try{FederationExchange.Message answer=call(contact,request,Math.min(1500,deadline-System.currentTimeMillis()));learn(answer.contacts);for(FederationRecord r:forRecipient(answer.records,recipient))FederationRecord.latest(results,r);for(String pin:answer.visited)if(visited.size()<8)visited.add(pin);}catch(IOException ignored){}
   }
  }
  return new QueryAnswer(FederationRecord.bounded(results.values()),visited);
 }
 private FederationExchange.Message call(FederationContact contact,FederationExchange.Message request,long timeout)throws IOException {
  if(!running || timeout<=0)throw new IOException("Directory stopped/deadline");long deadline=System.currentTimeMillis()+timeout;
  try(Socket socket=new Socket()) {
   sockets.add(socket);ScheduledFuture<?> total=FederationExchange.deadline(socket,timeout);
   try {
    InetSocketAddress address=DNS.resolve(contact,allowPrivate,deadline);long remaining=deadline-System.currentTimeMillis();if(remaining<=0)throw new SocketTimeoutException("Federation connection deadline");
    socket.connect(address,(int)Math.max(1,Math.min(1000,remaining)));remaining=deadline-System.currentTimeMillis();if(remaining<=0)throw new SocketTimeoutException("Federation exchange deadline");
    FederationExchange.Message response=FederationExchange.transact(identity,socket,contact,request,remaining);markSuccess(contact.fingerprint);return response;
   }finally{total.cancel(false);sockets.remove(socket);}
  }
 }
 private synchronized void markSuccess(String pin){if(contacts.containsKey(pin)){successes.put(pin,System.currentTimeMillis());failures.remove(pin);}}
 private synchronized int healthyAcks(FederationRecord record) {
  int count=0;for(FederationAck ack:acknowledgements.getOrDefault(record.digest,Collections.emptyMap()).values())if(ack.matches(record) && ack.retainedUntil>System.currentTimeMillis() && contacts.containsKey(ack.holder) && !failures.containsKey(ack.holder))count++;return count;
 }
 private synchronized boolean wantsReplica(FederationRecord record,FederationContact target)throws IOException {
  Map<String,FederationAck> acks=acknowledgements.getOrDefault(record.digest,Collections.emptyMap());int needed=2-healthyAcks(record);if(needed<=0 || acks.containsKey(target.fingerprint))return false;
  Map<String,Long> rejected=replicaRejectedUntil.getOrDefault(record.digest,Collections.emptyMap());
  List<FederationContact> candidates=new ArrayList<>();for(FederationContact c:contacts.values())if(!acks.containsKey(c.fingerprint) && !failures.containsKey(c.fingerprint) && rejected.getOrDefault(c.fingerprint,0L)<=System.currentTimeMillis())candidates.add(c);
  Map<String,String> score=new HashMap<>();for(FederationContact c:candidates)score.put(c.fingerprint,FederationRecord.hex(DiscoveryWire.hash((record.id+":"+c.fingerprint).getBytes(StandardCharsets.US_ASCII))));
  candidates.sort((a,b)->score.get(b.fingerprint).compareTo(score.get(a.fingerprint)));for(int i=0;i<Math.min(needed,candidates.size());i++)if(candidates.get(i).fingerprint.equals(target.fingerprint))return true;return false;
 }
 private void tick() {
  if(!running)return;FederationContact contact=null;
  try {
   synchronized(this){collectPublished();expire();if(dirty && System.currentTimeMillis()-lastPersist>=1000)persistNow();List<FederationContact> values=new ArrayList<>(contacts.values());for(int i=0;i<values.size();i++){FederationContact candidate=values.get(Math.floorMod(cursor++,values.size()));if(retryAfter.getOrDefault(candidate.fingerprint,0L)<=System.currentTimeMillis()){contact=candidate;break;}}}
   if(contact==null)return;
   learn(call(contact,FederationExchange.Message.simple(FederationExchange.GOSSIP,Collections.emptyList(),contactFrame()),3000).contacts);
   List<FederationRecord> batch=new ArrayList<>();synchronized(this){List<FederationRecord> random=new ArrayList<>(published.values());Collections.shuffle(random);for(FederationRecord record:random)if(wantsReplica(record,contact))batch.add(record);}
   batch=FederationRecord.bounded(batch);if(!batch.isEmpty()) {
    FederationExchange.Message response=call(contact,FederationExchange.Message.simple(FederationExchange.REPLICA,batch,Collections.emptyList()),4000);String expected=contact.fingerprint;
    synchronized(this) {
     Set<String> accepted=new HashSet<>();for(FederationAck ack:response.acks)if(expected.equals(ack.holder))for(FederationRecord record:batch)if(ack.matches(record)){acknowledgements.computeIfAbsent(record.digest,k->new HashMap<>()).put(ack.holder,ack);accepted.add(record.digest);}
     for(FederationRecord record:batch)if(!accepted.contains(record.digest)){replicaRejectedUntil.computeIfAbsent(record.digest,k->new HashMap<>()).put(expected,System.currentTimeMillis()+30000);capacityLimited=true;}
    }
   }
   synchronized(this){retryAfter.put(contact.fingerprint,System.currentTimeMillis()+1000);lastError="";}
  }catch(IOException|RuntimeException e) {
   synchronized(this){if(contact!=null){int attempts=Math.min(6,failures.getOrDefault(contact.fingerprint,0)+1);failures.put(contact.fingerprint,attempts);retryAfter.put(contact.fingerprint,System.currentTimeMillis()+Math.min(60000,1000L<<attempts));successes.remove(contact.fingerprint);}lastError="Не все соседи доступны; используются запасные контакты";}
  }
 }
 private synchronized void persistNow()throws IOException {
  collectPublished();expire();Files.createDirectories(state.getParent());ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(STATE_MAGIC);out.writeInt(contacts.size());for(FederationContact c:contacts.values())DiscoveryWire.field(out,c.bytes());writeRecords(out,published.values(),publicationSources,false);writeRecords(out,replicas.values(),replicaSources,true);if(bytes.size()>MAX_STATE)throw new IOException("Directory state size");
  Path temporary=Files.createTempFile(state.getParent(),"federation-",".tmp");
  try {
   try(FileChannel file=FileChannel.open(temporary,StandardOpenOption.WRITE)){ByteBuffer data=ByteBuffer.wrap(bytes.toByteArray());while(data.hasRemaining())file.write(data);file.force(true);}
   try{Files.move(temporary,state,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){throw new IOException("Atomic directory storage unavailable",e);}
   // Java's Windows provider cannot fsync a directory handle. State the narrower guarantee in the signed ACK.
   if(System.getProperty("os.name","").startsWith("Windows"))lastWriteDurability=FederationAck.PROCESS_CRASH;
   else {try(FileChannel directory=FileChannel.open(state.getParent(),StandardOpenOption.READ)){directory.force(true);}lastWriteDurability=FederationAck.DIRECTORY_SYNCED;}
  }finally{Files.deleteIfExists(temporary);}
  durableReplicas.clear();durableReplicas.addAll(replicas.keySet());dirty=false;lastPersist=System.currentTimeMillis();
 }
 private static void writeRecords(DataOutputStream out,Collection<FederationRecord> records,Map<String,String> sources,boolean digestKey)throws IOException {
  out.writeInt(records.size());for(FederationRecord r:records){out.writeByte(r.kind);DiscoveryWire.field(out,r.bytes());out.writeUTF(sources.getOrDefault(digestKey?r.digest:r.id,r.owner));}
 }
 private void load() {
  if(!Files.exists(state))return;try {
   if(Files.size(state)>MAX_STATE)throw new IOException("Directory state size");byte[] bytes=Files.readAllBytes(state);DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=STATE_MAGIC)throw new IOException("Directory state version");long now=System.currentTimeMillis();int count=in.readInt();if(count<0 || count>8)throw new IOException("Directory state contacts");List<FederationContact> loaded=new ArrayList<>();for(int i=0;i<count;i++){byte[] encoded=DiscoveryWire.field(in,FederationContact.MAX_BYTES);try{loaded.add(FederationContact.parseBytes(encoded,now,true));}catch(IOException e){lastError="Часть сохранённых контактов устарела или повреждена";}}
   Map<String,String> ps=new HashMap<>(),rs=new HashMap<>();List<FederationRecord> pubs=readRecords(in,now,ps,false),reps=readRecords(in,now,rs,true);DiscoveryWire.end(in);
   for(FederationContact c:loaded)remember(c);for(FederationRecord r:pubs){if(r.kind==1)publishedDiscovery.accept((DiscoveryPresence)r.value,now);else if(r.kind==2)publishedDiscovery.accept((DiscoverySignal)r.value,now);else publishedRoutes.accept((PeerDirectory.Record)r.value,now);}publicationSources.putAll(ps);for(FederationRecord r:reps)replicas.put(r.digest,r);replicaSources.putAll(rs);durableReplicas.addAll(replicas.keySet());collectPublished();dirty=false;
  }catch(IOException|RuntimeException e){contacts.clear();publishedDiscovery.clear();publishedRoutes.clear();published.clear();replicas.clear();publicationSources.clear();replicaSources.clear();durableReplicas.clear();lastError="Сохранённый каталог повреждён; добавьте контакт заново";}
 }
 private List<FederationRecord> readRecords(DataInputStream in,long now,Map<String,String> sources,boolean digestKey)throws IOException {
  int count=in.readInt();if(count<0 || count>MAX_STORED)throw new IOException("Directory state count");List<FederationRecord> result=new ArrayList<>();for(int i=0;i<count;i++){int kind=in.readUnsignedByte();byte[] bytes=DiscoveryWire.field(in,kind==1 || kind==3?8192:100000);String source=in.readUTF();if(!source.matches("[0-9a-f]{64}"))throw new IOException("Directory source identity");try{FederationRecord r=FederationRecord.parse(kind,bytes,now);result.add(r);sources.put(digestKey?r.digest:r.id,source);}catch(IOException e){lastError="Часть сохранённых объявлений устарела или повреждена";}}return result;
 }
 @Override public void close()throws IOException {
  synchronized(this){if(closed)return;closed=true;running=false;}gossip.shutdownNow();if(server!=null)server.close();for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}workers.shutdownNow();try{gossip.awaitTermination(4,TimeUnit.SECONDS);workers.awaitTermination(4,TimeUnit.SECONDS);if(acceptThread!=null)acceptThread.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}synchronized(this){successes.clear();persistNow();}
 }
}
