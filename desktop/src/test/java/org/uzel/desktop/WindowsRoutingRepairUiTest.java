package org.uzel.desktop;

import org.junit.Test;
import javax.swing.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class WindowsRoutingRepairUiTest {
    @Test public void readyBrokerStillOffersManualRepairAfterInterruptedManifestUpdate()throws Exception {
        AtomicInteger requested=new AtomicInteger();
        SwingUtilities.invokeAndWait(()->{
            // A replaced broker can appear READY while its manifest still needs recovery.
            JButton action=DesktopWindow.routingSetupAction(new WindowsAppRouter.Availability(true,true,"READY"),requested::incrementAndGet);
            assertNotNull(action);assertTrue(action.isEnabled());
            assertEquals("Обновить компонент",action.getText());
            assertEquals("Repair requires an explicit click",0,requested.get());
            action.doClick();assertEquals(1,requested.get());
            assertNull(DesktopWindow.routingSetupAction(new WindowsAppRouter.Availability(false,false,"WINDOWS_X64_REQUIRED"),requested::incrementAndGet));
        });
    }
}
