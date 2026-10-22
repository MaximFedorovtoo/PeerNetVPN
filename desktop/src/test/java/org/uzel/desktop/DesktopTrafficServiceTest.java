package org.uzel.desktop;

import org.junit.*;
import org.uzel.core.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class DesktopTrafficServiceTest {
    private final List<AutoCloseable> cleanup=new ArrayList<>();
    private static final InetAddress LOOPBACK=InetAddress.getLoopbackAddress();
    private DesktopTrafficService service(DesktopTrafficService.UploadProbe probe)throws Exception {
        DesktopTrafficService service=new DesktopTrafficService(IdentityStore.load(Files.createTempDirectory("desktop-traffic-")),new NetworkAccess(),address->true,probe);
        cleanup.add(service);return service;
    }
    @After public void close()throws Exception {Collections.reverse(cleanup);for(AutoCloseable item:cleanup)item.close();}
    @Test(timeout=15000) public void realTlsTcpUdpAndCountersStopWithDisconnect()throws Exception {
        DesktopTrafficService exit=service(c->3_000_000),client=service(c->3_000_000);
        exit.startSharing(0);client.startSharing(0);client.connect(exit.invitation("127.0.0.1"),()->{});
        DesktopTrafficService.ProxyEndpoint proxy=client.localProxy();assertNotNull(proxy);
        assertFalse(proxy.toString().contains(proxy.password()));
        try(ServerSocket echo=new ServerSocket(0,1,LOOPBACK)){
            ExecutorService worker=Executors.newSingleThreadExecutor();cleanup.add(()->worker.shutdownNow());
            Future<?> task=worker.submit(()->{try(Socket socket=echo.accept()){
                socket.getOutputStream().write(socket.getInputStream().readNBytes(4));socket.getOutputStream().flush();
            }catch(IOException e){throw new UncheckedIOException(e);}});
            try(Socket socket=authenticated(proxy)){
                request(socket,1,echo.getLocalPort());socket.getOutputStream().write(new byte[]{1,2,3,4});
                assertArrayEquals(new byte[]{1,2,3,4},socket.getInputStream().readNBytes(4));
            }task.get(3,TimeUnit.SECONDS);
        }
        try(DatagramSocket echo=new DatagramSocket(0,LOOPBACK);DatagramSocket sender=new DatagramSocket(0,LOOPBACK);Socket control=authenticated(proxy)){
            echo.setSoTimeout(3000);sender.setSoTimeout(3000);int port=request(control,3,sender.getLocalPort());
            byte[] packet=SocksPacket.encode(LOOPBACK,echo.getLocalPort(),new byte[]{8,9,10});
            sender.send(new DatagramPacket(packet,packet.length,LOOPBACK,port));
            DatagramPacket received=new DatagramPacket(new byte[100],100);echo.receive(received);echo.send(received);
            sender.receive(received);assertArrayEquals(new byte[]{8,9,10},SocksPacket.parse(Arrays.copyOf(received.getData(),received.getLength())).payload);
            await(()->client.snapshot().receivedBytes()==7 && exit.snapshot().sharedBytes()>=7);
            client.disconnect();assertNull(client.localProxy());assertFalse(client.snapshot().connected());
            assertEquals(-1,control.getInputStream().read());
        }
        assertTrue(client.snapshot().sharing());client.stopSharing();assertFalse(client.snapshot().sharing());
        assertEquals(7,client.snapshot().receivedBytes());
        assertThrows(IOException.class,()->new Socket(proxy.host(),proxy.port()));
    }
    @Test(timeout=15000) public void failedOrZeroProbeCannotEnableSharingAndRateFloorTracksRealMeasurement()throws Exception {
        DesktopTrafficService broken=service(c->{throw new IOException("offline");});
        assertThrows(IOException.class,()->broken.startSharing(0));assertFalse(broken.snapshot().sharing());
        DesktopTrafficService zero=service(c->0);assertThrows(IOException.class,()->zero.startSharing(0));assertFalse(zero.snapshot().sharing());
        AtomicLong speed=new AtomicLong(10);DesktopTrafficService good=service(c->speed.get());
        good.startSharing(0);assertEquals(10,good.snapshot().measuredUpload());assertEquals(0,good.snapshot().shareRateLimit());
        assertThrows(IllegalArgumentException.class,()->good.setShareRatePreference(new SharingRatePreference(3)));
        good.setShareRatePreference(new SharingRatePreference(4));assertEquals(4,good.snapshot().shareRateLimit());
        speed.set(31);good.remeasure();assertEquals(11,good.snapshot().shareRateLimit());
        good.setShareRatePreference(SharingRatePreference.unlimited());assertEquals(0,good.snapshot().shareRateLimit());
        speed.set(0);assertThrows(IOException.class,good::remeasure);assertFalse(good.snapshot().sharing());
    }
    @Test(timeout=10000) public void stopDuringProbePreventsLateSharingPublication()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        DesktopTrafficService service=service(c->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new InterruptedIOException();}return 100;});
        ExecutorService worker=Executors.newSingleThreadExecutor();cleanup.add(()->worker.shutdownNow());
        Future<?> pending=worker.submit(()->{try{service.startSharing(0);fail("Stopped measurement published");}catch(IOException expected){}});
        assertTrue(entered.await(2,TimeUnit.SECONDS));service.stopSharing();release.countDown();pending.get(2,TimeUnit.SECONDS);
        assertFalse(service.snapshot().sharing());assertNull(service.localProxy());
    }
    @Test(timeout=20000) public void publicRoutesRequireReciprocityEnforceThreeClientsAndStopRevokesBridge()throws Exception {
        DesktopTrafficService exit=service(c->3_000_000);exit.startSharing(0);
        List<DesktopTrafficService> clients=new ArrayList<>();
        for(int i=0;i<4;i++){
            DesktopTrafficService client=service(c->3_000_000);client.startSharing(0);
            client.publishRoute(client.publicRoute("127.0.0.1"));clients.add(client);
        }
        for(int i=0;i<3;i++)clients.get(i).connect(exit.publicRoute("127.0.0.1"),()->{});
        assertEquals(3,exit.snapshot().clients());
        assertThrows(IOException.class,()->clients.get(3).connect(exit.publicRoute("127.0.0.1"),()->{}));
        assertNull(clients.get(3).localProxy());
        DesktopTrafficService first=clients.get(0);DesktopTrafficService.ProxyEndpoint old=first.localProxy();
        try(Socket control=authenticated(old);DatagramSocket udp=new DatagramSocket(0,LOOPBACK)){
            request(control,3,udp.getLocalPort());first.stopSharing();assertEquals(-1,control.getInputStream().read());
        }
        assertFalse(first.snapshot().sharing());assertNull(first.localProxy());
    }
    @Test(timeout=10000) public void disconnectDuringPendingConnectCannotPublishLateProxy()throws Exception {
        DesktopTrafficService exit=service(c->3_000_000);exit.startSharing(0);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        NetworkAccess blocked=new NetworkAccess(){@Override public Socket open(String host,int port)throws IOException {
            entered.countDown();try{release.await();}catch(InterruptedException e){throw new InterruptedIOException();}
            return super.open(host,port);
        }};
        DesktopTrafficService client=new DesktopTrafficService(IdentityStore.load(Files.createTempDirectory("desktop-late-")),blocked,a->true,c->3_000_000);cleanup.add(client);client.startSharing(0);
        ExecutorService worker=Executors.newSingleThreadExecutor();cleanup.add(()->worker.shutdownNow());
        Future<?> pending=worker.submit(()->{try{client.connect(exit.invitation("127.0.0.1"),()->{});fail("Late proxy published");}catch(IOException expected){}});
        assertTrue(entered.await(2,TimeUnit.SECONDS));client.disconnect();release.countDown();pending.get(5,TimeUnit.SECONDS);
        assertNull(client.localProxy());assertFalse(client.snapshot().connected());assertTrue(client.snapshot().sharing());
    }
    @Test(timeout=10000) public void lateCompletionsOfRetiredNodeRemainCountedAcrossStopAndRestart()throws Exception {
        DesktopTrafficService service=service(c->3_000_000);service.startSharing(0);Node retired=service.node();
        retired.sharedBytes.addAndGet(3);retired.receivedBytes.addAndGet(5);service.stopSharing();
        // A write that passed the forwarding check can finish counting after logical revocation.
        retired.sharedBytes.addAndGet(7);retired.receivedBytes.addAndGet(11);
        assertEquals(10,service.snapshot().sharedBytes());assertEquals(16,service.snapshot().receivedBytes());
        service.startSharing(0);Node current=service.node();current.sharedBytes.addAndGet(13);current.receivedBytes.addAndGet(17);
        retired.sharedBytes.addAndGet(19);retired.receivedBytes.addAndGet(23);
        assertEquals(42,service.snapshot().sharedBytes());assertEquals(56,service.snapshot().receivedBytes());
        service.stopSharing();assertEquals(42,service.snapshot().sharedBytes());assertEquals(56,service.snapshot().receivedBytes());
    }
    private static Socket authenticated(DesktopTrafficService.ProxyEndpoint proxy)throws Exception {
        Socket socket=new Socket(proxy.host(),proxy.port());socket.setSoTimeout(3000);
        DataOutputStream out=new DataOutputStream(socket.getOutputStream());DataInputStream in=new DataInputStream(socket.getInputStream());
        out.write(new byte[]{5,1,2});out.flush();assertArrayEquals(new byte[]{5,2},in.readNBytes(2));
        byte[] user=proxy.username().getBytes(StandardCharsets.US_ASCII),password=proxy.password().getBytes(StandardCharsets.US_ASCII);
        out.writeByte(1);out.writeByte(user.length);out.write(user);out.writeByte(password.length);out.write(password);out.flush();
        assertArrayEquals(new byte[]{1,0},in.readNBytes(2));return socket;
    }
    private static int request(Socket socket,int command,int port)throws Exception {
        DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.write(new byte[]{5,(byte)command,0,1,127,0,0,1});out.writeShort(port);out.flush();
        DataInputStream in=new DataInputStream(socket.getInputStream());assertArrayEquals(new byte[]{5,0,0,1,127,0,0,1},in.readNBytes(8));return in.readUnsignedShort();
    }
    private static void await(java.util.function.BooleanSupplier ready)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!ready.getAsBoolean() && System.nanoTime()<until)Thread.sleep(10);assertTrue(ready.getAsBoolean());
    }
}
