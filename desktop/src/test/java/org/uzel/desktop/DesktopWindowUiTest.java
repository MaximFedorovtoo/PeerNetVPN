package org.uzel.desktop;

import org.junit.Test;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DesktopWindowUiTest {
    @Test public void allTabsRenderWithoutTrafficActionsOrBlockingBackendQueriesOnEdt()throws Exception {
        AtomicInteger uiQueries=new AtomicInteger(),actions=new AtomicInteger();
        DesktopBackend backend=(DesktopBackend)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DesktopBackend.class},(proxy,method,args)->{
            String name=method.getName();
            if(List.of("devices","canConnect","selectedApplications","routingAvailability","routingState").contains(name)&&SwingUtilities.isEventDispatchThread())uiQueries.incrementAndGet();
            return switch(name) {
                case "snapshot"->new DesktopBackend.Snapshot(false,0,0,0,false,"");
                case "traffic"->new DesktopTrafficService.Snapshot(false,false,false,0,0,0,0,0,0,"","OFF");
                case "devices","selectedApplications"->List.of();
                case "canConnect"->false;
                case "routingAvailability"->new WindowsAppRouter.Availability(true,false,"RUN_WINDOWS_ROUTING_SETUP");
                case "routingState"->new WindowsAppRouter.Snapshot(false,"OFF","");
                default->{actions.incrementAndGet();throw new AssertionError("Rendering invoked backend action: "+name);}
            };
        });
        Path root=Files.createTempDirectory("peernet-ui-test-");
        SwingUtilities.invokeAndWait(()->{try{new DesktopWindow(root,backend).renderPreview(root.resolve("preview"));}catch(Exception failure){throw new RuntimeException(failure);}});
        for(String tab:List.of("home","map","traffic","profile"))for(String suffix:List.of("","-min")) {
            var image=ImageIO.read(root.resolve("preview").resolve(tab+suffix+".png").toFile());
            assertEquals(suffix.isEmpty()?520:450,image.getWidth());
            assertEquals(suffix.isEmpty()?870:670,image.getHeight());
        }
        assertEquals("Paint must use cached status",0,uiQueries.get());
        assertEquals("Rendering must not start sharing, connect, install or export codes",0,actions.get());
    }
}
