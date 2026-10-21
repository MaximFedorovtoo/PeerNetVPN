package org.uzel.core;

import org.junit.*;
import static org.junit.Assert.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class PeerTransportTest extends NetworkTest {
    @Test public void ephemeralCredentialIsIdentityBoundAndRevokedWithRegistration()throws Exception{
        Node a=node(0),b=node(1),stranger=node(2);
        String tokenA=TlsIdentity.randomSecret(),tokenB=TlsIdentity.randomSecret();
        Node.TransportRegistration ab=a.registerPeerTransport(keys.get(1).fingerprint,()->new Socket("127.0.0.1",b.port()),tokenA);
        Node.TransportRegistration ba=b.registerPeerTransport(keys.get(0).fingerprint,()->new Socket("127.0.0.1",a.port()),tokenB);
        cleanup.add(ab);cleanup.add(ba);
        Invitation inviteA=new Invitation("127.0.0.1",a.port(),keys.get(0).fingerprint,tokenA);
        Invitation inviteB=new Invitation("127.0.0.1",b.port(),keys.get(1).fingerprint,tokenB);
        assertTrue(b.inspect(inviteA).sharing);
        try{stranger.inspect(inviteA);fail("Token must be bound to TLS identity");}catch(IOException expected){}
        Node.Session session=a.connect(inviteB,()->{});cleanup.add(session);assertTrue(session.isLive());
        assertFalse(a.isPublicMode());assertFalse(b.isPublicMode());
        ab.close();assertFalse(session.isLive());
        try{b.inspect(inviteA);fail("Revoked inbound token");}catch(IOException expected){}
    }
    @Test public void registrationsShareThreeIdentityCapacityAndReverseMeasurementIsRequired()throws Exception{
        Node a=node(0),b=node(1);
        cleanup.add(a.registerPeerTransport(keys.get(1).fingerprint,()->new Socket("127.0.0.1",b.port())));
        cleanup.add(a.registerPeerTransport(keys.get(2).fingerprint,()->{throw new IOException("offline");}));
        cleanup.add(a.registerPeerTransport(keys.get(3).fingerprint,()->{throw new IOException("offline");}));
        assertEquals(3,a.clientCount());
        try{a.registerPeerTransport(keys.get(4).fingerprint,()->{throw new IOException();});fail("capacity");}catch(IOException expected){}
        cleanup.add(b.registerPeerTransport(keys.get(0).fingerprint,()->new Socket("127.0.0.1",a.port())));
        // A forged/replayed callback endpoint cannot substitute for this measured node.
        a.setMeasuredUpload(0);
        try{b.connect(a.invitation("127.0.0.1"),()->{});fail("unmeasured exit");}catch(IOException expected){}
        a.setMeasuredUpload(3_000_000);b.setMeasuredUpload(0);
        try{a.connect(b.invitation("127.0.0.1"),()->{});fail("unmeasured peer");}catch(IOException expected){}
    }
    @Test public void privateTlsUsesRegisteredTransportInBothDirectionsWithoutPublicMode()throws Exception{
        Node a=node(0),b=node(1);AtomicInteger callsA=new AtomicInteger(),callsB=new AtomicInteger();
        Node.TransportRegistration ab=a.registerPeerTransport(keys.get(1).fingerprint,()->{callsA.incrementAndGet();return new Socket("127.0.0.1",b.port());});
        Node.TransportRegistration ba=b.registerPeerTransport(keys.get(0).fingerprint,()->{callsB.incrementAndGet();return new Socket("127.0.0.1",a.port());});
        cleanup.add(ab);cleanup.add(ba);
        Invitation unreachable=new Invitation("203.0.113.99",9,keys.get(1).fingerprint,b.invitation("127.0.0.1").secret);
        Node.Session session=a.connect(unreachable,()->{});cleanup.add(session);
        assertTrue(session.isLive());assertTrue(callsA.get()>0);assertTrue(callsB.get()>0);assertFalse(a.isPublicMode());
        ab.close();assertFalse(session.isLive());
        try{a.inspect(b.invitation("127.0.0.1"));fail("Closed registration must not silently dial direct");}catch(IOException expected){}
    }
    @Test public void wrongTransportIdentityStillFailsPinnedTls()throws Exception{
        Node a=node(0),b=node(1),wrong=node(2);
        cleanup.add(a.registerPeerTransport(keys.get(1).fingerprint,()->new Socket("127.0.0.1",wrong.port())));
        try{a.inspect(b.invitation("127.0.0.1"));fail("Transport is not identity proof");}catch(IOException expected){}
    }
    @Test public void closingRegistrationCancelsOpeningSocketWithoutFallback()throws Exception{
        Node a=node(0),b=node(1);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Socket> supplied=new AtomicReference<>();
        Node.TransportRegistration registration=a.registerPeerTransport(keys.get(1).fingerprint,()->{
            entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new InterruptedIOException();}
            Socket socket=new Socket("127.0.0.1",b.port());supplied.set(socket);return socket;
        });cleanup.add(registration);
        ExecutorService executor=Executors.newSingleThreadExecutor();cleanup.add(()->executor.shutdownNow());
        Future<?> pending=executor.submit(()->{try{a.inspect(b.invitation("127.0.0.1"));fail("Cancelled open");}catch(IOException expected){}});
        assertTrue(entered.await(3,TimeUnit.SECONDS));registration.close();release.countDown();pending.get(3,TimeUnit.SECONDS);
        assertNotNull(supplied.get());assertTrue(supplied.get().isClosed());
    }
    @Test public void replacementDoesNotLetOldRegistrationCloseNewTransport()throws Exception{
        Node a=node(0),b=node(1);
        Node.TransportRegistration old=a.registerPeerTransport(keys.get(1).fingerprint,()->new Socket("127.0.0.1",b.port()));
        Node.TransportRegistration current=a.registerPeerTransport(keys.get(1).fingerprint,()->new Socket("127.0.0.1",b.port()));cleanup.add(current);
        old.close();assertTrue(current.isLive());assertTrue(a.inspect(b.invitation("127.0.0.1")).sharing);
    }
}
