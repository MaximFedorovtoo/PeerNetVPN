package org.uzel.desktop;

import org.junit.Test;
import org.uzel.core.*;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class DesktopSharingIntegrationTest {
    @Test public void sharingAndDirectoryHaveIndependentLifecycles()throws Exception {
        Path root=Files.createTempDirectory("peernet-sharing-backend-");
        TlsIdentity identity=IdentityStore.load(root);
        DesktopTrafficService traffic=new DesktopTrafficService(identity,new NetworkAccess(),PublicAddress::allowed,cancelled->9_000_000);
        int directoryPort;try(ServerSocket free=new ServerSocket(0)){directoryPort=free.getLocalPort();}
        try(FederationBackend backend=new FederationBackend(root,identity,traffic,0)){
            backend.start(new DesktopConfig("127.0.0.1",directoryPort,true));
            assertFalse(backend.traffic().sharing());
            backend.startSharing();
            assertTrue(backend.traffic().sharing());
            assertEquals(9_000_000,backend.traffic().measuredUpload());
            assertTrue(backend.sharingCode().startsWith("uzel://"));
            assertThrows(IllegalArgumentException.class,()->backend.setSharingRate(new SharingRatePreference(2_999_999)));
            backend.setSharingRate(new SharingRatePreference(3_000_000));
            assertEquals(3_000_000,backend.traffic().shareRateLimit());
            backend.stopSharing();
            assertFalse(backend.traffic().sharing());
            assertTrue(backend.snapshot().running());
        }
    }
    @Test(timeout=15000) public void manualPeerSelectionUsesRealSessionAndRouterFailureClosesIt()throws Exception {
        Path root=Files.createTempDirectory("peernet-routing-failure-");TlsIdentity identity=IdentityStore.load(root);
        DesktopTrafficService traffic=traffic(identity);TestRouting routing=new TestRouting();routing.fail=true;
        try(DesktopTrafficService exit=traffic(IdentityStore.load(Files.createTempDirectory("peernet-exit-")));
            FederationBackend backend=new FederationBackend(root,identity,traffic,0,routing)){
            exit.startSharing(0);backend.startSharing();backend.addTrafficPeer(exit.invitation("127.0.0.1").encode());
            String peer=exit.invitation("127.0.0.1").fingerprint;
            assertFalse(backend.canConnect(peer));Path exe=Files.write(root.resolve("Browser.exe"),new byte[]{1});backend.selectApplications(List.of(exe));
            assertEquals(List.of(exe.toRealPath()),backend.selectedApplications());assertTrue(backend.canConnect(peer));
            assertEquals(1,backend.devices().size());assertEquals(peer,backend.devices().get(0).fingerprint());
            assertThrows(IOException.class,()->backend.connect(peer));assertNotNull(routing.endpoint);
            assertFalse(backend.traffic().connected());assertFalse(traffic.snapshot().connected());assertNull(traffic.localProxy());assertFalse(routing.active);
            assertFalse(backend.snapshot().running());assertTrue(backend.traffic().sharing());
        }
        assertEquals(1,DesktopApplications.load(root).size());
    }
    @Test(timeout=15000) public void disconnectDuringSlowActivationPreventsLateConnectedState()throws Exception {
        Path root=Files.createTempDirectory("peernet-routing-cancel-");TlsIdentity identity=IdentityStore.load(root);
        DesktopTrafficService traffic=traffic(identity);TestRouting routing=new TestRouting();routing.entered=new CountDownLatch(1);routing.release=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(DesktopTrafficService exit=traffic(IdentityStore.load(Files.createTempDirectory("peernet-exit-")));
            FederationBackend backend=new FederationBackend(root,identity,traffic,0,routing)){
            exit.startSharing(0);backend.startSharing();String code=exit.invitation("127.0.0.1").encode(),peer=exit.invitation("127.0.0.1").fingerprint;
            backend.addTrafficPeer(code);backend.selectApplications(List.of(Files.write(root.resolve("Browser.exe"),new byte[]{1})));
            Future<?> connecting=worker.submit(()->{try{backend.connect(peer);fail("Cancelled activation connected");}catch(Exception expected){assertTrue(expected instanceof IOException);}});
            assertTrue(routing.entered.await(3,TimeUnit.SECONDS));assertFalse(backend.traffic().connected());backend.disconnect();routing.release.countDown();connecting.get(3,TimeUnit.SECONDS);
            assertFalse(routing.active);assertFalse(backend.traffic().connected());assertNull(traffic.localProxy());
        }finally{routing.release.countDown();worker.shutdownNow();}
    }
    @Test(timeout=15000) public void peerLossRevokesApplicationRoutingAndChangingSelectionDisconnects()throws Exception {
        Path root=Files.createTempDirectory("peernet-routing-loss-");TlsIdentity identity=IdentityStore.load(root);TestRouting routing=new TestRouting();
        try(DesktopTrafficService exit=traffic(IdentityStore.load(Files.createTempDirectory("peernet-exit-")));
            FederationBackend backend=new FederationBackend(root,identity,traffic(identity),0,routing)){
            exit.startSharing(0);backend.startSharing();String peer=exit.invitation("127.0.0.1").fingerprint;
            backend.addTrafficPeer(exit.invitation("127.0.0.1").encode());backend.selectApplications(List.of(Files.write(root.resolve("Browser.exe"),new byte[]{1})));
            backend.connect(peer);assertTrue(backend.traffic().connected());assertTrue(routing.active);
            backend.selectApplications(List.of(Files.write(root.resolve("Other.exe"),new byte[]{1})));
            assertFalse(backend.traffic().connected());assertFalse(routing.active);
            backend.connect(peer);assertTrue(backend.traffic().connected());exit.stopSharing();
            await(()->!backend.traffic().connected() && !routing.active);
        }
    }
    @Test(timeout=12000) public void stopDuringProbeCancelsBackendStartWithoutStoppingDirectory()throws Exception {
        Path root=Files.createTempDirectory("peernet-backend-probe-");TlsIdentity identity=IdentityStore.load(root);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        DesktopTrafficService traffic=new DesktopTrafficService(identity,new NetworkAccess(),PublicAddress::allowed,c->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new InterruptedIOException();}return 9_000_000;});
        ExecutorService worker=Executors.newSingleThreadExecutor();int port;try(ServerSocket free=new ServerSocket(0)){port=free.getLocalPort();}
        try(FederationBackend backend=new FederationBackend(root,identity,traffic,0,new TestRouting())){
            backend.start(new DesktopConfig("127.0.0.1",port,true));
            Future<?> starting=worker.submit(()->{try{backend.startSharing();fail("Cancelled sharing start published");}catch(Exception expected){assertTrue(expected instanceof IOException);}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));backend.stopSharing();release.countDown();starting.get(3,TimeUnit.SECONDS);
            assertFalse(backend.traffic().sharing());assertTrue(backend.snapshot().running());assertTrue(backend.devices().isEmpty());
        }finally{release.countDown();worker.shutdownNow();}
    }
    @Test(timeout=15000) public void directoryPublishesOwnMeasuredIdentityAndImportsSignedRouteCountryWithExpiry()throws Exception {
        Path root=Files.createTempDirectory("peernet-own-publication-"),otherRoot=Files.createTempDirectory("peernet-observer-");TlsIdentity identity=IdentityStore.load(root),other=IdentityStore.load(otherRoot);
        int port;try(ServerSocket free=new ServerSocket(0)){port=free.getLocalPort();}
        try(FederationNode observer=new FederationNode(other,Files.createTempDirectory("peernet-observer-state-"),InetAddress.getLoopbackAddress(),0,"127.0.0.1",true);
            FederationBackend backend=new FederationBackend(root,identity,traffic(identity),0,new TestRouting())){
            observer.start();backend.start(new DesktopConfig("127.0.0.1",port,true));backend.startSharing();
            observer.addContact(backend.ownCode());FederationNode.LookupResult own=observer.lookup();
            assertTrue(own.presences.stream().anyMatch(p->p.fingerprint.equals(identity.fingerprint) && p.upload==9_000_000));
            assertTrue(own.routes.stream().anyMatch(r->r.fingerprint.equals(identity.fingerprint) && r.route.port!=port));
            assertTrue(backend.devices().isEmpty());
            long expiry=System.currentTimeMillis()+3000;PeerDirectory.Record foreign=shortLivedRoute(otherRoot,other,expiry);
            observer.acceptRoute(foreign);backend.addContact(observer.ownContact().encode());backend.refreshDevices();
            DeviceDirectoryView.Device device=backend.devices().stream().filter(d->d.fingerprint().equals(other.fingerprint)).findFirst().orElseThrow();
            assertEquals("CA",device.country());assertEquals(expiry,device.expiresAt());
            backend.selectApplications(List.of(Files.write(root.resolve("Browser.exe"),new byte[]{1})));assertTrue(backend.canConnect(other.fingerprint));
            await(()->!backend.canConnect(other.fingerprint));assertTrue(backend.devices().isEmpty());
            backend.stopSharing();assertTrue(backend.snapshot().running());
        }
    }
    private static PeerDirectory.Record shortLivedRoute(Path root,TlsIdentity identity,long expires)throws Exception {
        ByteArrayOutputStream payload=new ByteArrayOutputStream();DataOutputStream body=new DataOutputStream(payload);
        body.writeInt(1);body.writeUTF(PeerRoute.direct("127.0.0.1",9,identity.fingerprint).encode());body.writeUTF("CA");body.writeLong(1);body.writeLong(expires-120_000);body.writeLong(expires);
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(encoded);
        java.security.KeyStore keys=java.security.KeyStore.getInstance("PKCS12");char[] password="PeerNet local identity".toCharArray();
        try(InputStream input=Files.newInputStream(root.resolve("identity.p12"))){keys.load(input,password);}
        java.security.Signature signer=java.security.Signature.getInstance("SHA256withECDSA");signer.initSign((java.security.PrivateKey)keys.getKey("node",password));signer.update(payload.toByteArray());
        for(byte[] field:List.of(keys.getCertificate("node").getEncoded(),payload.toByteArray(),signer.sign())){out.writeInt(field.length);out.write(field);}
        return PeerDirectory.Record.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(encoded.toByteArray()));
    }
    private static DesktopTrafficService traffic(TlsIdentity identity){return new DesktopTrafficService(identity,new NetworkAccess(),address->true,c->9_000_000);}
    private static void await(java.util.function.BooleanSupplier ready)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!ready.getAsBoolean() && System.nanoTime()<until)Thread.sleep(10);assertTrue(ready.getAsBoolean());}
    private static final class TestRouting implements FederationBackend.ApplicationRouting {
        volatile boolean active,fail;volatile DesktopTrafficService.ProxyEndpoint endpoint;CountDownLatch entered,release;
        public WindowsAppRouter.Availability availability(){return new WindowsAppRouter.Availability(true,true,"TEST_ROUTER");}
        public WindowsAppRouter.Snapshot snapshot(){return new WindowsAppRouter.Snapshot(active,active?"ACTIVE":"OFF","");}
        public void activate(List<Path> paths,DesktopTrafficService.ProxyEndpoint proxy)throws IOException {
            endpoint=proxy;if(entered!=null){entered.countDown();try{release.await();}catch(InterruptedException e){throw new InterruptedIOException();}}
            if(fail)throw new IOException("Router failed");active=true;
        }
        public void deactivate(){active=false;}
        public void close(){active=false;}
    }
}
