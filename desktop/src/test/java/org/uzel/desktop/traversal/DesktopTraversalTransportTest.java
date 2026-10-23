package org.uzel.desktop.traversal;

import org.junit.*;
import org.uzel.desktop.IdentityStore;
import org.uzel.core.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Actual native ICE/SCTP + signed SDP + pinned core TLS. No elevated helper is used. */
public class DesktopTraversalTransportTest {
    private static Class<?> transportType()throws Exception {
        try{return Class.forName("org.uzel.desktop.traversal.DesktopTraversalTransport");}
        catch(ClassNotFoundException absent){fail("Desktop native traversal transport is missing");return null;}
    }
    @Test(timeout=120000) public void signedNativePeersCarryTlsStreamsAndReconnectAfterClose()throws Exception {
        Assume.assumeTrue(System.getProperty("os.name","").startsWith("Windows"));transportType();
        TlsIdentity ka=IdentityStore.load(Files.createTempDirectory("desktop-ice-a-"));
        TlsIdentity kb=IdentityStore.load(Files.createTempDirectory("desktop-ice-b-"));
        try(Node a=new Node(ka,new NetworkAccess(),address->true);Node b=new Node(kb,new NetworkAccess(),address->true);
            ServerSocket echo=new ServerSocket(0,4,InetAddress.getLoopbackAddress())){
            a.start(0);b.start(0);a.setPublicMode(true);b.setPublicMode(true);
            a.setMeasuredUpload(3_000_000);b.setMeasuredUpload(3_000_000);
            ExecutorService io=Executors.newSingleThreadExecutor();
            byte[] expected=new byte[384*1024];new Random(7).nextBytes(expected);
            Future<?> echoTask=io.submit(()->{try(Socket socket=echo.accept()){
                byte[] received=socket.getInputStream().readNBytes(expected.length);
                socket.getOutputStream().write(received);socket.getOutputStream().flush();
            }catch(IOException error){throw new UncheckedIOException(error);}});
            try{
                try(Link link=new Link(a,b,ka,kb)){
                    link.connect();
                    try(Node.Session session=a.connect(link.ib,()->{});
                        Socket stream=session.openTcp("127.0.0.1",echo.getLocalPort())){
                        stream.setSoTimeout(20000);stream.getOutputStream().write(expected);stream.getOutputStream().flush();
                        assertArrayEquals(expected,stream.getInputStream().readNBytes(expected.length));
                    }
                    echoTask.get(10,TimeUnit.SECONDS);
                    long before=System.nanoTime();link.close();
                    assertTrue("Traversal cleanup exceeded deadline",System.nanoTime()-before<TimeUnit.SECONDS.toNanos(8));
                    assertThrows(IOException.class,link::openA);
                }
                try(Link replacement=new Link(a,b,ka,kb)){
                    replacement.connect();
                    try(Node.Session session=a.connect(replacement.ib,()->{})){assertTrue(session.isLive());}
                }
            }finally{io.shutdownNow();}
        }
    }
    private static final class Link implements AutoCloseable {
        final Class<?> type;final Object a,b;final Invitation ia,ib;final TlsIdentity ka,kb;
        final CountDownLatch connected=new CountDownLatch(2);
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicReference<PairingCode> offer=new AtomicReference<>();
        final Node.TransportRegistration ra,rb;
        Link(Node na,Node nb,TlsIdentity ka,TlsIdentity kb)throws Exception {
            this.ka=ka;this.kb=kb;type=transportType();
            ia=new Invitation("127.0.0.1",na.port(),ka.fingerprint,TlsIdentity.randomSecret());
            ib=new Invitation("127.0.0.1",nb.port(),kb.fingerprint,TlsIdentity.randomSecret());
            Class<?> observer=Class.forName(type.getName()+"$Listener");
            a=type.getConstructor(int.class,observer).newInstance(na.port(),listener(observer,true));
            b=type.getConstructor(int.class,observer).newInstance(nb.port(),listener(observer,false));
            ra=na.registerPeerTransport(kb.fingerprint,this::openA,ia.secret);
            rb=nb.registerPeerTransport(ka.fingerprint,()->open(b),ib.secret);
        }
        private Object listener(Class<?> observer,boolean first){
            return java.lang.reflect.Proxy.newProxyInstance(observer.getClassLoader(),new Class<?>[]{observer},(proxy,method,args)->{
                try{
                    if(method.getName().equals("description")){
                        long now=System.currentTimeMillis();
                        if(first){
                            PairingCode signed=PairingCode.offer(ka,(String)args[1],ia,now);
                            PairingCode verified=PairingCode.parseAndVerify(signed.encode(),now);offer.set(verified);
                            type.getMethod("acceptOffer",String.class).invoke(b,verified.sdp);
                        }else{
                            PairingCode signed=PairingCode.answer(kb,(String)args[1],ib,offer.get(),now);
                            PairingCode verified=PairingCode.parseAndVerify(signed.encode(),now);
                            PairingCode.verifyAnswer(verified,offer.get(),now);
                            type.getMethod("acceptAnswer",String.class).invoke(a,verified.sdp);
                        }
                    }else if(method.getName().equals("state") && "CONNECTED".equals(args[0]))connected.countDown();
                    else if(method.getName().equals("failed"))failure.compareAndSet(null,new IOException((String)args[0]));
                }catch(Throwable error){failure.compareAndSet(null,error);connected.countDown();connected.countDown();}
                return null;
            });
        }
        void connect()throws Exception {
            type.getMethod("createOffer").invoke(a);
            assertTrue("Native pair timed out: "+failure.get(),connected.await(45,TimeUnit.SECONDS));
            if(failure.get()!=null)throw new AssertionError("Native pair failed",failure.get());
            assertEquals(true,type.getMethod("isConnected").invoke(a));assertEquals(true,type.getMethod("isConnected").invoke(b));
        }
        Socket openA()throws IOException{return open(a);}
        Socket open(Object transport)throws IOException{
            try{return (Socket)type.getMethod("openStream").invoke(transport);}
            catch(java.lang.reflect.InvocationTargetException error){if(error.getCause() instanceof IOException io)throw io;throw new IOException(error.getCause());}
            catch(Exception error){throw new IOException(error);}
        }
        public void close()throws Exception{ra.close();rb.close();((AutoCloseable)a).close();((AutoCloseable)b).close();}
    }
}
