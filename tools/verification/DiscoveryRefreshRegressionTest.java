package org.uzel.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
public class DiscoveryRefreshRegressionTest extends NetworkTest {
 private DiscoveryPresence lookup(DiscoveryStore store,String fingerprint,long now)throws Exception{
  Method method=Class.forName("org.uzel.app.AutoDiscoveryManager").getDeclaredMethod("currentRecipient",DiscoveryStore.class,String.class,long.class);method.setAccessible(true);
  try{return (DiscoveryPresence)method.invoke(null,store,fingerprint,now);}catch(InvocationTargetException failure){throw (Exception)failure.getCause();}
 }
 @Test public void refreshedRecipientWorksAfterGatherOutlivesOriginalTtl()throws Exception{
  long now=System.currentTimeMillis(),beforeGather=now-130000;
  DiscoveryIdentity alice=new DiscoveryIdentity(keys.get(0)),bob=new DiscoveryIdentity(keys.get(1));
  DiscoveryPresence stale=bob.presence("DE",0,1000,beforeGather);DiscoveryStore store=new DiscoveryStore();store.accept(stale,beforeGather);
  DiscoveryPresence fresh=bob.presence("DE",0,1000,now-1000);store.accept(fresh,now);
  alice.presence("RU",0,1000,now);String code=PairingCode.offer(keys.get(0),"v=0",node(0).invitation("127.0.0.1"),now).encode();
  try{alice.seal(stale,code,now);fail("Original callback recipient must reproduce expired failure");}catch(IOException expected){}
  DiscoverySignal signal=alice.seal(lookup(store,stale.fingerprint,now),code,now);
  assertEquals(code,bob.open(signal,now));assertTrue(signal.expiresAt<=fresh.expiresAt);
  try{DiscoverySignal.parse(signal.encode(),fresh.expiresAt);fail("No TTL extension");}catch(IOException expected){}
 }
 @Test public void expiredOrDifferentIdentityIsNeverFallback()throws Exception{
  long now=System.currentTimeMillis();DiscoveryStore store=new DiscoveryStore();DiscoveryIdentity bob=new DiscoveryIdentity(keys.get(1)),other=new DiscoveryIdentity(keys.get(2));
  DiscoveryPresence stale=bob.presence("DE",0,1000,now-130000);store.accept(stale,now-130000);store.accept(other.presence("FR",0,1000,now),now);
  try{lookup(store,stale.fingerprint,now);fail("Must not substitute another recipient or stale record");}catch(IOException expected){}
 }
 @Test public void refreshedSignedKeyRemainsBoundToRecipient()throws Exception{
  long now=System.currentTimeMillis();DiscoveryIdentity alice=new DiscoveryIdentity(keys.get(0)),oldBob=new DiscoveryIdentity(keys.get(1)),newBob=new DiscoveryIdentity(keys.get(1));
  DiscoveryStore store=new DiscoveryStore();DiscoveryPresence old=oldBob.presence("DE",0,1000,now-1000);store.accept(old,now);store.accept(newBob.presence("DE",0,1000,now),now);
  alice.presence("RU",0,1000,now);String code=PairingCode.offer(keys.get(0),"v=0",node(0).invitation("127.0.0.1"),now).encode();
  DiscoverySignal signal=alice.seal(lookup(store,old.fingerprint,now),code,now);
  try{oldBob.open(signal,now);fail("Old ephemeral key must not decrypt");}catch(IOException expected){}
  assertEquals(code,newBob.open(signal,now));
 }
}
