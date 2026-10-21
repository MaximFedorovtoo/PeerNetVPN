package org.uzel.core;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;

public class LocalSocksTest {
    private static final List<TlsIdentity> keys=new ArrayList<>();
    private final List<AutoCloseable> cleanup=new ArrayList<>();
    private Node client;
    private Node.Session session;
    private LocalSocks socks;
    private static final InetAddress LOOPBACK=InetAddress.getLoopbackAddress();

    @BeforeClass public static void identities() throws Exception {
        Path folder=Files.createTempDirectory("uzel-socks-");
        try{for(int i=0;i<2;i++){
            Path file=folder.resolve(i+".p12");
            Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                    "-genkeypair","-alias","node","-keyalg","EC","-groupname","secp256r1","-dname","CN=Socks"+i,
                    "-validity","2","-storetype","PKCS12","-keystore",file.toString(),"-storepass","test-password",
                    "-keypass","test-password","-noprompt").redirectErrorStream(true).start();
            String log=new String(process.getInputStream().readAllBytes());assertEquals(log,0,process.waitFor());
            KeyStore store=KeyStore.getInstance("PKCS12");
            try(InputStream in=Files.newInputStream(file)){store.load(in,"test-password".toCharArray());}
            keys.add(TlsIdentity.fromKeyStore(store,"test-password".toCharArray(),"node"));Files.delete(file);
        }}finally{Files.delete(folder);}
    }
    @Before public void start() throws Exception {
        Node server=node(0);client=node(1);
        session=client.connect(server.invitation("127.0.0.1"),()->{});cleanup.add(session);
        socks=new LocalSocks(client,session);cleanup.add(socks);socks.start();
    }
    private Node node(int i) throws Exception {
        Node node=new Node(keys.get(i),new NetworkAccess(),address->true);
        node.start(0);node.setMeasuredUpload(3_000_000);cleanup.add(node);return node;
    }
    @After public void close() throws Exception {Collections.reverse(cleanup);for(AutoCloseable item:cleanup)item.close();}
    private Socket socket() throws Exception {
        Socket socket=new Socket("127.0.0.1",socks.port());socket.setSoTimeout(3000);return socket;
    }
    private Socket authenticated() throws Exception {
        Socket socket=socket();DataOutputStream out=new DataOutputStream(socket.getOutputStream());
        DataInputStream in=new DataInputStream(socket.getInputStream());out.write(new byte[]{5,1,2});out.flush();
        assertEquals(5,in.readUnsignedByte());assertEquals(2,in.readUnsignedByte());
        auth(out,socks.username(),socks.password());assertEquals(1,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());return socket;
    }
    private static void auth(DataOutputStream out,String username,String password) throws Exception {
        byte[] u=username.getBytes(StandardCharsets.US_ASCII),p=password.getBytes(StandardCharsets.US_ASCII);
        out.writeByte(1);out.writeByte(u.length);out.write(u);out.writeByte(p.length);out.write(p);out.flush();
    }
    private static int request(Socket socket,int command,int port) throws Exception {
        DataOutputStream out=new DataOutputStream(socket.getOutputStream());
        out.write(new byte[]{5,(byte)command,0,1,127,0,0,1});out.writeShort(port);out.flush();
        DataInputStream in=new DataInputStream(socket.getInputStream());assertEquals(5,in.readUnsignedByte());
        assertEquals(0,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());assertEquals(1,in.readUnsignedByte());
        assertArrayEquals(new byte[]{127,0,0,1},in.readNBytes(4));return in.readUnsignedShort();
    }
    @Test(timeout=10000) public void requiresRandomCredentialsAndRejectsWrongPassword() throws Exception {
        LocalSocks other=new LocalSocks(client,session);cleanup.add(other);
        assertNotEquals(socks.username(),other.username());assertNotEquals(socks.password(),other.password());
        try(Socket socket=socket()){
            socket.getOutputStream().write(new byte[]{5,1,0});
            assertArrayEquals(new byte[]{5,(byte)255},socket.getInputStream().readNBytes(2));
            assertEquals(-1,socket.getInputStream().read());
        }
        try(Socket socket=socket()){
            socket.getOutputStream().write(new byte[]{5,1,2});assertArrayEquals(new byte[]{5,2},socket.getInputStream().readNBytes(2));
            auth(new DataOutputStream(socket.getOutputStream()),socks.username(),"wrong");
            assertArrayEquals(new byte[]{1,1},socket.getInputStream().readNBytes(2));assertEquals(-1,socket.getInputStream().read());
        }
    }
    @Test(timeout=15000) public void tcpCarriesAndCountsActualBytesAndCloseStopsActiveFlow() throws Exception {
        try(ServerSocket echo=new ServerSocket(0,1,LOOPBACK)){
            ExecutorService worker=Executors.newSingleThreadExecutor();cleanup.add(()->worker.shutdownNow());
            Future<?> task=worker.submit(()->{try(Socket socket=echo.accept()){
                socket.getOutputStream().write(socket.getInputStream().readNBytes(4));socket.getOutputStream().flush();
                assertEquals(-1,socket.getInputStream().read());
            }catch(IOException e){throw new UncheckedIOException(e);}});
            try(Socket socket=authenticated()){
                request(socket,1,echo.getLocalPort());socket.getOutputStream().write(new byte[]{1,2,3,4});
                assertArrayEquals(new byte[]{1,2,3,4},socket.getInputStream().readNBytes(4));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while(client.receivedBytes.get()<4 && System.nanoTime()<until)Thread.sleep(5);
                assertEquals(4,client.receivedBytes.get());socks.close();assertEquals(-1,socket.getInputStream().read());
            }
            task.get(3,TimeUnit.SECONDS);
        }
    }
    @Test(timeout=15000) public void udpAuthenticatesAssociationPinsSourceRejectsFragmentsAndEndsWithControl() throws Exception {
        try(DatagramSocket echo=new DatagramSocket(0,LOOPBACK);DatagramSocket sender=new DatagramSocket(0,LOOPBACK);
            DatagramSocket attacker=new DatagramSocket(0,LOOPBACK);Socket control=authenticated()){
            echo.setSoTimeout(3000);sender.setSoTimeout(3000);attacker.setSoTimeout(250);
            int relay=request(control,3,sender.getLocalPort());
            byte[] packet=SocksPacket.encode(LOOPBACK,echo.getLocalPort(),new byte[]{8,9,10});
            attacker.send(new DatagramPacket(packet,packet.length,LOOPBACK,relay));
            byte[] fragment=packet.clone();fragment[2]=1;sender.send(new DatagramPacket(fragment,fragment.length,LOOPBACK,relay));
            sender.send(new DatagramPacket(packet,packet.length,LOOPBACK,relay));
            byte[] b=new byte[100];DatagramPacket received=new DatagramPacket(b,b.length);echo.receive(received);
            assertArrayEquals(new byte[]{8,9,10},Arrays.copyOf(b,received.getLength()));echo.send(received);
            DatagramPacket response=new DatagramPacket(b,b.length);sender.receive(response);
            assertArrayEquals(new byte[]{8,9,10},SocksPacket.parse(Arrays.copyOf(b,response.getLength())).payload);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(client.receivedBytes.get()<3 && System.nanoTime()<until)Thread.sleep(5);
            assertEquals(3,client.receivedBytes.get());
            try{attacker.receive(new DatagramPacket(new byte[100],100));fail("Wrong source received reply");}catch(SocketTimeoutException expected){}
            control.close();Thread.sleep(150);echo.setSoTimeout(350);
            sender.send(new DatagramPacket(packet,packet.length,LOOPBACK,relay));
            try{echo.receive(new DatagramPacket(new byte[100],100));fail("Closed association forwarded packet");}catch(SocketTimeoutException expected){}
        }
    }
    @Test(timeout=10000) public void wildcardAssociationPinsFirstValidSourceAndCloseInterruptsControl() throws Exception {
        try(DatagramSocket echo=new DatagramSocket(0,LOOPBACK);DatagramSocket first=new DatagramSocket(0,LOOPBACK);
            DatagramSocket other=new DatagramSocket(0,LOOPBACK);Socket control=authenticated()){
            echo.setSoTimeout(3000);int relay=request(control,3,0);
            byte[] packet=SocksPacket.encode(LOOPBACK,echo.getLocalPort(),new byte[]{42});
            other.send(new DatagramPacket(new byte[]{0,0,1},3,LOOPBACK,relay));
            first.send(new DatagramPacket(packet,packet.length,LOOPBACK,relay));
            DatagramPacket received=new DatagramPacket(new byte[100],100);echo.receive(received);
            assertEquals(42,received.getData()[0]);
            other.send(new DatagramPacket(packet,packet.length,LOOPBACK,relay));echo.setSoTimeout(250);
            try{echo.receive(received);fail("Second sender forwarded");}catch(SocketTimeoutException expected){}
            socks.close();assertEquals(-1,control.getInputStream().read());
            first.send(new DatagramPacket(packet,packet.length,LOOPBACK,relay));
            try{echo.receive(received);fail("Stopped server forwarded");}catch(SocketTimeoutException expected){}
        }
    }
    @Test(timeout=10000) public void revokedSessionCannotStartBridge() throws Exception {
        session.close();LocalSocks other=new LocalSocks(client,session);cleanup.add(other);
        try{other.start();fail("Dead session");}catch(IOException expected){}
    }
}
