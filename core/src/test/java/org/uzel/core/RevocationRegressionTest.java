package org.uzel.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.concurrent.*;
public class RevocationRegressionTest extends NetworkTest {
 @Test public void blockedPumpCannotForwardAfterLogicalPublicStopEvenAfterCleanup()throws Exception {
  Node n=node(0); n.setPublicMode(true);
  CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);
  InputStream input=new InputStream(){boolean sent;public int read(){throw new UnsupportedOperationException();}
   public int read(byte[] b,int off,int len)throws IOException{reading.countDown();try{release.await();}catch(InterruptedException e){throw new IOException(e);}if(sent)return -1;sent=true;b[off]=42;return 1;}};
  ByteArrayOutputStream output=new ByteArrayOutputStream();
  ExecutorService worker=Executors.newSingleThreadExecutor();
  try{Future<?> pump=worker.submit(()->{try{n.pump(input,output,false,null);}catch(IOException expected){}});
   assertTrue(reading.await(2,TimeUnit.SECONDS));n.stopPublicAdmission();n.setPublicMode(false);release.countDown();pump.get(2,TimeUnit.SECONDS);assertEquals(0,output.size());
  }finally{release.countDown();worker.shutdownNow();}
 }
 @Test public void invalidatingSessionStillAllowsPhysicalClose()throws Exception {
  Node a=node(0),b=node(1);Node.Session s=a.connect(b.invitation("127.0.0.1"),()->{});cleanup.add(s);
  s.invalidate();assertFalse(s.isLive());s.close();
  java.lang.reflect.Field f=s.getClass().getDeclaredField("control");f.setAccessible(true);assertTrue(((java.net.Socket)f.get(s)).isClosed());s.close();
 }
}
