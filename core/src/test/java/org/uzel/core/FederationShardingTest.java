package org.uzel.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Explicit durable replicas, with no recursive payload gossip. */
public class FederationShardingTest extends NetworkTest {
 private FederationNode node(int key,Path state)throws Exception {
  FederationNode n=new FederationNode(keys.get(key),state,InetAddress.getLoopbackAddress(),0,"127.0.0.1",true);n.start();cleanup.add(n);return n;
 }
 private static int role(FederationNode n,String name) {
  try{return n.snapshot().getClass().getField(name).getInt(n.snapshot());}catch(ReflectiveOperationException e){throw new AssertionError("Missing explicit storage role: "+name,e);}
 }
 private static void lookup(FederationNode n)throws Exception {
  try{n.getClass().getMethod("lookup").invoke(n);}catch(NoSuchMethodException e){throw new AssertionError("Missing bounded distributed lookup",e);}
 }
 private void eventually(BooleanSupplier condition)throws Exception {
  long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);while(!condition.getAsBoolean() && System.nanoTime()<end)Thread.sleep(50);assertTrue("Replica state did not converge",condition.getAsBoolean());
 }
 @Test public void twoAckedDurableReplicasSurviveOriginLossAndFourthOnlyQueries()throws Exception {
  FederationNode a=node(0,Files.createTempDirectory("shard-origin"));List<FederationNode> holders=new ArrayList<>();List<Path> paths=new ArrayList<>();
  for(int i=1;i<=3;i++){Path path=Files.createTempDirectory("shard-replica");paths.add(path);FederationNode n=node(i,path);holders.add(n);a.addContact(n.ownContact().encode());n.addContact(a.ownContact().encode());}
  long now=System.currentTimeMillis();a.discoveryStore().accept(new DiscoveryIdentity(keys.get(4)).presence("RU",0,1000,now),now);
  eventually(()->role(a,"fullyReplicatedRecords")==1);
  assertEquals(1,role(a,"publishedRecords"));assertEquals(2,holders.stream().mapToInt(n->role(n,"replicaRecords")).sum());
  FederationNode querying=holders.stream().filter(n->role(n,"replicaRecords")==0).findFirst().orElseThrow();
  assertTrue(querying.snapshot().presences.isEmpty());
  List<FederationNode> copies=new ArrayList<>();for(FederationNode n:holders)if(role(n,"replicaRecords")>0)copies.add(n);
  a.close();
  // Crash/restart both acknowledged holders: durable data must still be independently searchable.
  for(FederationNode copy:copies){int index=holders.indexOf(copy);Path crash=Files.createTempDirectory("after-ack-crash");Files.copy(paths.get(index).resolve("federation-state.bin"),crash.resolve("federation-state.bin"));copy.close();FederationNode restarted=node(index+1,crash);assertEquals(1,role(restarted,"replicaRecords"));querying.addContact(restarted.ownContact().encode());}
  lookup(querying);assertEquals(1,querying.snapshot().presences.size());assertEquals(0,role(querying,"replicaRecords"));assertTrue(role(querying,"cachedQueryRecords")>0);
  querying.close();FederationNode fresh=node(holders.indexOf(querying)+1,paths.get(holders.indexOf(querying)));assertEquals(0,role(fresh,"replicaRecords"));assertTrue(fresh.snapshot().presences.isEmpty());
 }
 @Test public void queriedRecordsAreNotRepublishedAsLocalPublications()throws Exception {
  FederationNode a=node(0,Files.createTempDirectory("shard-publish-a")),b=node(1,Files.createTempDirectory("shard-publish-b"));long now=System.currentTimeMillis();
  DiscoveryStore upload=new DiscoveryStore();upload.accept(new DiscoveryIdentity(keys.get(2)).presence("RU",0,1000,now),now);
  try(Socket socket=new Socket("127.0.0.1",a.ownContact().port)){FederationExchange.exchange(keys.get(1),socket,a.ownContact(),upload.exportFrame(now));}
  assertEquals(0,role(a,"publishedRecords"));
  upload.clear();upload.accept(new DiscoveryIdentity(keys.get(1)).presence("DE",0,1000,now),now);
  try(Socket socket=new Socket("127.0.0.1",b.ownContact().port)){FederationExchange.exchange(keys.get(1),socket,b.ownContact(),upload.exportFrame(now));}
  assertEquals(1,role(b,"publishedRecords"));
 }
 @Test public void cyclicLookupSkipsAlreadyVisitedBranchesAndDeduplicatesQueryId()throws Exception {
  List<FederationNode> nodes=new ArrayList<>();for(int i=0;i<5;i++)nodes.add(node(i,Files.createTempDirectory("query-cycle")));
  for(FederationNode a:nodes)for(FederationNode b:nodes)if(a!=b)a.addContact(b.ownContact().encode());
  FederationNode.LookupResult answer=nodes.get(0).lookup();assertTrue(answer.partial);assertEquals("Visited branches must not hide an unvisited reachable neighbor",5,answer.visitedServers);assertTrue(answer.visitedServers<=7);
  String id=TlsIdentity.randomSecret();long deadline=System.currentTimeMillis()+5000;
  FederationExchange.Message request=new FederationExchange.Message(FederationExchange.QUERY,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),id,2,deadline,Collections.emptyList(),true);
  try(Socket socket=new Socket("127.0.0.1",nodes.get(0).ownContact().port)){FederationExchange.transact(keys.get(4),socket,nodes.get(0).ownContact(),request,7000);}
  Thread.sleep(120);
  try(Socket socket=new Socket("127.0.0.1",nodes.get(0).ownContact().port)) {
   FederationExchange.Message repeated=FederationExchange.transact(keys.get(4),socket,nodes.get(0).ownContact(),request,7000);assertEquals("Repeated ID must not fan out",1,repeated.visited.size());
  }
 }
 @Test public void replicaSourceQuotaRetainsPromisedVersionsAndAllowsAnotherOrigin()throws Exception {
  Path state=Files.createTempDirectory("replica-versions");FederationNode holder=node(0,state);DiscoveryIdentity author=new DiscoveryIdentity(keys.get(2));long now=System.currentTimeMillis();FederationRecord oldest=null;
  for(int batch=0;batch<1;batch++) {
   List<FederationRecord> records=new ArrayList<>();for(int i=0;i<32;i++){FederationRecord r=FederationRecord.of(author.presence("RU",0,100,now-batch*64-i));records.add(r);oldest=r;}
   if(batch>0)Thread.sleep(510);
   try(Socket socket=new Socket("127.0.0.1",holder.ownContact().port)) {
    FederationExchange.Message result=FederationExchange.transact(keys.get(1),socket,holder.ownContact(),FederationExchange.Message.simple(FederationExchange.REPLICA,records,Collections.emptyList()),8000);assertEquals(32,result.acks.size());for(FederationAck ack:result.acks)assertTrue(records.stream().anyMatch(ack::matches));
   }
  }
  assertEquals(32,holder.snapshot().replicaRecords);Thread.sleep(510);
  FederationRecord extra=FederationRecord.of(author.presence("DE",0,200,now+1));
  try(Socket socket=new Socket("127.0.0.1",holder.ownContact().port)){FederationExchange.Message result=FederationExchange.transact(keys.get(1),socket,holder.ownContact(),FederationExchange.Message.simple(FederationExchange.REPLICA,Collections.singletonList(extra),Collections.emptyList()),8000);assertTrue(result.acks.isEmpty());}
  assertEquals(32,holder.snapshot().replicaRecords);assertTrue(holder.snapshot().capacityLimited);
  Path crash=Files.createTempDirectory("retained-versions-crash");Files.copy(state.resolve("federation-state.bin"),crash.resolve("federation-state.bin"));FederationNode restored=node(0,crash);assertEquals(32,restored.snapshot().replicaRecords);
  try(Socket socket=new Socket("127.0.0.1",restored.ownContact().port)){FederationExchange.Message result=FederationExchange.transact(keys.get(1),socket,restored.ownContact(),FederationExchange.Message.simple(FederationExchange.REPLICA,Collections.singletonList(oldest),Collections.emptyList()),8000);assertEquals(1,result.acks.size());assertTrue(result.acks.get(0).matches(oldest));assertFalse(result.acks.get(0).matches(extra));}
  try(Socket socket=new Socket("127.0.0.1",restored.ownContact().port)){FederationExchange.Message result=FederationExchange.transact(keys.get(3),socket,restored.ownContact(),FederationExchange.Message.simple(FederationExchange.REPLICA,Collections.singletonList(extra),Collections.emptyList()),8000);assertEquals("Another origin still has capacity",1,result.acks.size());}
  assertEquals(33,restored.snapshot().replicaRecords);
 }
 @Test public void failedHolderIsReplacedWithoutDroppingOrigin()throws Exception {
  FederationNode origin=node(0,Files.createTempDirectory("replacement-origin"));List<FederationNode> others=new ArrayList<>();for(int i=1;i<=3;i++){FederationNode n=node(i,Files.createTempDirectory("replacement-holder"));others.add(n);origin.addContact(n.ownContact().encode());}
  long now=System.currentTimeMillis();origin.discoveryStore().accept(new DiscoveryIdentity(keys.get(4)).presence("RU",0,1000,now),now);eventually(()->role(origin,"fullyReplicatedRecords")==1);
  FederationNode replacement=others.stream().filter(n->role(n,"replicaRecords")==0).findFirst().orElseThrow();FederationNode failed=others.stream().filter(n->role(n,"replicaRecords")==1).findFirst().orElseThrow();failed.close();
  eventually(()->role(replacement,"replicaRecords")==1 && role(origin,"fullyReplicatedRecords")==1);assertEquals(1,role(origin,"publishedRecords"));
 } @Test public void denseCatalogDeliversOnlyRecipientSignalsAndStillIncludesRoutes()throws Exception {
  FederationNode server=node(0,Files.createTempDirectory("query-fair"));long now=System.currentTimeMillis();
  for(int i=0;i<65;i++){TlsIdentity author=FederationTestIdentities.create();server.discoveryStore().accept(new DiscoveryIdentity(author).presence("RU",0,1000,now),now);}
  DiscoveryIdentity recipient=new DiscoveryIdentity(keys.get(1)),other=new DiscoveryIdentity(keys.get(4)),sender=new DiscoveryIdentity(keys.get(2));sender.presence("RU",0,1000,now);
  DiscoveryPresence target=recipient.presence("DE",0,1000,now),unrelated=other.presence("FR",0,1000,now);
  String offer=PairingCode.offer(keys.get(2),"v=0",new Invitation("1.1.1.1",1234,keys.get(2).fingerprint,TlsIdentity.randomSecret()),now).encode();
  for(int i=0;i<7;i++)server.discoveryStore().accept(sender.seal(unrelated,offer,now),now);server.discoveryStore().accept(sender.seal(target,offer,now),now);
  server.acceptRoute(PeerDirectory.Record.create(keys.get(2),PeerRoute.direct("1.1.1.1",1234,keys.get(2).fingerprint),"RU",1));
  try(Socket socket=new Socket("127.0.0.1",server.ownContact().port)) {
   FederationExchange.Result result=FederationExchange.exchange(keys.get(1),socket,server.ownContact(),new DiscoveryStore().exportFrame(now));DiscoveryStore response=new DiscoveryStore();response.ingestFrame(result.discoveryFrame,System.currentTimeMillis());
   assertEquals("Recipient message must survive a catalog larger than one result page",1,response.signalsFor(target.fingerprint,System.currentTimeMillis()).size());assertTrue(response.signalsFor(unrelated.fingerprint,System.currentTimeMillis()).isEmpty());assertEquals(1,result.routes.size());assertFalse(response.presences(System.currentTimeMillis()).isEmpty());assertEquals(offer,recipient.open(response.signalsFor(target.fingerprint,System.currentTimeMillis()).get(0),System.currentTimeMillis()));
  }
 } @Test public void reachableHolderRejectingRecordDoesNotBlockSpare()throws Exception {
  FederationNode origin=node(0,Files.createTempDirectory("rejected-origin"));List<FederationNode> holders=new ArrayList<>();for(int i=1;i<=3;i++){FederationNode holder=node(i,Files.createTempDirectory("rejected-holder"));holders.add(holder);origin.addContact(holder.ownContact().encode());}
  DiscoveryIdentity author=new DiscoveryIdentity(keys.get(4));long now=System.currentTimeMillis();FederationRecord publication=FederationRecord.of(author.presence("RU",0,1000,now));
  Map<FederationNode,String> scores=new HashMap<>();for(FederationNode holder:holders)scores.put(holder,FederationRecord.hex(DiscoveryWire.hash((publication.id+":"+holder.ownContact().fingerprint).getBytes(java.nio.charset.StandardCharsets.US_ASCII))));holders.sort((a,b)->scores.get(b).compareTo(scores.get(a)));
  FederationNode full=holders.get(0),spare=holders.get(2);List<FederationRecord> retained=new ArrayList<>();for(int i=1;i<=32;i++)retained.add(FederationRecord.of(author.presence("RU",0,1000,now-i)));
  try(Socket socket=new Socket("127.0.0.1",full.ownContact().port)){FederationExchange.Message reply=FederationExchange.transact(keys.get(0),socket,full.ownContact(),FederationExchange.Message.simple(FederationExchange.REPLICA,retained,Collections.emptyList()),8000);assertEquals(32,reply.acks.size());}
  origin.discoveryStore().accept((DiscoveryPresence)publication.value,now);
  eventually(()->origin.snapshot().fullyReplicatedRecords==1 && spare.snapshot().replicaRecords==1);assertEquals(32,full.snapshot().replicaRecords);
 } @Test public void ackStatesActualPlatformDurabilityAndRejectsUnknownGuarantee()throws Exception {
  FederationNode holder=node(0,Files.createTempDirectory("ack-durability"));long now=System.currentTimeMillis();FederationRecord record=FederationRecord.of(new DiscoveryIdentity(keys.get(2)).presence("RU",0,1000,now));FederationAck ack;
  try(Socket socket=new Socket("127.0.0.1",holder.ownContact().port)){FederationExchange.Message response=FederationExchange.transact(keys.get(1),socket,holder.ownContact(),FederationExchange.Message.simple(FederationExchange.REPLICA,Collections.singletonList(record),Collections.emptyList()),8000);ack=response.acks.get(0);}
  java.lang.reflect.Field field;try{field=FederationAck.class.getDeclaredField("durability");}catch(NoSuchFieldException e){throw new AssertionError("ACK does not state its actual durability guarantee",e);}field.setAccessible(true);String guarantee=(String)field.get(ack);
  assertEquals(System.getProperty("os.name").startsWith("Windows")?"PROCESS_CRASH":"DIRECTORY_SYNCED",guarantee);
  java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);out.writeUTF("PEERNET-FEDERATION-ACK-2");out.writeUTF(record.digest);out.writeUTF(record.id);out.writeLong(record.version);out.writeLong(record.expiresAt);out.writeUTF("POWER_LOSS_ALWAYS");
  try{FederationAck.parse(DiscoveryWire.signed(keys.get(0),bytes.toByteArray()),now);fail("Unknown durability enum");}catch(java.io.IOException expected){}
 }}
