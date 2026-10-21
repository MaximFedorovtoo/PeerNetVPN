package org.uzel.app;

import android.content.Context;
import android.net.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.security.auth.x500.X500Principal;
import org.uzel.app.traversal.TraversalTransport;
import org.uzel.core.Invitation;
import org.uzel.core.PairingCode;
import org.uzel.core.TlsIdentity;

/** Real SCTP/DTLS + signed SDP lifecycle tests; no VPN and no production key aliases. */
@SuppressWarnings("deprecation")
public final class TraversalTransportTest extends InstrumentationTestCase {
    private final List<String> aliases=new ArrayList<>();
    private KeyStore store;
    @Override protected void setUp()throws Exception{super.setUp();store=KeyStore.getInstance("AndroidKeyStore");store.load(null);}
    @Override protected void tearDown()throws Exception{try{for(String alias:aliases)store.deleteEntry(alias);}finally{super.tearDown();}}
    private Network physical()throws IOException {
        ConnectivityManager manager=getInstrumentation().getTargetContext().getSystemService(ConnectivityManager.class);
        for(Network network:manager.getAllNetworks()){NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            if(caps!=null&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))return network;}
        throw new IOException("A real physical network is required for traversal instrumentation");
    }
    private TlsIdentity identity()throws Exception {
        String alias="peernet-traversal-test-"+UUID.randomUUID();assertFalse(store.containsAlias(alias));aliases.add(alias);
        long now=System.currentTimeMillis();KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(new X500Principal("CN=Isolated traversal fixture"))
                .setCertificateSerialNumber(new BigInteger(120,new SecureRandom()).add(BigInteger.ONE))
                .setCertificateNotBefore(new Date(now-86400000L)).setCertificateNotAfter(new Date(now+7*86400000L)).build());
        generator.generateKeyPair();return TlsIdentity.fromKeyStore(store,null,alias);
    }
    public void testCloseBeforeNegotiationAndSdpBounds()throws Exception {
        AtomicInteger callbacks=new AtomicInteger();
        TraversalTransport.Listener listener=new TraversalTransport.Listener(){public void description(String t,String s){callbacks.incrementAndGet();}public void state(String s){callbacks.incrementAndGet();}public void failed(String s){callbacks.incrementAndGet();}};
        TraversalTransport transport=new TraversalTransport(getInstrumentation().getTargetContext(),physical(),39281,listener);
        assertEquals("Construction cannot call an unassigned owner",0,callbacks.get());
        try{transport.acceptOffer("not-sdp");fail("Malformed SDP accepted");}catch(IllegalArgumentException expected){}
        try{transport.acceptAnswer("v=0"+new String(new char[128*1024]));fail("Oversized SDP accepted");}catch(IllegalArgumentException expected){}
        transport.close();transport.close();assertFalse(transport.isConnected());
        try{transport.openStream();fail("Closed transport opened a stream");}catch(IOException expected){}
        // Closing releases the global detector so a replacement factory is usable.
        TraversalTransport replacement=new TraversalTransport(getInstrumentation().getTargetContext(),physical(),39281,listener);
        replacement.close();assertEquals(0,callbacks.get());
    }
    public void testSignedSdpChunkedEchoAndHalfClose()throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        byte[] payload=new byte[512*1024+17];new Random(17).nextBytes(payload);
        try(ServerSocket echo=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));Fixture pair=new Fixture(echo.getLocalPort())) {
            echo.setSoTimeout(45000);
            Future<?> served=worker.submit(()->{try(Socket accepted=echo.accept()){
                accepted.setSoTimeout(30000);byte[] actual=new byte[payload.length];new DataInputStream(accepted.getInputStream()).readFully(actual);
                if(!Arrays.equals(payload,actual))throw new IOException("Forward payload changed");
                if(accepted.getInputStream().read()!=-1)throw new IOException("Forward FIN missing");
                for(int offset=0;offset<actual.length;){int count=Math.min(997,actual.length-offset);accepted.getOutputStream().write(actual,offset,count);offset+=count;}
                accepted.shutdownOutput();
            }catch(IOException failure){throw new RuntimeException(failure);}});
            pair.connect();
            try(Socket stream=pair.a.openStream()) {
                stream.setSoTimeout(30000);
                for(int offset=0;offset<payload.length;){int count=Math.min(7001,payload.length-offset);stream.getOutputStream().write(payload,offset,count);offset+=count;}
                stream.shutdownOutput();byte[] actual=new byte[payload.length];new DataInputStream(stream.getInputStream()).readFully(actual);
                assertTrue(Arrays.equals(payload,actual));assertEquals("Reverse FIN must preserve final bytes",-1,stream.getInputStream().read());
            }
            served.get(35,TimeUnit.SECONDS);pair.assertHealthy();
        }finally{worker.shutdownNow();}
    }
    public void testStreamCapAndCloseUnblocksBackpressure()throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch accepted=new CountDownLatch(1),release=new CountDownLatch(1);
        List<Socket> sockets=new ArrayList<>();AtomicReference<Socket> blockedConsumer=new AtomicReference<>();
        try(ServerSocket sink=new ServerSocket(0,32,InetAddress.getByName("127.0.0.1"));Fixture pair=new Fixture(sink.getLocalPort())) {
            sink.setSoTimeout(45000);
            workers.submit(()->{try(Socket socket=sink.accept()){blockedConsumer.set(socket);socket.setReceiveBufferSize(4096);accepted.countDown();release.await(30,TimeUnit.SECONDS);}catch(Exception ignored){}});
            pair.connect();Socket first=pair.a.openStream();sockets.add(first);first.setSendBufferSize(4096);
            assertTrue("Remote channel must connect only to configured sink",accepted.await(10,TimeUnit.SECONDS));
            Future<Boolean> writer=workers.submit(()->{try{byte[] chunk=new byte[16*1024];for(int i=0;i<1024;i++)first.getOutputStream().write(chunk);return false;}catch(IOException closed){return true;}});
            for(int i=1;i<TraversalTransport.MAX_STREAMS;i++)sockets.add(pair.a.openStream());
            try{pair.a.openStream();fail("25th stream accepted");}catch(IOException expected){}
            Thread.sleep(500);
            assertFalse("Slow reader should apply bounded backpressure",writer.isDone());
            assertQueueBounds(pair.a);assertQueueBounds(pair.b);
            pair.a.close();assertTrue("Transport close must unblock a blocked local writer",writer.get(5,TimeUnit.SECONDS));
            pair.a.close();assertFalse(pair.a.isConnected());
        }finally{
            release.countDown();Socket consumer=blockedConsumer.get();if(consumer!=null)consumer.close();
            for(Socket socket:sockets)socket.close();workers.shutdownNow();
        }
    }
    public void testFragmentedReceiveWindowAndIdleCloseReleaseWorkers()throws Exception {
        try(ServerSocket sink=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));Fixture pair=new Fixture(sink.getLocalPort())){
            sink.setSoTimeout(10000);pair.connect();
            try(Socket stream=pair.a.openStream();Socket target=sink.accept()){
                java.lang.reflect.Field all=TraversalTransport.class.getDeclaredField("bridges");all.setAccessible(true);
                Map<?,?> bridges=(Map<?,?>)all.get(pair.b);
                long deadline=System.currentTimeMillis()+5000;
                while(bridges.isEmpty()&&System.currentTimeMillis()<deadline)Thread.sleep(10);
                assertFalse(bridges.isEmpty());Object bridge=bridges.values().iterator().next();
                java.lang.reflect.Field gateField=bridge.getClass().getDeclaredField("gate"),queuedField=bridge.getClass().getDeclaredField("queued"),stoppedField=bridge.getClass().getDeclaredField("stopped");
                gateField.setAccessible(true);queuedField.setAccessible(true);stoppedField.setAccessible(true);
                java.lang.reflect.Method close=bridge.getClass().getDeclaredMethod("close");close.setAccessible(true);
                synchronized(gateField.get(bridge)){
                    // Hold the consumer to deterministically reproduce >8 legal tiny frames.
                    org.webrtc.DataChannel.Observer observer=(org.webrtc.DataChannel.Observer)bridge;
                    for(int i=0;i<64;i++)observer.onMessage(new org.webrtc.DataChannel.Buffer(java.nio.ByteBuffer.wrap(new byte[]{1,(byte)i}),true));
                    assertFalse("Byte credit must not become an undocumented 8-frame limit",((AtomicBoolean)stoppedField.get(bridge)).get());
                    assertEquals(64,queuedField.getInt(bridge));
                    close.invoke(bridge);
                }
                java.lang.reflect.Field executor=TraversalTransport.class.getDeclaredField("io");executor.setAccessible(true);
                ThreadPoolExecutor workers=(ThreadPoolExecutor)executor.get(pair.b);
                deadline=System.currentTimeMillis()+5000;
                while(workers.getActiveCount()!=0&&System.currentTimeMillis()<deadline)Thread.sleep(10);
                assertEquals("Closing one idle channel must release its workers without closing transport",0,workers.getActiveCount());
            }
        }
    }
    public void testDelayedManualAnswerDoesNotExpireAt15Seconds()throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket echo=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));Fixture pair=new Fixture(echo.getLocalPort(),45000)) {
            echo.setSoTimeout(100000);
            Future<?> served=worker.submit(()->{try(Socket accepted=echo.accept()){
                accepted.setSoTimeout(15000);int value=accepted.getInputStream().read();accepted.getOutputStream().write(value);
            }catch(IOException failure){throw new RuntimeException(failure);}});
            pair.connect();
            try(Socket stream=pair.a.openStream()){stream.setSoTimeout(15000);stream.getOutputStream().write(73);assertEquals(73,stream.getInputStream().read());}
            served.get(20,TimeUnit.SECONDS);pair.assertHealthy();
        }finally{worker.shutdownNow();}
    }
    private void assertQueueBounds(TraversalTransport transport)throws Exception {
        java.lang.reflect.Field field=TraversalTransport.class.getDeclaredField("bridges");field.setAccessible(true);
        Map<?,?> bridges=(Map<?,?>)field.get(transport);assertTrue(bridges.size()<=24);
        for(Object bridge:bridges.values()){
            java.lang.reflect.Field queued=bridge.getClass().getDeclaredField("queued"),gate=bridge.getClass().getDeclaredField("gate");queued.setAccessible(true);gate.setAccessible(true);
            synchronized(gate.get(bridge)){int value=queued.getInt(bridge);assertTrue("Receive window must stay bounded",value>=0&&value<=96*1024);}
        }
    }
    private final class Fixture implements AutoCloseable {
        final TraversalTransport a,b;
        final CountDownLatch ready=new CountDownLatch(2);
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicReference<PairingCode> offer=new AtomicReference<>();
        final TlsIdentity ka,kb;
        final long answerDelayMs;
        final ScheduledExecutorService signaling=Executors.newSingleThreadScheduledExecutor();
        final Invitation ia,ib;
        Fixture(int port)throws Exception {this(port,0);}
        Fixture(int port,long delayMs)throws Exception {
            answerDelayMs=delayMs;
            ka=identity();kb=identity();ia=new Invitation("127.0.0.1",port,ka.fingerprint,TlsIdentity.randomSecret());ib=new Invitation("127.0.0.1",port,kb.fingerprint,TlsIdentity.randomSecret());
            Context context=getInstrumentation().getTargetContext();Network network=physical();
            a=new TraversalTransport(context,network,port,new Listener(true));
            TraversalTransport second=null;
            try{second=new TraversalTransport(context,network,port,new Listener(false));}catch(Exception error){a.close();throw error;}
            b=second;
        }
        void connect()throws Exception {a.createOffer();assertTrue("ICE/SCTP connection timeout: "+failure.get(),ready.await(answerDelayMs>0?110:60,TimeUnit.SECONDS));assertHealthy();assertTrue(a.isConnected());assertTrue(b.isConnected());}
        void assertHealthy(){if(failure.get()!=null)throw new AssertionError("Traversal fixture failed",failure.get());}
        @Override public void close(){signaling.shutdownNow();a.close();b.close();}
        private final class Listener implements TraversalTransport.Listener {
            final boolean first;Listener(boolean first){this.first=first;}
            public void description(String type,String sdp){try{
                long now=System.currentTimeMillis();
                if(first){PairingCode signed=PairingCode.offer(ka,sdp,ia,now);PairingCode verified=PairingCode.parseAndVerify(signed.encode(),now);offer.set(verified);b.acceptOffer(verified.sdp);}
                else{PairingCode signed=PairingCode.answer(kb,sdp,ib,offer.get(),now);PairingCode verified=PairingCode.parseAndVerify(signed.encode(),now);PairingCode.verifyAnswer(verified,offer.get(),now);
                    if(answerDelayMs==0)a.acceptAnswer(verified.sdp);else signaling.schedule(()->{try{a.acceptAnswer(verified.sdp);}catch(Throwable error){failure.compareAndSet(null,error);ready.countDown();ready.countDown();}},answerDelayMs,TimeUnit.MILLISECONDS);}
            }catch(Throwable error){failure.compareAndSet(null,error);ready.countDown();ready.countDown();}}
            public void state(String state){if(TraversalTransport.CONNECTED.equals(state))ready.countDown();}
            public void failed(String message){failure.compareAndSet(null,new IOException(message));ready.countDown();ready.countDown();}
        }
    }
}
