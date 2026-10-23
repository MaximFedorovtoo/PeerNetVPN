package org.uzel.desktop;
import org.junit.Test;
import org.uzel.core.FederationContact;
import java.nio.file.*;
import java.net.ServerSocket;
import static org.junit.Assert.*;

public class FederationBackendTest {
    @Test public void realDirectoryStartStopAndRestartPreservesKey()throws Exception {
        Path root=Files.createTempDirectory("peernet-desktop-");
        int port;try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var identity=IdentityStore.load(root);
        try(FederationBackend backend=new FederationBackend(root,identity,DesktopDiscoveryClient.disabled(identity.fingerprint))){
            assertFalse(backend.snapshot().running());
            backend.start(new DesktopConfig("localhost",port,true));
            String fingerprint=FederationContact.parse(backend.ownCode()).fingerprint;
            assertEquals(IdentityStore.load(root).fingerprint,fingerprint);
            assertTrue(backend.snapshot().running());
            DesktopBackend.SearchResult search=backend.refreshDevices();
            assertTrue(search.partial());
            assertTrue(search.visitedServers()>=1);
            assertTrue(backend.devices().isEmpty());
            backend.stop();assertFalse(backend.snapshot().running());
            assertThrows(Exception.class,backend::refreshDevices);
            backend.start(new DesktopConfig("localhost",port,true));
            assertEquals(fingerprint,FederationContact.parse(backend.ownCode()).fingerprint);
            assertThrows(Exception.class,()->backend.addContact("forged-code"));
        }
    }
}
