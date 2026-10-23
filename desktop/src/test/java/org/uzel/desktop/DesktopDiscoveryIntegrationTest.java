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
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class DesktopDiscoveryIntegrationTest {
    @Test(timeout=15000) public void searchReadsSignedAndroidCatalogWithoutSharingAndKeepsItOutOfFederationPublications()throws Exception {
        Path root=Files.createTempDirectory("uzelio-discovery-backend-"),phoneRoot=Files.createTempDirectory("uzelio-discovery-phone-");
        TlsIdentity self=IdentityStore.load(root),phone=IdentityStore.load(phoneRoot);DiscoveryStore bootstrap=new DiscoveryStore();
        bootstrap.accept(new DiscoveryIdentity(phone).presence("CA",1,9_000_000,System.currentTimeMillis()),System.currentTimeMillis());
        AtomicReference<DiscoveryStore> uploaded=new AtomicReference<>();
        DesktopDiscoveryClient client=new DesktopDiscoveryClient(self.fingerprint,"https://bootstrap.example/v1/exchange",url->new Connection(url,bootstrap,uploaded),()->null,1000);
        int port;try(ServerSocket free=new ServerSocket(0)){port=free.getLocalPort();}
        DesktopTrafficService traffic=new DesktopTrafficService(self,new NetworkAccess(),address->true,c->6_000_000);
        try(FederationBackend backend=new FederationBackend(root,self,traffic,0,new Routing(),client);
            FederationNode observer=new FederationNode(phone,Files.createTempDirectory("uzelio-discovery-observer-"),InetAddress.getLoopbackAddress(),0,"127.0.0.1",true)){
            backend.start(new DesktopConfig("127.0.0.1",port,true));backend.refreshDevices();
            assertEquals("ONLINE",backend.discoveryStatus().state());assertEquals(1,backend.discoveryStatus().verifiedDevices());
            assertFalse(backend.traffic().sharing());assertTrue(uploaded.get().presences(System.currentTimeMillis()).isEmpty());
            assertEquals(1,backend.devices().size());assertEquals(phone.fingerprint,backend.devices().get(0).fingerprint());assertEquals("CA",backend.devices().get(0).country());
            assertFalse(backend.hasTrafficRoute(phone.fingerprint));assertFalse(backend.canConnect(phone.fingerprint));
            backend.startSharing();backend.selectApplications(List.of(Files.write(root.resolve("Browser.exe"),new byte[]{1})));backend.refreshDevices();
            assertEquals(1,backend.devices().size());assertFalse(backend.hasTrafficRoute(phone.fingerprint));assertTrue(backend.canConnect(phone.fingerprint));
            var sent=uploaded.get().presences(System.currentTimeMillis());assertEquals(1,sent.size());assertEquals(self.fingerprint,sent.get(0).fingerprint);assertEquals(6_000_000,sent.get(0).upload);
            observer.start();observer.addContact(backend.ownCode());var federation=observer.lookup();
            assertTrue(federation.presences.stream().anyMatch(p->p.fingerprint.equals(self.fingerprint)));
            assertFalse(federation.presences.stream().anyMatch(p->p.fingerprint.equals(phone.fingerprint)));
        }
    }
    private static final class Routing implements FederationBackend.ApplicationRouting {
        public WindowsAppRouter.Availability availability(){return new WindowsAppRouter.Availability(true,true,"TEST");}
        public WindowsAppRouter.Snapshot snapshot(){return new WindowsAppRouter.Snapshot(false,"OFF","");}
        public void activate(List<Path> applications,DesktopTrafficService.ProxyEndpoint endpoint){}public void deactivate(){}public void close(){}
    }
    private static final class Connection extends HttpsURLConnection {
        final DiscoveryStore bootstrap;final AtomicReference<DiscoveryStore> uploaded;final ByteArrayOutputStream request=new ByteArrayOutputStream();
        Connection(URL url,DiscoveryStore bootstrap,AtomicReference<DiscoveryStore> uploaded){super(url);this.bootstrap=bootstrap;this.uploaded=uploaded;}
        public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}public OutputStream getOutputStream(){return request;}
        public int getResponseCode(){return 200;}
        public InputStream getInputStream()throws IOException {DiscoveryStore submitted=new DiscoveryStore();submitted.ingestFrame(request.toByteArray(),System.currentTimeMillis());uploaded.set(submitted);return new ByteArrayInputStream(bootstrap.exchange(request.toByteArray(),System.currentTimeMillis()));}
        public String getCipherSuite(){return "TEST_BOUNDARY";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return new Certificate[0];}public Principal getPeerPrincipal(){return ()->"test";}public Principal getLocalPrincipal(){return null;}
    }
}
