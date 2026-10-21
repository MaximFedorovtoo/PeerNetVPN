package org.uzel.core;

import org.junit.*;
import static org.junit.Assert.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class RelayTest extends NetworkTest {
    @Test public void relayEofDoesNotExplicitlyCloseSocketOwnedByTlsCaller()throws Exception{
        Node relay=node(0),client=node(1),target=node(2);for(Node n:new Node[]{relay,client,target})n.setPublicMode(true);
        Node.RelayLease registration=target.registerRelay(relay.publicRoute("127.0.0.1"));cleanup.add(registration);
        Socket plain=client.relaySupport.openTunnel(registration.route());cleanup.add(plain);
        javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)client.identity.context(registration.route().fingerprint)
                .getSocketFactory().createSocket(plain,"127.0.0.1",target.port(),true);cleanup.add(tls);
        tls.startHandshake();DataOutputStream out=new DataOutputStream(tls.getOutputStream());
        out.writeInt(Node.MAGIC);out.writeUTF("");out.writeByte(Node.INFO);out.flush();
        // INFO is one response then EOF. Android Conscrypt checks Java Socket's
        // closed flag before reading even if response bytes already arrived.
        Thread.sleep(300);
        assertFalse("Returned socket belongs to TLS caller, not bridge worker",plain.isClosed());
        DataInputStream in=new DataInputStream(tls.getInputStream());assertEquals(0,in.readInt());assertTrue(in.readBoolean());
    }
    @Test public void publicAuthenticationCannotEnterPrivateJoinAndOffClosesPreauthenticatedSockets()throws Exception{
        Node server=node(0),client=node(1);server.setPublicMode(true);client.setPublicMode(true);
        PeerRoute route=server.publicRoute("127.0.0.1");
        try(Socket bad=client.open(route,Node.JOIN)){
            DataOutputStream out=new DataOutputStream(bad.getOutputStream());
            out.writeUTF(client.invitation("127.0.0.1").encode());out.flush();
            try{Node.checkStatus(new DataInputStream(bad.getInputStream()));fail("Public token cannot authorize private JOIN");}catch(IOException expected){}
        }
        List<Socket> pending=new ArrayList<>();
        for(int i=0;i<2;i++){
            javax.net.ssl.SSLSocket socket=(javax.net.ssl.SSLSocket)client.identity.context(route.fingerprint).getSocketFactory().createSocket("127.0.0.1",route.port);
            cleanup.add(socket);pending.add(socket);socket.setSoTimeout(3000);socket.startHandshake();
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeInt(Node.MAGIC);out.writeUTF("");out.flush();
        }
        java.lang.reflect.Field field=Node.class.getDeclaredField("publicSockets");field.setAccessible(true);
        long until=System.currentTimeMillis()+3000;
        while(((Set<?>)field.get(server)).size()<2 && System.currentTimeMillis()<until)Thread.sleep(10);
        assertEquals(2,((Set<?>)field.get(server)).size());
        server.setPublicMode(false);
        for(Socket socket:pending){try{assertEquals(-1,socket.getInputStream().read());}catch(SocketException expected){}}
        assertEquals(0,server.clientCount());
    }
    @Test public void publicModeIsExplicitAndGossipNeverContainsInvitationSecrets() throws Exception {
        Node a=node(0), b=node(1);
        b.setPublicMode(true);
        PeerRoute route=a.publicRoute("127.0.0.1");
        try { b.inspect(route); fail("private by default"); } catch(IOException expected){}
        a.setPublicMode(true);b.setPublicMode(true);
        a.setAdvertisedRoute(route);
        PeerDirectory.Record record=a.publish(route);
        assertEquals(route.fingerprint,record.fingerprint);
        List<PeerDirectory.Record> records=b.exchangeDirectory(route);
        assertEquals(1,records.size());
        assertFalse(record.encode().contains(a.invitation("127.0.0.1").secret));
        assertEquals(route.encode(),records.get(0).route.encode());
    }
    private Socket command(Node client,PeerRoute relay,int operation,String value)throws Exception{
        Socket s=client.open(relay,operation);cleanup.add(s);
        if(value!=null){DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeUTF(value);out.flush();}
        return s;
    }
    private void success(Socket s)throws Exception{Node.checkStatus(new DataInputStream(s.getInputStream()));}
    private String ticket(Socket registration)throws Exception{
        DataOutputStream out=new DataOutputStream(registration.getOutputStream());out.writeInt(Node.MAGIC);out.flush();
        return new DataInputStream(registration.getInputStream()).readUTF();
    }
    @Test public void relayTicketsBindTargetAndRejectReplayAndOldRegistration()throws Exception{
        Node r=node(0),target=node(1),client=node(2),attacker=node(3);
        for(Node n:new Node[]{r,target,client,attacker})n.setPublicMode(true);
        PeerRoute route=r.publicRoute("127.0.0.1");
        Socket registration=command(target,route,ParticipantRelay.REGISTER,null);success(registration);
        Socket request=command(client,route,ParticipantRelay.CONNECT,target.publicRoute("127.0.0.1").fingerprint);
        String ticket=ticket(registration);assertEquals(64,ticket.length());
        Socket wrong=command(attacker,route,ParticipantRelay.ATTACH,ticket);
        assertNotEquals(0,new DataInputStream(wrong.getInputStream()).readInt());
        Socket attached=command(target,route,ParticipantRelay.ATTACH,ticket);success(attached);success(request);
        Socket replay=command(target,route,ParticipantRelay.ATTACH,ticket);
        assertNotEquals(0,new DataInputStream(replay.getInputStream()).readInt());
        request.getOutputStream().write(42);request.getOutputStream().flush();assertEquals(42,attached.getInputStream().read());
        registration.close();request.setSoTimeout(3000);
        try{assertEquals(-1,request.getInputStream().read());}catch(SocketException expected){}
        long until=System.currentTimeMillis()+3000;while(r.clientCount()!=0 && System.currentTimeMillis()<until)Thread.sleep(20);
        Socket replacement=command(target,route,ParticipantRelay.REGISTER,null);success(replacement);
        Socket stale=command(target,route,ParticipantRelay.ATTACH,ticket);
        assertNotEquals(0,new DataInputStream(stale.getInputStream()).readInt());
    }
    @Test public void mixedExitAndRelayRolesShareCapacityAndIndependentLifetimes()throws Exception{
        Node r=node(0),a=node(1),b=node(2),c=node(3),d=node(4);
        for(Node n:new Node[]{r,a,b,c,d})n.setPublicMode(true);
        PeerRoute route=r.publicRoute("127.0.0.1");
        Node.RelayLease registration=a.registerRelay(route);cleanup.add(registration);
        Node.Session same=a.connect(r.invitation("127.0.0.1"),()->{});cleanup.add(same);
        Node.Session second=b.connect(r.invitation("127.0.0.1"),()->{});cleanup.add(second);
        Node.RelayLease third=c.registerRelay(route);cleanup.add(third);
        assertEquals(3,r.clientCount());
        same.close();Thread.sleep(100);assertEquals(3,r.clientCount());
        try{d.registerRelay(route);fail("fourth mixed identity");}catch(IOException expected){}
        registration.close();long until=System.currentTimeMillis()+3000;
        while(r.clientCount()==3 && System.currentTimeMillis()<until)Thread.sleep(20);
        cleanup.add(d.registerRelay(route));assertEquals(3,r.clientCount());
    }
    @Test public void relayAndExitUseSameAggregatePacer()throws Exception{
        Node r=node(0),target=node(1),client=node(2);for(Node n:new Node[]{r,target,client})n.setPublicMode(true);
        PeerRoute route=r.publicRoute("127.0.0.1");
        Socket registration=command(target,route,ParticipantRelay.REGISTER,null);success(registration);
        Socket request=command(client,route,ParticipantRelay.CONNECT,target.publicRoute("127.0.0.1").fingerprint);
        String ticket=ticket(registration);Socket attached=command(target,route,ParticipantRelay.ATTACH,ticket);success(attached);success(request);
        r.setMeasuredUpload(60_000); // 20 kB/s shared across both directions and ordinary exit pumping.
        byte[] payload=new byte[4000];long start=System.nanoTime();
        ExecutorService pool=Executors.newFixedThreadPool(3);cleanup.add(()->pool.shutdownNow());
        Future<?> first=pool.submit(()->{try{request.getOutputStream().write(payload);request.getOutputStream().flush();assertEquals(payload.length,attached.getInputStream().readNBytes(payload.length).length);}catch(IOException e){throw new RuntimeException(e);}});
        Future<?> second=pool.submit(()->{try{attached.getOutputStream().write(payload);attached.getOutputStream().flush();assertEquals(payload.length,request.getInputStream().readNBytes(payload.length).length);}catch(IOException e){throw new RuntimeException(e);}});
        Future<?> exit=pool.submit(()->{try{r.pump(new ByteArrayInputStream(payload),new ByteArrayOutputStream(),true,null);}catch(IOException e){throw new RuntimeException(e);}});
        first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);exit.get(5,TimeUnit.SECONDS);
        assertTrue("one 20 kB/s budget for all 12 kB",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)>=550);
    }
    @Test public void twoRegisteredPeersCarryEndToEndTlsAndLeaseLossClosesStreams() throws Exception {
        Node relay=node(0), a=node(1), b=node(2);
        relay.setPublicMode(true);a.setPublicMode(true);b.setPublicMode(true);
        PeerRoute r=relay.publicRoute("127.0.0.1");relay.setAdvertisedRoute(r);
        Node.RelayLease ra=a.registerRelay(r), rb=b.registerRelay(r);cleanup.add(ra);cleanup.add(rb);
        a.setAdvertisedRoute(ra.route());b.setAdvertisedRoute(rb.route());
        try(ServerSocket echo=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService e=Executors.newSingleThreadExecutor();cleanup.add(()->e.shutdownNow());
            e.submit(()->{try(Socket s=echo.accept()){s.getOutputStream().write(s.getInputStream().readNBytes(3));Thread.sleep(10000);}catch(Exception ignored){}});
            Node.Session session=a.connect(rb.route(),()->{});cleanup.add(session);
            Socket stream=session.openTcp("127.0.0.1",echo.getLocalPort());cleanup.add(stream);
            stream.getOutputStream().write(new byte[]{3,2,1});
            assertArrayEquals(new byte[]{3,2,1},stream.getInputStream().readNBytes(3));
            assertEquals(2,relay.clientCount());assertEquals(1,b.clientCount());
            rb.close();stream.setSoTimeout(5000);
            try{assertEquals(-1,stream.getInputStream().read());}catch(SocketException expected){}
        }
    }
    @Test public void relayRegistrationsShareThreeDeviceCapacity() throws Exception {
        Node relay=node(0);relay.setPublicMode(true);PeerRoute r=relay.publicRoute("127.0.0.1");
        for(int i=1;i<=3;i++){Node n=node(i);n.setPublicMode(true);cleanup.add(n.registerRelay(r));}
        Node fourth=node(4);fourth.setPublicMode(true);
        try{fourth.registerRelay(r);fail("fourth identity");}catch(IOException expected){}
        assertEquals(3,relay.clientCount());
    }
    @Test public void tunneledTcpHalfCloseStillReceivesResponse()throws Exception{
        Node relay=node(0),a=node(1),b=node(2);for(Node n:new Node[]{relay,a,b})n.setPublicMode(true);
        PeerRoute route=relay.publicRoute("127.0.0.1");
        Node.RelayLease ra=a.registerRelay(route),rb=b.registerRelay(route);cleanup.add(ra);cleanup.add(rb);
        a.setAdvertisedRoute(ra.route());b.setAdvertisedRoute(rb.route());
        try(ServerSocket echo=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            ExecutorService executor=Executors.newSingleThreadExecutor();cleanup.add(()->executor.shutdownNow());
            executor.submit(()->{try(Socket s=echo.accept()){
                byte[] request=s.getInputStream().readAllBytes();s.getOutputStream().write(request);s.getOutputStream().flush();
            }catch(IOException ignored){}});
            Node.Session session=a.connect(rb.route(),()->{});cleanup.add(session);
            try(Socket stream=session.openTcp("127.0.0.1",echo.getLocalPort())){
                stream.getOutputStream().write(new byte[]{8,9,10});stream.shutdownOutput();
                assertArrayEquals(new byte[]{8,9,10},stream.getInputStream().readNBytes(3));
            }
        }
    }
    @Test public void directoryRejectsTamperingAndExpiredRecords() throws Exception {
        Node a=node(0);a.setPublicMode(true);
        PeerDirectory.Record r=a.publish(a.publicRoute("127.0.0.1"));
        PeerDirectory d=new PeerDirectory();
        assertTrue(d.accept(r,System.currentTimeMillis()));
        assertFalse(d.accept(r,r.expiresAt+1));
        assertFalse(d.accept(r,r.issuedAt-31_000));
        String encoded=r.encode();
        char replacement=encoded.charAt(encoded.length()-8)=='A'?'B':'A';
        String damaged=encoded.substring(0,encoded.length()-8)+replacement+encoded.substring(encoded.length()-7);
        try{PeerDirectory.Record.parse(damaged);fail("bad signature");}catch(IOException expected){}
    }
    @Test public void gossipEndpointsCannotDialPrivateNetworksButExplicitLanBootstrapWorks()throws Exception{
        Node advertised=node(0);advertised.setPublicMode(true);
        PeerRoute lan=advertised.publicRoute("127.0.0.1");
        PeerDirectory.Record record=PeerDirectory.Record.parse(advertised.publish(lan).encode());
        Node strict=new Node(keys.get(1),new NetworkAccess(),PublicAddress::allowed);strict.start(0);strict.setMeasuredUpload(300_000);strict.setPublicMode(true);cleanup.add(strict);
        assertTrue(strict.inspect(lan).sharing);
        try{strict.inspect(record.route);fail("Untrusted loopback endpoint");}catch(IOException expected){}
        assertTrue(record.route.discovered);
        assertFalse(lan.discovered);
    }
}
