package org.uzel.desktop;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class WindowsRoutingUpgradeTest {
    @Test public void oldOrMissingBrokerRequiresSafeUpdateBeforeRouting()throws Exception {
        Path broker=Files.createTempDirectory("uzelio-broker-upgrade-").resolve("PeerNetRoutingBroker.ps1");
        assertFalse(WindowsAppRouter.brokerMatchesBundled(broker));
        try(var resource=getClass().getResourceAsStream("/org/uzel/desktop/routing/PeerNetRoutingBroker.ps1")){
            assertNotNull(resource);Files.copy(resource,broker);
        }
        assertTrue(WindowsAppRouter.brokerMatchesBundled(broker));
        Files.writeString(broker,"# prior broker without Uzelio carrier exclusion");
        assertFalse(WindowsAppRouter.brokerMatchesBundled(broker));
    }
}
