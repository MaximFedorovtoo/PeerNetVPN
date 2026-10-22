package org.uzel.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.uzel.core.*;

/** Ephemeral signed catalog and recipient-encrypted signaling. Never persists SDP or invitations. */
final class AutoDiscoveryManager implements AutoCloseable {
    interface Listener { void catalog(List<DiscoveryPresence> values); void failed(String fingerprint); }
    private final Node node;
    private final PhysicalNetwork physical;
    private final PairingManager pairing;
    private final DiscoveryIdentity identity;
    private final TlsIdentity tlsIdentity;
    private final DirectoryContacts directoryContacts;
    private final DirectoryFailover failover=new DirectoryFailover();
    private volatile Socket activeDirectorySocket;
    private final DiscoveryStore store=new DiscoveryStore();
    private final Supplier<List<Invitation>> contacts;
    private final Listener listener;
    private final InetAddress hostingAddress;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private volatile HttpURLConnection activeRequest;
    private final Map<String,Long> handled=new LinkedHashMap<>();
    private volatile boolean closed,hosting;
    private volatile String url,state="STARTING",pending="";
    private final AtomicLong refreshRequests=new AtomicLong();
    private volatile long completedRefresh;
    long completedRefresh(){return completedRefresh;}
    long requestRefresh(){
        long request=refreshRequests.incrementAndGet();
        execute(()->{cycle();completedRefresh=request;});
        return request;
    }
    private volatile BootstrapHttpServer server;
    private String ownFingerprint;
    private long publishedAt,pendingUntil,lastIncoming;
    private final AtomicLong attempts=new AtomicLong(),cancellations=new AtomicLong();
    private volatile long pendingAttempt;
    private int cursor;

    AutoDiscoveryManager(Context context,Node node,PhysicalNetwork physical,PairingManager pairing,
                         String url,boolean hosting,Supplier<List<Invitation>> contacts,Listener listener)throws Exception {
        this.node=node;this.physical=physical;this.pairing=pairing;this.url=validateUrl(url);
        this.hosting=hosting;this.contacts=contacts;this.listener=listener;
        tlsIdentity=DeviceIdentity.load();identity=new DiscoveryIdentity(tlsIdentity);
        directoryContacts=new DirectoryContacts(context);
        ownFingerprint=node.invitation("127.0.0.1").fingerprint;
        LinkProperties properties=context.getSystemService(ConnectivityManager.class).getLinkProperties(physical.network);
        InetAddress bind=null;
        if(properties!=null)for(LinkAddress value:properties.getLinkAddresses()){
            InetAddress candidate=value.getAddress();
            if(candidate.isLoopbackAddress()||candidate.isAnyLocalAddress()||candidate.isLinkLocalAddress())continue;
            if(bind==null||candidate instanceof Inet4Address)bind=candidate;
            if(candidate instanceof Inet4Address)break;
        }
        hostingAddress=bind;
        node.setDiscoveryExchange(frame->{
            if(!enabled())throw new IOException("Discovery disabled");
            return store.exchange(frame,System.currentTimeMillis());
        });
        worker.scheduleWithFixedDelay(this::cycle,0,3,TimeUnit.SECONDS);
    }
    static String validateUrl(String value){
        if(value==null)throw new IllegalArgumentException("URL");
        value=value.trim();if(value.isEmpty())return "";
        try{
            URI parsed=new URI(value);
            if(!"https".equalsIgnoreCase(parsed.getScheme())||parsed.getHost()==null||parsed.getUserInfo()!=null||parsed.getFragment()!=null||parsed.getQuery()!=null||parsed.getPort()==0||parsed.getPort()>65535||value.length()>2048)
                throw new IllegalArgumentException("HTTPS URL required");
            String path=parsed.getPath();
            if(path==null||path.isEmpty()||"/".equals(path))return value.replaceAll("/+$","")+"/v1/exchange";
            if(!"/v1/exchange".equals(path))throw new IllegalArgumentException("Exchange endpoint required");
            return value;
        }catch(URISyntaxException e){throw new IllegalArgumentException("URL",e);}
    }
    String state(){return state;}
    boolean isPairing(){return !pending.isEmpty();}
    boolean hosting(){return server!=null;}
    int port(){BootstrapHttpServer current=server;return current==null?0:current.port();}
    void configure(String value,boolean host){url=validateUrl(value);hosting=host;cancelRequest();execute(this::cycle);}
    private boolean enabled(){return !closed&&node.isSharing()&&(!url.isEmpty()||hosting||!contacts.get().isEmpty()||!directoryContacts.list().isEmpty());}
    void addDirectoryContact(String code)throws IOException{directoryContacts.add(code);execute(this::cycle);}
    int directoryContactCount(){return directoryContacts.list().size();}
    void allowPrivateDirectories(boolean enabled){directoryContacts.setAllowPrivate(enabled);cancelRequest();execute(this::cycle);}
    void cancel(String fingerprint){cancellations.incrementAndGet();if(pending.equals(fingerprint)){pending="";pendingAttempt=attempts.incrementAndGet();}pairing.cancelTargeted(fingerprint);}
    private void paired(String fingerprint,long attempt){execute(()->{if(pending.equals(fingerprint)&&pendingAttempt==attempt){pending="";pendingUntil=0;state="ONLINE";}});}
    void connect(DiscoveryPresence recipient){long cancellation=cancellations.get();execute(()->{
        if(cancellation!=cancellations.get())return;
        long now=System.currentTimeMillis();
        if(!enabled()||!pending.isEmpty()||recipient.expiresAt<=now||recipient.clients>=3||recipient.upload<=0){listener.failed(recipient.fingerprint);return;}
        pending=recipient.fingerprint;pendingAttempt=attempts.incrementAndGet();pendingUntil=now+180_000;state="PAIRING";
        pairing.offerTo(recipient.fingerprint,sink(recipient,pendingAttempt));
    });}
    private PairingManager.DescriptionSink sink(DiscoveryPresence recipient,long attempt){
        return new PairingManager.DescriptionSink(){
            public void description(String code){execute(()->{
                if(!enabled()||!pending.equals(recipient.fingerprint)||pendingAttempt!=attempt)return;
                try{
                    long now=System.currentTimeMillis();
                    // Gathering may outlive the original advertisement. Resolve only a fresh signed
                    // record for the pinned identity; never extend an old record or reuse its key.
                    DiscoveryPresence current=currentRecipient(store,recipient.fingerprint,now);
                    store.accept(identity.presence(node.country,node.clientCount(),node.measuredUpload(),now),now);publishedAt=now;
                    store.accept(identity.seal(current,code,now),now);cycle();
                }
                catch(IOException failure){AutoDiscoveryManager.this.failed(recipient.fingerprint,attempt);}
            });}
            public void failed(){execute(()->AutoDiscoveryManager.this.failed(recipient.fingerprint,attempt));}
            public void connected(){paired(recipient.fingerprint,attempt);}
        };
    }
    static DiscoveryPresence currentRecipient(DiscoveryStore store,String fingerprint,long now)throws IOException {
        for(DiscoveryPresence value:store.presences(now))if(value.fingerprint.equals(fingerprint))return value;
        throw new IOException("Recipient announcement unavailable");
    }
    private void failed(String fingerprint,long attempt){
        if(pending.equals(fingerprint)&&pendingAttempt==attempt){pending="";pendingUntil=0;state="OFFLINE";listener.failed(fingerprint);}
    }
    private void cycle(){
        if(closed)return;
        try{
            updateHosting();
            if(!enabled()){if(!pending.isEmpty()){String old=pending;pending="";pairing.cancelTargeted(old);listener.failed(old);}state="NOT_CONFIGURED";return;}
            long now=System.currentTimeMillis();
            if(!pending.isEmpty()&&now>pendingUntil){String old=pending;pairing.cancel();failed(old,pendingAttempt);}
            if(node.measuredUpload()<=0){state="STARTING";return;}
            if(now-publishedAt>30_000){
                store.accept(identity.presence(node.country,node.clientCount(),node.measuredUpload(),now),now);publishedAt=now;
            }
            processSignals(now);
            boolean exchanged=false;
            Map<String,FederationContact> directoryServers=new LinkedHashMap<>();
            List<String> candidates=new ArrayList<>();
            for(FederationContact contact:directoryContacts.list()){directoryServers.put(contact.fingerprint,contact);candidates.add(contact.fingerprint);}
            String endpoint=url;if(!endpoint.isEmpty())candidates.add(endpoint);
            int tried=0;
            for(String key:failover.order(candidates,android.os.SystemClock.elapsedRealtime())){
                if(closed||tried++>=3)break;
                try{
                    FederationContact contact=directoryServers.get(key);
                    byte[] response;
                    if(contact==null)response=post(key,store.exportFrame(System.currentTimeMillis()));
                    else response=exchangeDirectory(contact);
                    if(closed)return;store.ingestFrame(response,System.currentTimeMillis());
                    failover.succeeded(key);exchanged=true;break;
                }catch(IOException unavailable){failover.failed(key,android.os.SystemClock.elapsedRealtime());}
            }
            List<Invitation> peers=contacts.get();
            for(int attempt=0;attempt<Math.min(2,peers.size())&&!closed;attempt++){
                Invitation peer=peers.get(Math.floorMod(cursor++,peers.size()));
                try{byte[] response=node.exchangeDiscovery(peer,store.exportFrame(System.currentTimeMillis()));
                    if(closed)return;store.ingestFrame(response,System.currentTimeMillis());exchanged=true;
                }catch(IOException ignored){}
            }
            if(closed)return;
            processSignals(System.currentTimeMillis());
            listener.catalog(store.presences(System.currentTimeMillis()));
            state=!pending.isEmpty()?"PAIRING":exchanged||server!=null?"ONLINE":"OFFLINE";
        }catch(Exception failure){if(!closed)state="OFFLINE";}
    }
    private void processSignals(long now)throws IOException {
        handled.entrySet().removeIf(e->e.getValue()<=now);
        Map<String,DiscoveryPresence> catalog=new HashMap<>();
        for(DiscoveryPresence value:store.presences(now))catalog.put(value.fingerprint,value);
        for(DiscoverySignal signal:store.signalsFor(ownFingerprint,now)){
            if(closed||!enabled()||handled.containsKey(signal.senderFingerprint+":"+signal.id))continue;
            DiscoveryPresence sender=catalog.get(signal.senderFingerprint);
            if(sender==null||sender.fingerprint.equals(ownFingerprint))continue;
            if(!pending.isEmpty()&&!pending.equals(sender.fingerprint))continue;
            if(pending.isEmpty()&&now-lastIncoming<10_000)continue;
            String code;
            PairingCode verified;
            try{code=identity.open(signal,now);verified=PairingCode.parseAndVerify(code,now);}
            catch(IOException invalid){remember(signal);continue;}
            if(!verified.fingerprint.equals(sender.fingerprint)){remember(signal);continue;}
            if("offer".equals(verified.type)){
                if(now-lastIncoming<10_000||(!pending.isEmpty()&&sender.fingerprint.compareTo(ownFingerprint)>=0)){remember(signal);continue;}
                lastIncoming=now;pending=sender.fingerprint;pendingAttempt=attempts.incrementAndGet();pendingUntil=Math.min(now+180_000,verified.expiresAt);
            }else if(!pending.equals(sender.fingerprint)){remember(signal);continue;}
            remember(signal);state="PAIRING";
            pairing.acceptFrom(code,sender.fingerprint,sink(sender,pendingAttempt));
        }
    }
    private void remember(DiscoverySignal signal){
        if(handled.size()>=512)handled.remove(handled.keySet().iterator().next());
        handled.put(signal.senderFingerprint+":"+signal.id,signal.expiresAt);
    }
    private byte[] post(String endpoint,byte[] request)throws IOException {
        HttpURLConnection connection=physical.http(endpoint);
        activeRequest=connection;
        ScheduledFuture<?> deadline=null;
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        try{
            if(closed)throw new InterruptedIOException("Closed");
            deadline=watchdog.schedule(connection::disconnect,20,TimeUnit.SECONDS);
            connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/octet-stream");
            connection.setFixedLengthStreamingMode(request.length);
            try(OutputStream out=connection.getOutputStream()){out.write(request);}
            if(connection.getResponseCode()!=200)throw new IOException("Bootstrap unavailable");
            if(connection.getContentLengthLong()>DiscoveryStore.MAX_FRAME)throw new IOException("Frame too large");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] chunk=new byte[4096];int count;
                while((count=in.read(chunk))!=-1){if(closed||System.nanoTime()>until)throw new InterruptedIOException("Exchange deadline");if(out.size()+count>DiscoveryStore.MAX_FRAME)throw new IOException("Frame too large");out.write(chunk,0,count);}
                return out.toByteArray();
            }
        }finally{if(deadline!=null)deadline.cancel(false);if(activeRequest==connection)activeRequest=null;connection.disconnect();}
    }
    private byte[] exchangeDirectory(FederationContact contact)throws IOException {
        InetSocketAddress address=contact.address(physical.resolve(contact.host),directoryContacts.allowPrivate());
        try(Socket socket=physical.open(address.getAddress(),address.getPort())){
            activeDirectorySocket=socket;
            if(closed)throw new InterruptedIOException("Closed");
            ScheduledFuture<?> deadline=watchdog.schedule(()->{try{socket.close();}catch(IOException ignored){}},12,TimeUnit.SECONDS);
            try{
                FederationExchange.Result result=FederationExchange.exchange(tlsIdentity,socket,contact,store.exportFrame(System.currentTimeMillis()));
                if(closed)throw new InterruptedIOException("Closed");
                directoryContacts.merge(result.contacts);
                return result.discoveryFrame;
            }finally{deadline.cancel(false);activeDirectorySocket=null;}
        }
    }
    private void updateHosting()throws IOException {
        if((!hosting||!node.isSharing())&&server!=null){BootstrapHttpServer old=server;server=null;old.close();}
        if(hosting&&node.isSharing()&&server==null){
            if(hostingAddress==null)throw new IOException("No physical address");
            server=new BootstrapHttpServer(hostingAddress,39282,frame->{
                if(!enabled()||!hosting)throw new IOException("Discovery disabled");
                return store.exchange(frame,System.currentTimeMillis());
            });
        }
    }
    private void cancelRequest(){Socket socket=activeDirectorySocket;if(socket!=null)try{socket.close();}catch(IOException ignored){}HttpURLConnection current=activeRequest;if(current!=null)try{watchdog.execute(current::disconnect);}catch(RejectedExecutionException ignored){}}
    private void execute(Runnable task){if(!closed)try{worker.execute(()->{if(!closed)task.run();});}catch(RejectedExecutionException ignored){}}
    @Override public void close(){
        if(closed)return;closed=true;node.setDiscoveryExchange(null);cancelRequest();watchdog.shutdown();
        worker.execute(()->{BootstrapHttpServer old=server;server=null;if(old!=null)old.close();store.clear();handled.clear();});
        worker.shutdown();
    }
}
