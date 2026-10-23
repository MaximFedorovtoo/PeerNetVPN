package org.uzel.desktop;

import org.junit.Test;
import org.uzel.core.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class DesktopConnectionSwitchTest {
    @Test(timeout=15000) public void wholeSystemModeConnectsWithoutSelectionAndRestoresSavedApplicationScope()throws Exception {
        Class<?> mode;try{mode=Class.forName("org.uzel.desktop.DesktopRoutingMode");}catch(ClassNotFoundException missing){fail("Routing scope selection is missing");return;}
        Object all=Arrays.stream(mode.getEnumConstants()).filter(value->value.toString().equals("ALL_TRAFFIC")).findFirst().orElseThrow();
        Object selected=Arrays.stream(mode.getEnumConstants()).filter(value->value.toString().equals("SELECTED_APPLICATIONS")).findFirst().orElseThrow();
        var set=FederationBackend.class.getMethod("setRoutingMode",mode);var get=FederationBackend.class.getMethod("routingMode");
        Path root=Files.createTempDirectory("desktop-routing-scope-");TlsIdentity self=IdentityStore.load(root);
        try(DesktopTrafficService exit=traffic(identity());FederationBackend backend=new FederationBackend(root,self,traffic(self),0,new Routing())){
            exit.startSharing(0);backend.startSharing();String peer=exit.invitation("127.0.0.1").fingerprint;backend.addTrafficPeer(exit.invitation("127.0.0.1").encode());
            assertFalse(backend.canConnect(peer));set.invoke(backend,all);assertTrue(backend.canConnect(peer));backend.connect(peer);assertTrue(backend.traffic().connected());
            Path executable=Files.write(root.resolve("Browser.exe"),new byte[]{1});backend.selectApplications(List.of(executable));backend.connect(peer);
            set.invoke(backend,selected);assertFalse(backend.traffic().connected());assertTrue(backend.traffic().sharing());assertEquals(List.of(executable.toRealPath()),backend.selectedApplications());
            set.invoke(backend,all);assertEquals(all,get.invoke(backend));
        }
        try(FederationBackend reloaded=new FederationBackend(root,self,traffic(self),0,new Routing())){assertEquals(all,get.invoke(reloaded));assertEquals(1,reloaded.selectedApplications().size());set.invoke(reloaded,selected);}
    }
    @Test(timeout=15000) public void newSelectionCancelsPendingPairingWithoutStoppingOwnSharingOrIncomingSession()throws Exception {
        Path root=Files.createTempDirectory("desktop-switch-");TlsIdentity self=IdentityStore.load(root),remote=identity();
        DiscoveryIdentity remoteDiscovery=new DiscoveryIdentity(remote);DiscoveryStore server=new DiscoveryStore();
        server.accept(remoteDiscovery.presence("CA",0,3_000_000,System.currentTimeMillis()),System.currentTimeMillis());
        DesktopTrafficService own=traffic(self);Routing routing=new Routing();CountDownLatch offered=new CountDownLatch(1);
        AtomicReference<DesktopPairingManager.TransportListener> retired=new AtomicReference<>();
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try(DesktopDiscoveryClient discovery=new DesktopDiscoveryClient(self.fingerprint,"https://bootstrap.example/v1/exchange",url->new Connection(url,server),()->null,1000);
            DesktopTrafficService exit=traffic(identity());DesktopTrafficService replacement=traffic(identity());Node incoming=new Node(identity(),new NetworkAccess(),a->true);
            FederationBackend backend=new FederationBackend(root,self,own,0,routing,discovery,(node,key,signing,client,listener)->new DesktopPairingManager(node,key,signing,client,listener,(port,callbacks)->{
                retired.set(callbacks);return new DesktopPairingManager.Transport(){
                    public void createOffer(){offered.countDown();}public void acceptOffer(String sdp){}public void acceptAnswer(String sdp){}
                    public boolean isConnected(){return false;}public Socket openStream()throws IOException{throw new IOException("Pending");}public void close(){}
                };
            },5000))){
            exit.startSharing(0);replacement.startSharing(0);incoming.start(0);incoming.setMeasuredUpload(3_000_000);backend.startSharing();discovery.refresh().get(2,TimeUnit.SECONDS);
            backend.selectApplications(List.of(Files.write(root.resolve("Browser.exe"),new byte[]{1})));
            backend.addTrafficPeer(exit.invitation("127.0.0.1").encode());
            backend.addTrafficPeer(replacement.invitation("127.0.0.1").encode());
            try(Node.Session served=incoming.connect(own.invitation("127.0.0.1"),()->{})){
                Future<?> first=workers.submit(()->{try{backend.connect(remote.fingerprint);fail("Retired selection connected");}catch(IOException expected){}catch(Exception failure){throw new RuntimeException(failure);}});
                assertTrue(offered.await(2,TimeUnit.SECONDS));
                Future<?> second=workers.submit(()->{try{backend.connect(exit.invitation("127.0.0.1").fingerprint);}catch(Exception failure){throw new RuntimeException(failure);}});
                second.get(2,TimeUnit.SECONDS);first.get(2,TimeUnit.SECONDS);
                assertTrue(backend.traffic().connected());assertTrue(backend.traffic().sharing());assertEquals(1,own.snapshot().clients());
                retired.get().state("FAILED");assertTrue(served.isLive());assertTrue(backend.traffic().connected());
                backend.connect(replacement.invitation("127.0.0.1").fingerprint);assertTrue(backend.traffic().connected());assertIncomingTraffic(served);
                assertEquals(1,replacement.snapshot().clients());
                backend.disconnect();assertFalse(backend.traffic().connected());assertFalse(routing.active);assertNull(own.localProxy());
                assertTrue(backend.traffic().sharing());assertTrue(served.isLive());assertEquals(1,own.snapshot().clients());
                assertIncomingTraffic(served);
                backend.connect(exit.invitation("127.0.0.1").fingerprint);assertTrue(backend.traffic().connected());assertIncomingTraffic(served);
            }
        }finally{workers.shutdownNow();}
    }
    private static void assertIncomingTraffic(Node.Session session)throws Exception {
        try(ServerSocket echo=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            ExecutorService io=Executors.newSingleThreadExecutor();
            try{
                Future<?> reply=io.submit(()->{try(Socket accepted=echo.accept()){accepted.getOutputStream().write(37);}catch(IOException failure){throw new UncheckedIOException(failure);}});
                try(Socket flow=session.openTcp("127.0.0.1",echo.getLocalPort())){flow.setSoTimeout(2000);assertEquals(37,flow.getInputStream().read());}
                reply.get(2,TimeUnit.SECONDS);
            }finally{io.shutdownNow();}
        }
    }
    private static TlsIdentity identity()throws Exception{return IdentityStore.load(Files.createTempDirectory("desktop-switch-identity-"));}
    private static DesktopTrafficService traffic(TlsIdentity identity){return new DesktopTrafficService(identity,new NetworkAccess(),a->true,c->3_000_000);}
    private static final class Routing implements FederationBackend.ApplicationRouting {
        volatile boolean active;
        public WindowsAppRouter.Availability availability(){return new WindowsAppRouter.Availability(true,true,"");}
        public WindowsAppRouter.Snapshot snapshot(){return new WindowsAppRouter.Snapshot(active,active?"ACTIVE":"OFF","");}
        public void activate(List<Path> applications,DesktopTrafficService.ProxyEndpoint proxy){active=true;}public void deactivate(){active=false;}public void close(){active=false;}
    }
    private static final class Connection extends HttpsURLConnection {
        final DiscoveryStore server;final ByteArrayOutputStream request=new ByteArrayOutputStream();Connection(URL url,DiscoveryStore server){super(url);this.server=server;}
        public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}public OutputStream getOutputStream(){return request;}public int getResponseCode(){return 200;}
        public InputStream getInputStream()throws IOException{return new ByteArrayInputStream(server.exchange(request.toByteArray(),System.currentTimeMillis()));}
        public String getCipherSuite(){return "TEST";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return new Certificate[0];}public Principal getPeerPrincipal(){return ()->"test";}public Principal getLocalPrincipal(){return null;}
    }
}
