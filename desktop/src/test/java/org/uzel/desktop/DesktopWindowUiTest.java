package org.uzel.desktop;

import org.junit.Test;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class DesktopWindowUiTest {
    @Test public void allTabsRenderWithoutTrafficActionsOrBlockingBackendQueriesOnEdt()throws Exception {
        AtomicInteger uiQueries=new AtomicInteger(),actions=new AtomicInteger();
        DesktopBackend backend=(DesktopBackend)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DesktopBackend.class},(proxy,method,args)->{
            String name=method.getName();
            if(List.of("devices","canConnect","selectedApplications","routingAvailability","routingState","discoveryStatus","hasTrafficRoute").contains(name)&&SwingUtilities.isEventDispatchThread())uiQueries.incrementAndGet();
            return switch(name) {
                case "snapshot"->new DesktopBackend.Snapshot(false,0,0,0,false,"");
                case "traffic"->new DesktopTrafficService.Snapshot(false,false,false,0,0,0,0,0,0,"","OFF");
                case "devices","selectedApplications"->List.of();
                case "canConnect","hasTrafficRoute"->false;
                case "routingAvailability"->new WindowsAppRouter.Availability(true,false,"RUN_WINDOWS_ROUTING_SETUP");
                case "routingState"->new WindowsAppRouter.Snapshot(false,"OFF","");
                case "discoveryStatus"->null; // Older adapters/mocks may omit the optional discovery status.
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
    @Test public void openingMapStartsDirectoryThenSearchesWithoutEnablingSharing()throws Exception {
        AtomicBoolean running=new AtomicBoolean();AtomicInteger sharing=new AtomicInteger();CountDownLatch queried=new CountDownLatch(1);
        List<String> order=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        DesktopBackend backend=(DesktopBackend)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DesktopBackend.class},(proxy,method,args)->switch(method.getName()) {
            case "snapshot"->new DesktopBackend.Snapshot(running.get(),0,0,0,false,"");
            case "traffic"->new DesktopTrafficService.Snapshot(false,false,false,0,0,0,0,0,0,"","OFF");
            case "devices","selectedApplications"->List.of();
            case "canConnect","hasTrafficRoute"->false;
            case "routingAvailability"->new WindowsAppRouter.Availability(true,false,"");
            case "routingState"->new WindowsAppRouter.Snapshot(false,"OFF","");
            case "discoveryStatus"->null;
            case "start"->{assertFalse(SwingUtilities.isEventDispatchThread());running.set(true);order.add("start");yield null;}
            case "refreshDevices"->{assertFalse(SwingUtilities.isEventDispatchThread());assertTrue(running.get());order.add("query");queried.countDown();yield new DesktopBackend.SearchResult(1,false);}
            case "startSharing"->{sharing.incrementAndGet();yield null;}
            default->throw new AssertionError("Unexpected UI action "+method.getName());
        });
        Path root=Files.createTempDirectory("desktop-map-start-");DesktopWindow[] view=new DesktopWindow[1];
        SwingUtilities.invokeAndWait(()->{view[0]=new DesktopWindow(root,backend);view[0].showTab(1);});
        boolean completed=queried.await(3,TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(()->{try{view[0].renderPreview(root.resolve("preview"));}catch(Exception e){throw new RuntimeException(e);}});
        assertTrue("Opening map should query automatically",completed);assertEquals(List.of("start","query"),order);assertEquals(0,sharing.get());
    }
}
