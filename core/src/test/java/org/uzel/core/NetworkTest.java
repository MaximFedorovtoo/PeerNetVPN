package org.uzel.core;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;

/** Real TLS and loopback sockets: no network transport mocks. */
public class NetworkTest {
    static final List<TlsIdentity> keys = new ArrayList<>();
    final List<AutoCloseable> cleanup = new ArrayList<>();

    @BeforeClass public static void identities() throws Exception {
        Path folder = Files.createTempDirectory("uzel-test-");
        for (int i=0;i<5;i++) {
            Path file = folder.resolve("node"+i+".p12");
            String exe = Path.of(System.getProperty("java.home"),"bin","keytool").toString();
            Process p = new ProcessBuilder(exe, "-genkeypair", "-alias","node","-keyalg","EC",
                    "-groupname","secp256r1","-dname","CN=Test"+i, "-validity","2",
                    "-storetype","PKCS12","-keystore",file.toString(),
                    "-storepass","test-password","-keypass","test-password","-noprompt")
                    .redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes());
            assertEquals(log,0,p.waitFor());
            KeyStore ks=KeyStore.getInstance("PKCS12");
            try (InputStream in=Files.newInputStream(file)) { ks.load(in,"test-password".toCharArray()); }
            keys.add(TlsIdentity.fromKeyStore(ks,"test-password".toCharArray(),"node"));
            Files.delete(file);
        }
        Files.delete(folder);
    }
    @After public void close() throws Exception {
        Collections.reverse(cleanup);
        for (AutoCloseable c:cleanup) c.close();
    }
    Node node(int key) throws Exception {
        // Explicit LAN destination allowance is only for this isolated integration fixture.
        Node node=new Node(keys.get(key),new NetworkAccess(), address -> true);
        node.start(0);
        node.setMeasuredUpload(3_000_000);
        cleanup.add(node);
        return node;
    }
    @Test public void tlsTcpAndUdpActuallyCarryBytesAndStopWithSharing() throws Exception {
        Node server=node(0), client=node(1);
        try (ServerSocket echo=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
             DatagramSocket udp=new DatagramSocket(0,InetAddress.getLoopbackAddress())) {
            ExecutorService pool=Executors.newFixedThreadPool(2);
            cleanup.add(() -> pool.shutdownNow());
            pool.submit(() -> { try(Socket s=echo.accept()){
                byte[] b=s.getInputStream().readNBytes(4);
                s.getOutputStream().write(b); s.getOutputStream().flush();
            } catch(IOException ignored){} });
            pool.submit(() -> { try {
                byte[] b=new byte[200]; DatagramPacket p=new DatagramPacket(b,b.length);
                udp.receive(p); udp.send(p);
            } catch(IOException ignored){} });
            Node.Session session=client.connect(server.invitation("127.0.0.1"), () -> {});
            cleanup.add(session);
            assertEquals(1,server.clientCount());
            try(Socket stream=session.openTcp("127.0.0.1",echo.getLocalPort())) {
                stream.getOutputStream().write(new byte[]{1,3,5,7});
                assertArrayEquals(new byte[]{1,3,5,7},stream.getInputStream().readNBytes(4));
            }
            try(Socket stream=session.openUdp()) {
                DataOutputStream out=new DataOutputStream(stream.getOutputStream());
                byte[] packet=SocksPacket.encode(InetAddress.getLoopbackAddress(),udp.getLocalPort(),new byte[]{9,8,7});
                out.writeInt(packet.length); out.write(packet); out.flush();
                DataInputStream in=new DataInputStream(stream.getInputStream());
                byte[] reply=new byte[in.readInt()]; in.readFully(reply);
                assertArrayEquals(new byte[]{9,8,7},SocksPacket.parse(reply).payload);
            }
            assertTrue(server.sharedBytes.get()>=14);
            client.close();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(server.clientCount()!=0 && System.nanoTime()<until) Thread.sleep(20);
            assertEquals(0,server.clientCount());
            try {session.openTcp("1.1.1.1",80); fail("Sharing off must revoke receiving");}
            catch(IOException expected){}
        }
    }
    @Test public void fourthRealClientIsRejectedAndClosedSessionFreesSlot() throws Exception {
        Node s=node(0);
        List<Node.Session> sessions=new ArrayList<>();
        List<Node> clients=new ArrayList<>();
        for(int i=1;i<=4;i++) clients.add(node(i));
        for(int i=0;i<3;i++) {
            Node.Session c=clients.get(i).connect(s.invitation("127.0.0.1"),()->{});
            sessions.add(c); cleanup.add(c);
        }
        try {clients.get(3).connect(s.invitation("127.0.0.1"),()->{}); fail("fourth device");}
        catch(IOException expected){}
        assertEquals(3,s.clientCount());
        sessions.get(0).close();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(s.clientCount()==3 && System.nanoTime()<until) Thread.sleep(20);
        Node.Session fourth=clients.get(3).connect(s.invitation("127.0.0.1"),()->{});
        cleanup.add(fourth);
        assertEquals(3,s.clientCount());
    }
    @Test public void incorrectPinSecretAndUnavailableSharingAreRejected() throws Exception {
        Node s=node(0), c=node(1);
        Invitation good=s.invitation("127.0.0.1");
        for(Invitation bad:new Invitation[]{
                new Invitation(good.host,good.port,"aa".repeat(32),good.secret),
                new Invitation(good.host,good.port,good.fingerprint,"bb".repeat(32))}) {
            try {c.inspect(bad); fail("Authentication must fail");} catch(IOException expected){}
        }
        c.close();
        try {c.connect(good,()->{}); fail("No local sharing");} catch(IOException expected){}
        assertEquals(0,s.clientCount());
    }
    @Test public void publicExitRefusesLanEvenThroughAuthenticatedClient() throws Exception {
        Node s=new Node(keys.get(0),new NetworkAccess(),PublicAddress::allowed);
        s.start(0); s.setMeasuredUpload(3_000_000); cleanup.add(s);
        Node c=node(1);
        Node.Session session=c.connect(s.invitation("127.0.0.1"),()->{}); cleanup.add(session);
        try {session.openTcp("127.0.0.1",80); fail("Private exit blocked");}
        catch(IOException expected){}
    }
    @Test public void speedProbeMeasuresRealBytesWithoutTrafficCredit() throws Exception {
        Node a=node(0), b=node(1);
        long bytesPerSecond=a.measure(b.invitation("127.0.0.1"));
        assertTrue(bytesPerSecond>0);
        assertEquals(bytesPerSecond,a.measuredUpload());
        assertEquals(0,a.limit());
        assertEquals(0,a.sharedBytes.get());
        assertEquals(0,b.sharedBytes.get());
    }
    @Test public void manualRateSurvivesMeasurementAndUsesRoundedUpMinimum() throws Exception {
        Node a=node(0);
        assertEquals(0,a.limit());
        assertEquals(1_000_000,a.minimumShareRateLimit());
        a.setShareRateLimit(2_000_000);
        a.setMeasuredUpload(4_000_001);
        assertEquals(1_333_334,a.minimumShareRateLimit());
        assertEquals(2_000_000,a.limit());
        try{a.setShareRateLimit(1_333_333);fail("below minimum");}catch(IllegalArgumentException expected){}
        assertEquals(2_000_000,a.limit());
        a.setMeasuredUpload(9_000_001);
        assertEquals(3_000_001,a.limit());
        assertEquals(2_000_000,a.requestedShareRateLimit());
        a.setMeasuredUpload(3_000_000);
        assertEquals(2_000_000,a.limit());
        a.setShareRateLimit(0);a.setMeasuredUpload(9_000_001);
        assertEquals(0,a.limit());
        try{a.setShareRateLimit(-1);fail("negative rate");}catch(IllegalArgumentException expected){}
        assertEquals(0,a.limit());
    }
    @Test public void manualRateCanBeRestoredBeforeMeasurement() throws Exception {
        Node a=node(0);a.setMeasuredUpload(0);
        a.setShareRateLimit(50_000);assertEquals(50_000,a.limit());
        assertEquals(0,a.minimumShareRateLimit());
        try{a.pump(new ByteArrayInputStream(new byte[]{1}),new ByteArrayOutputStream(),true,null);fail("measurement required even with a manual limit");}
        catch(IOException expected){}
        a.setMeasuredUpload(300_001);assertEquals(100_001,a.limit());
        assertEquals(50_000,a.requestedShareRateLimit());
    }
    @Test public void publicRevocationWakesSlowPacedStreams() throws Exception {
        Node a=node(0);a.setMeasuredUpload(3);a.setShareRateLimit(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> output=pool.submit(()->{
                try{a.pump(new ByteArrayInputStream(new byte[2048]),new ByteArrayOutputStream(),true,null);return false;}
                catch(IOException expected){return true;}
            });
            Thread.sleep(50);assertFalse(output.isDone());
            a.stopPublicAdmission();
            assertTrue(output.get(1,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }
    @Test public void fragmentedAndTruncatedSocksDatagramsAreRejected() throws Exception {
        for(byte[] bytes:new byte[][]{new byte[0],new byte[]{0,0,1,1,127,0,0,1,0,53},
                new byte[]{0,0,0,4,1,2}}) {
            try {SocksPacket.parse(bytes); fail("bad packet");} catch(IOException expected){}
        }
    }
    @Test public void revokingMeasurementClosesExistingClientStreams() throws Exception {
        Node s=node(0), c=node(1);
        try(ServerSocket echo=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            ExecutorService pool=Executors.newSingleThreadExecutor();cleanup.add(()->pool.shutdownNow());
            pool.submit(()->{try(Socket socket=echo.accept()){
                int value;while((value=socket.getInputStream().read())!=-1){
                    socket.getOutputStream().write(value);socket.getOutputStream().flush();
                }
            }catch(IOException ignored){}});
            Node.Session session=c.connect(s.invitation("127.0.0.1"),()->{});cleanup.add(session);
            try(Socket flow=session.openTcp("127.0.0.1",echo.getLocalPort())){
                flow.getOutputStream().write(42);assertEquals(42,flow.getInputStream().read());
                s.setMeasuredUpload(0);
                flow.setSoTimeout(2000);
                assertEquals("Revoked speed must close existing flows", -1,flow.getInputStream().read());
            }
        }
    }
}
