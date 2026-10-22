package org.uzel.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Real independent TLS directory nodes and signed Android-compatible announcements. */
public class FederationTest extends NetworkTest {
 private FederationNode federation(int key,Path state)throws Exception {
  FederationNode n=new FederationNode(keys.get(key),state,InetAddress.getLoopbackAddress(),0,"127.0.0.1",true);
  n.start();cleanup.add(n);return n;
 }
 private void eventually(BooleanSupplier condition)throws Exception {
  long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
  while(!condition.getAsBoolean() && System.nanoTime()<until)Thread.sleep(40);
  assertTrue("Directory did not converge",condition.getAsBoolean());
 }
 @Test public void signedCodeRejectsTamperExpiryAndPrivateAddresses()throws Exception {
  long now=System.currentTimeMillis();
  FederationContact c=FederationContact.create(keys.get(0),"127.0.0.1",12345,now);
  assertEquals(keys.get(0).fingerprint,FederationContact.parse(c.encode()).fingerprint);
  assertEquals(12345,c.address(true).getPort());
  try{c.address(new InetAddress[]{InetAddress.getByName("1.1.1.1"),InetAddress.getLoopbackAddress()},false);fail("mixed DNS answer");}catch(IOException expected){}
  try{c.address(new InetAddress[]{null},false);fail("null DNS answer");}catch(IOException expected){}
  try{c.address(false);fail("private address");}catch(IOException expected){}
  String bad=c.encode();bad=bad.substring(0,bad.length()-8)+"AAAAAAAA";
  try{FederationContact.parse(bad);fail("signature");}catch(IOException expected){}
  try{FederationContact.parse("peernet://"+"a".repeat(50000));fail("size");}catch(IOException expected){}
  try{FederationContact.create(keys.get(0),"127.0.0.1",1,now-FederationContact.TTL_MILLIS-1);fail("expiry");}catch(IOException expected){}
  for(String host:new String[]{"0.0.0.0","169.254.169.254","224.0.0.1","100.64.0.1","::1","fc00::1"}) {
   try{FederationContact.create(keys.get(0),host,80,now).address(false);fail(host);}catch(IOException expected){}
  }
 }
 @Test public void threeNodesConvergeAndBackupSurvivesPrimaryLossAndRestart()throws Exception {
  Path sa=Files.createTempDirectory("federation-a"),sb=Files.createTempDirectory("federation-b"),sc=Files.createTempDirectory("federation-c");
  FederationNode a=federation(0,sa),b=federation(1,sb),c=federation(2,sc);
  long now=System.currentTimeMillis();
  a.discoveryStore().accept(new DiscoveryIdentity(keys.get(3)).presence("RU",0,1000,now),now);
  a.addContact(b.ownContact().encode());c.addContact(b.ownContact().encode());
  eventually(()->c.snapshot().presences.size()==1 && c.snapshot().contacts.stream().anyMatch(x->x.fingerprint.equals(keys.get(0).fingerprint)));
  assertTrue(FederationContact.parse(c.ownContact().encode()).backups.size()>0);
  b.close();
  long later=System.currentTimeMillis()+1;
  a.discoveryStore().accept(new DiscoveryIdentity(keys.get(4)).presence("DE",0,2000,later),later);
  eventually(()->c.snapshot().presences.size()==2);
  c.close();FederationNode restarted=federation(2,sc);
  assertTrue(restarted.snapshot().contacts.stream().anyMatch(x->x.fingerprint.equals(keys.get(0).fingerprint)));
  assertEquals(2,restarted.snapshot().presences.size());
  assertEquals(keys.get(2).fingerprint,restarted.ownContact().fingerprint);
 }
 @Test public void expiredSnapshotRecordsDoNotReviveAndCorruptionIsSafe()throws Exception {
  Path path=Files.createTempDirectory("federation-expiry");FederationNode a=federation(0,path);
  long now=System.currentTimeMillis();a.discoveryStore().accept(new DiscoveryIdentity(keys.get(1)).presence("RU",0,100,now-119990),now-119990);
  a.close();Thread.sleep(80);FederationNode b=federation(0,path);assertTrue(b.snapshot().presences.isEmpty());b.close();
  Files.write(path.resolve("federation-state.bin"),new byte[]{1,2,3});
  FederationNode repaired=federation(0,path);assertTrue(repaired.snapshot().presences.isEmpty());assertFalse(repaired.snapshot().lastError.isEmpty());
 }
 @Test public void persistenceRetainsSignalsBeyondOneGossipFrame()throws Exception {
  Path path=Files.createTempDirectory("federation-large-state");FederationNode a=federation(0,path);
  DiscoveryIdentity sender=new DiscoveryIdentity(keys.get(1)),recipient=new DiscoveryIdentity(keys.get(2));long now=System.currentTimeMillis();
  DiscoveryPresence target=recipient.presence("DE",0,100,now);sender.presence("RU",0,100,now);
  Invitation invitation=new Invitation("1.1.1.1",1234,keys.get(1).fingerprint,TlsIdentity.randomSecret());
  String offer=PairingCode.offer(keys.get(1),"v=0"+"x".repeat(20000),invitation,now).encode();
  for(int i=0;i<8;i++)a.discoveryStore().accept(sender.seal(target,offer,now),now);
  assertEquals(8,a.discoveryStore().signalsFor(target.fingerprint,now).size());a.close();
  FederationNode restored=federation(0,path);
  assertEquals("Snapshot must preserve every live ciphertext, not a frame sample",8,restored.discoveryStore().signalsFor(target.fingerprint,System.currentTimeMillis()).size());
 }
 @Test public void sourceQuotaBoundsOpaqueSignalsWithoutOpeningThem()throws Exception {
  Path state=Files.createTempDirectory("federation-quota");FederationNode a=federation(0,state);long now=System.currentTimeMillis();
  DiscoveryIdentity recipient=new DiscoveryIdentity(keys.get(4));DiscoveryPresence target=recipient.presence("DE",0,100,now);DiscoveryStore batch=new DiscoveryStore();
  for(int k=1;k<=3;k++) {
   DiscoveryIdentity sender=new DiscoveryIdentity(keys.get(k));sender.presence("RU",0,100,now);
   String offer=PairingCode.offer(keys.get(k),"v=0",new Invitation("1.1.1.1",1234,keys.get(k).fingerprint,TlsIdentity.randomSecret()),now).encode();
   for(int i=0;i<8;i++)batch.accept(sender.seal(target,offer,now),now);
  }
  try(Socket socket=new Socket("127.0.0.1",a.ownContact().port)){FederationExchange.transact(keys.get(1),socket,a.ownContact(),FederationExchange.Message.simple(FederationExchange.REPLICA,FederationRecord.frame(batch.exportFrame(now),now),Collections.emptyList()),8000);}
  assertEquals(16,a.snapshot().replicaRecords);assertTrue(a.snapshot().capacityLimited);
  a.close();FederationNode restored=federation(0,state);
  try(Socket socket=new Socket("127.0.0.1",restored.ownContact().port)){FederationExchange.transact(keys.get(1),socket,restored.ownContact(),FederationExchange.Message.simple(FederationExchange.REPLICA,FederationRecord.frame(batch.exportFrame(System.currentTimeMillis()),System.currentTimeMillis()),Collections.emptyList()),8000);}
  assertEquals("Source quota survives restart",16,restored.snapshot().replicaRecords);
 }
 @Test public void shutdownEndsPartialFrameAndPartitionsRejoinOnSameIdentity()throws Exception {
  Path path=Files.createTempDirectory("federation-rejoin");FederationNode a=federation(0,path),b=federation(1,Files.createTempDirectory("federation-rejoin-b"));
  int port=a.ownContact().port;b.addContact(a.ownContact().encode());eventually(()->b.snapshot().connectedNeighbors>0);
  try(javax.net.ssl.SSLSocket partial=(javax.net.ssl.SSLSocket)keys.get(2).context(a.ownContact().fingerprint).getSocketFactory().createSocket("127.0.0.1",port)) {
   partial.startHandshake();new DataOutputStream(partial.getOutputStream()).writeInt(1024);a.close();partial.setSoTimeout(2000);assertEquals(-1,partial.getInputStream().read());
  }
  long now=System.currentTimeMillis();b.discoveryStore().accept(new DiscoveryIdentity(keys.get(3)).presence("RU",0,1000,now),now);
  FederationNode restored=new FederationNode(keys.get(0),path,InetAddress.getLoopbackAddress(),port,"127.0.0.1",true);restored.start();cleanup.add(restored);
  eventually(()->restored.snapshot().presences.size()==1);
 }
 @Test public void absoluteDeadlineClosesSlowTrickleFrame()throws Exception {
  FederationNode a=federation(0,Files.createTempDirectory("federation-trickle"));
  try(javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)keys.get(1).context(a.ownContact().fingerprint).getSocketFactory().createSocket("127.0.0.1",a.ownContact().port)) {
   tls.startHandshake();DataOutputStream out=new DataOutputStream(tls.getOutputStream());out.writeInt(10000);out.flush();
   java.util.concurrent.ScheduledExecutorService trickle=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
   try {
    trickle.scheduleWithFixedDelay(()->{try{out.writeByte(1);out.flush();}catch(IOException ignored){}},0,200,java.util.concurrent.TimeUnit.MILLISECONDS);
    tls.setSoTimeout(10000);long start=System.nanoTime();
    try{assertEquals(-1,tls.getInputStream().read());}catch(java.net.SocketTimeoutException timeout){fail("Peer kept handler forever by trickling bytes");}catch(java.net.SocketException closed){}
    assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<9500);
   }finally{trickle.shutdownNow();}
  }
 }
 @Test public void pinMismatchAndOversizeDiscoveryAreRejected()throws Exception {
  FederationNode a=federation(0,Files.createTempDirectory("federation-pin"));
  FederationContact bad=FederationContact.create(keys.get(1),"127.0.0.1",a.ownContact().port,System.currentTimeMillis());
  try(Socket socket=new Socket("127.0.0.1",a.ownContact().port)) {
   try{FederationExchange.exchange(keys.get(2),socket,bad,new DiscoveryStore().exportFrame(System.currentTimeMillis()));fail("pin");}catch(IOException expected){}
  }
  try(Socket socket=new Socket("127.0.0.1",a.ownContact().port)) {
   try{FederationExchange.exchange(keys.get(2),socket,a.ownContact(),new byte[DiscoveryStore.MAX_FRAME+1]);fail("frame bound");}catch(IOException expected){}
  }
 }
 @Test public void replayCannotReplaceNewPresenceAndRoutesRemainSigned()throws Exception {
  FederationNode a=federation(0,Files.createTempDirectory("federation-replay-a")),b=federation(1,Files.createTempDirectory("federation-replay-b"));
  long now=System.currentTimeMillis();DiscoveryIdentity d=new DiscoveryIdentity(keys.get(2));
  DiscoveryPresence old=d.presence("RU",0,100,now-100),newer=d.presence("DE",0,200,now);
  a.discoveryStore().accept(newer,now);
  DiscoveryStore sender=new DiscoveryStore();sender.accept(old,now);
  try(Socket socket=new Socket("127.0.0.1",a.ownContact().port)){FederationExchange.exchange(keys.get(1),socket,a.ownContact(),sender.exportFrame(now));}
  assertEquals("DE",a.snapshot().presences.get(0).country);
  PeerDirectory.Record route=PeerDirectory.Record.create(keys.get(2),PeerRoute.direct("1.1.1.1",1234,keys.get(2).fingerprint),"DE",1);
  assertTrue(a.acceptRoute(route));b.addContact(a.ownContact().encode());
  eventually(()->b.snapshot().routes.size()==1);
 }
 @Test public void expiredBackupDoesNotInvalidateSignedOuterAndBadBackupSignatureStillFails()throws Exception {
  long now=System.currentTimeMillis();byte[] old=signedContact(keys.get(1),now-FederationContact.TTL_MILLIS-1,Collections.emptyList());
  byte[] outer=signedContact(keys.get(0),now,Collections.singletonList(old));FederationContact code=FederationContact.parseBytes(outer,now,true);assertEquals(keys.get(0).fingerprint,code.fingerprint);assertTrue(code.backups.isEmpty());
  old[old.length-10]^=1;
  try{FederationContact.parseBytes(signedContact(keys.get(0),now,Collections.singletonList(old)),now,true);fail("Expired backup must still have a valid signature");}catch(IOException expected){}
  try{FederationExchange.decode(new byte[]{0x50,0x4e,0x46,0x31},now);fail("Old federation version");}catch(IOException expected){}
 }
 private byte[] signedContact(TlsIdentity key,long issued,List<byte[]> backups)throws Exception {
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeUTF("PEERNET-FEDERATION-CONTACT-1");out.writeUTF("127.0.0.1");out.writeInt(1234);out.writeLong(issued);out.writeLong(issued+FederationContact.TTL_MILLIS);out.writeInt(backups.size());for(byte[] backup:backups)DiscoveryWire.field(out,backup);return DiscoveryWire.signed(key,bytes.toByteArray());
 } @Test public void dnsResolutionHasBoundedConcurrencyAndAbsoluteDeadline()throws Exception {
  Class<?> type;try{type=Class.forName("org.uzel.core.FederationResolver");}catch(ClassNotFoundException e){throw new AssertionError("Missing bounded DNS resolver",e);}
  java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.atomic.AtomicInteger active=new java.util.concurrent.atomic.AtomicInteger(),peak=new java.util.concurrent.atomic.AtomicInteger();
  java.util.function.Function<String,InetAddress[]> lookup=host->{int current=active.incrementAndGet();peak.accumulateAndGet(current,Math::max);try{while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return new InetAddress[]{InetAddress.getLoopbackAddress()};}finally{active.decrementAndGet();}};
  java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(java.util.function.Function.class);constructor.setAccessible(true);AutoCloseable resolver=(AutoCloseable)constructor.newInstance(lookup);java.lang.reflect.Method resolve=type.getDeclaredMethod("resolve",FederationContact.class,boolean.class,long.class);resolve.setAccessible(true);
  java.util.concurrent.ExecutorService clients=java.util.concurrent.Executors.newFixedThreadPool(10);FederationContact contact=FederationContact.create(keys.get(0),"127.0.0.1",1234,System.currentTimeMillis());long start=System.nanoTime();
  try {
   List<java.util.concurrent.Future<?>> calls=new ArrayList<>();for(int i=0;i<10;i++)calls.add(clients.submit(()->{try{resolve.invoke(resolver,contact,true,System.currentTimeMillis()+150);throw new AssertionError("DNS deadline ignored");}catch(java.lang.reflect.InvocationTargetException expected){assertTrue(expected.getCause() instanceof IOException);}catch(ReflectiveOperationException bad){throw new AssertionError(bad);}}));
   for(java.util.concurrent.Future<?> call:calls)call.get(2,java.util.concurrent.TimeUnit.SECONDS);
   assertTrue("Native lookups must not grow an unbounded worker pool",peak.get()<=2);assertTrue(peak.get()>0);assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500);
  }finally{release.countDown();clients.shutdownNow();resolver.close();}
 }}
