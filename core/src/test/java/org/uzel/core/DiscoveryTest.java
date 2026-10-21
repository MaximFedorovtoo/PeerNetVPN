package org.uzel.core;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class DiscoveryTest extends NetworkTest {
    @Test public void publicOptOutDuringHandlerRevokesDiscoveryResponse()throws Exception {
        Node a=node(0),b=node(1);a.setPublicMode(true);b.setPublicMode(true);
        cleanup.add(b.registerPeerTransport(keys.get(0).fingerprint,()->new Socket("127.0.0.1",a.port())));
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        b.setDiscoveryExchange(frame->{entered.countDown();try{release.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new InterruptedIOException();}return new byte[]{42};});
        try(Socket socket=a.open(b.publicRoute("127.0.0.1"),Node.DISCOVERY)){
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeInt(0);out.flush();
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));b.stopPublicAdmission();release.countDown();
            try{new DataInputStream(socket.getInputStream()).readInt();fail("Public response after opt-out");}catch(IOException expected){}
        }finally{release.countDown();}
    }
    @Test public void signedPresenceAndEncryptedSignalRejectTamperRecipientReplayAndExpiry()throws Exception {
        Node a=node(0),b=node(1);long now=System.currentTimeMillis();
        DiscoveryIdentity alice=new DiscoveryIdentity(keys.get(0)),bob=new DiscoveryIdentity(keys.get(1)),wrong=new DiscoveryIdentity(keys.get(2));
        DiscoveryPresence pa=alice.presence("RU",0,100000,now),pb=bob.presence("DE",1,200000,now);
        String offer=PairingCode.offer(keys.get(0),"v=0\r\na=secret-sdp",a.invitation("127.0.0.1"),now).encode();
        DiscoverySignal signal=alice.seal(pb,offer,now);
        assertFalse(new String(signal.encode(),java.nio.charset.StandardCharsets.ISO_8859_1).contains(offer));
        try{wrong.open(signal,now);fail("recipient");}catch(IOException expected){}
        assertEquals(offer,bob.open(signal,now));
        try{bob.open(signal,now);fail("replay");}catch(IOException expected){}
        byte[] tampered=signal.encode();tampered[tampered.length-10]^=1;
        try{DiscoverySignal.parse(tampered,now);fail("tampered signal");}catch(IOException expected){}
        byte[] presence=pa.encode();presence[presence.length-10]^=1;
        try{DiscoveryPresence.parse(presence,now);fail("tampered presence");}catch(IOException expected){}
        try{DiscoveryPresence.parse(pa.encode(),pa.expiresAt);fail("expired presence");}catch(IOException expected){}
        try{DiscoverySignal.parse(signal.encode(),signal.expiresAt);fail("expired signal");}catch(IOException expected){}
        DiscoveryIdentity replacement=new DiscoveryIdentity(keys.get(1));
        try{replacement.open(signal,now);fail("same TLS identity different ephemeral key");}catch(IOException expected){}
    }
    @Test public void storeBoundsFramesDeduplicatesAndExpires()throws Exception {
        Node a=node(0);long now=System.currentTimeMillis();DiscoveryIdentity alice=new DiscoveryIdentity(keys.get(0)),bob=new DiscoveryIdentity(keys.get(1));
        DiscoveryPresence pa=alice.presence("RU",0,100,now),pb=bob.presence("",0,100,now);DiscoveryStore store=new DiscoveryStore();
        assertTrue(store.accept(pa,now));assertFalse(store.accept(pa,now));
        String offer=PairingCode.offer(keys.get(0),"v=0",a.invitation("127.0.0.1"),now).encode();
        DiscoverySignal signal=alice.seal(pb,offer,now);assertTrue(store.accept(signal,now));assertFalse(store.accept(signal,now));
        for(int i=1;i<8;i++)assertTrue(store.accept(alice.seal(pb,offer,now),now));
        assertFalse(store.accept(alice.seal(pb,offer,now),now));
        DiscoveryStore other=new DiscoveryStore();other.ingestFrame(store.exportFrame(now),now);
        assertEquals(1,other.presences(now).size());assertEquals(8,other.signalsFor(pb.fingerprint,now).size());
        assertTrue(store.exportFrame(now).length<=DiscoveryStore.MAX_FRAME);
        try{store.ingestFrame(new byte[DiscoveryStore.MAX_FRAME+1],now);fail("oversize");}catch(IOException expected){}
        byte[] trailing=Arrays.copyOf(store.exportFrame(now),store.exportFrame(now).length+1);
        try{store.ingestFrame(trailing,now);fail("trailing");}catch(IOException expected){}
        assertEquals(0,store.presences(now+120000).size());assertEquals(0,store.signalsFor(pb.fingerprint,now+120000).size());
        other.clear();assertTrue(other.presences(now).isEmpty());
    }
    @Test public void thirdPeerLearnsThroughAuthenticatedMeshWithoutBootstrapAndRevocationStopsExchange()throws Exception {
        Node a=node(0),b=node(1),c=node(2);long now=System.currentTimeMillis();
        DiscoveryStore sa=new DiscoveryStore(),sb=new DiscoveryStore(),sc=new DiscoveryStore();
        sa.accept(new DiscoveryIdentity(keys.get(0)).presence("RU",0,1000,now),now);
        sb.accept(new DiscoveryIdentity(keys.get(1)).presence("DE",0,1000,now),now);
        sc.accept(new DiscoveryIdentity(keys.get(2)).presence("FR",0,1000,now),now);
        a.setDiscoveryExchange(frame->sa.exchange(frame,System.currentTimeMillis()));b.setDiscoveryExchange(frame->sb.exchange(frame,System.currentTimeMillis()));c.setDiscoveryExchange(frame->sc.exchange(frame,System.currentTimeMillis()));
        try{a.exchangeDiscovery(b.invitation("127.0.0.1"),sa.exportFrame(now));fail("unattached peer");}catch(IOException expected){}
        Node.TransportRegistration ab=a.registerPeerTransport(keys.get(1).fingerprint,()->new Socket("127.0.0.1",b.port()));
        Node.TransportRegistration ba=b.registerPeerTransport(keys.get(0).fingerprint,()->new Socket("127.0.0.1",a.port()));
        Node.TransportRegistration bc=b.registerPeerTransport(keys.get(2).fingerprint,()->new Socket("127.0.0.1",c.port()));
        Node.TransportRegistration cb=c.registerPeerTransport(keys.get(1).fingerprint,()->new Socket("127.0.0.1",b.port()));
        cleanup.add(ab);cleanup.add(ba);cleanup.add(bc);cleanup.add(cb);
        sb.ingestFrame(b.exchangeDiscovery(c.invitation("127.0.0.1"),sb.exportFrame(now)),now);
        sa.ingestFrame(a.exchangeDiscovery(b.invitation("127.0.0.1"),sa.exportFrame(now)),now);
        assertEquals(3,sa.presences(now).size());assertTrue(sa.presences(now).stream().anyMatch(p->p.fingerprint.equals(keys.get(2).fingerprint)));
        ba.close();try{a.exchangeDiscovery(b.invitation("127.0.0.1"),sa.exportFrame(now));fail("revoked");}catch(IOException expected){}
    }
}
