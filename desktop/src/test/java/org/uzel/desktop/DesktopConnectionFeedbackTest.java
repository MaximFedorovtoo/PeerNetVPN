package org.uzel.desktop;

import org.junit.Test;
import javax.swing.SwingUtilities;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** No real networking or Windows routing: exercise the actual window and queued actions. */
public class DesktopConnectionFeedbackTest {
    @Test public void pairingAndRoutingRemainPendingUntilRouteReadyInBothScopes() throws Exception {
        for (DesktopRoutingMode mode : DesktopRoutingMode.values()) exercise(mode,false);
    }
    @Test public void failedAttemptOffersRetryAndCancelClearsPendingFeedback() throws Exception {
        exercise(DesktopRoutingMode.ALL_TRAFFIC,true);
    }
    private void exercise(DesktopRoutingMode mode,boolean fail) throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),observerRelease=new CountDownLatch(1);
        AtomicBoolean connected=new AtomicBoolean(); AtomicInteger attempts=new AtomicInteger();
        DesktopBackend backend=(DesktopBackend)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DesktopBackend.class},(p,m,a)->switch(m.getName()) {
            case "snapshot" -> new DesktopBackend.Snapshot(true,0,0,0,false,"");
            case "traffic" -> new DesktopTrafficService.Snapshot(true,connected.get(),false,0,0,1000,0,0,0,"","SHARING");
            // Hold polling back: completion must publish a coherent route snapshot itself.
            case "devices" -> {observerRelease.await();yield List.of();} case "selectedApplications" -> mode==DesktopRoutingMode.ALL_TRAFFIC?List.of():List.of(Path.of("Browser.exe"));
            case "routingMode" -> mode;
            case "routingAvailability" -> new WindowsAppRouter.Availability(true,true,"");
            case "routingState" -> new WindowsAppRouter.Snapshot(connected.get(),connected.get()?"ACTIVE":"OFF","");
            case "discoveryStatus" -> null;
            case "connect" -> {attempts.incrementAndGet();entered.countDown();release.await();if(fail)throw new java.io.IOException("Pairing failed");connected.set(true);yield null;}
            case "disconnect" -> {connected.set(false);yield null;}
            default -> null;
        });
        Path root=Files.createTempDirectory("connection-feedback-"); DesktopWindow[] view=new DesktopWindow[1];
        SwingUtilities.invokeAndWait(()->{view[0]=new DesktopWindow(root,backend);set(view[0],"routingMode",mode);set(view[0],"selectedCount",mode==DesktopRoutingMode.ALL_TRAFFIC?0:1);call(view[0],"connectPeer","peer");
            assertEquals("Подключаемся…",call(view[0],"connectionTitle"));});
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->assertEquals("Подключаемся…",call(view[0],"connectionTitle")));
            capture(view[0],"pending-"+mode);
            release.countDown();
            await(view[0],fail?"Не удалось подключиться":"Соединение установлено");
            capture(view[0],(fail?"failed-":"connected-")+mode);
            if(!fail)SwingUtilities.invokeAndWait(()->{
                for(String state:List.of("OFF","FAILED")) {
                    set(view[0],"routingState",new WindowsAppRouter.Snapshot(false,state,""));
                    assertNotEquals("A live peer alone must not confirm Windows routing", "Соединение установлено",call(view[0],"connectionTitle"));
                }
                set(view[0],"routingState",new WindowsAppRouter.Snapshot(true,"ACTIVE",""));
                assertEquals("Соединение установлено",call(view[0],"connectionTitle"));
            });
            if(fail) {
                SwingUtilities.invokeAndWait(()->{assertEquals("Повторить",call(view[0],"connectionActionTitle"));call(view[0],"connectPeer","peer");call(view[0],"cancelConnection");
                    assertEquals("Не подключено",call(view[0],"connectionTitle"));assertEquals("Выбрать узел",call(view[0],"connectionActionTitle"));});
                assertTrue(attempts.get()>=1);
            }
        } finally {release.countDown();observerRelease.countDown();SwingUtilities.invokeAndWait(()->{try{view[0].renderPreview(root.resolve("preview"));}catch(Exception e){throw new RuntimeException(e);}});}
    }
    private static void await(DesktopWindow view,String expected)throws Exception {
        for(int i=0;i<100;i++){AtomicReference<String> text=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{call(view,"refresh");text.set((String)call(view,"connectionTitle"));});if(expected.equals(text.get()))return;Thread.sleep(20);}
        SwingUtilities.invokeAndWait(()->assertEquals(expected,call(view,"connectionTitle")));
    }
    private static void capture(DesktopWindow view,String name)throws Exception {
        SwingUtilities.invokeAndWait(()->{try {
            view.setSize(450,670);layout(view);
            var image=new java.awt.image.BufferedImage(450,670,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics=image.createGraphics();view.printAll(graphics);graphics.dispose();
            Path directory=Path.of("build","connection-feedback-preview");Files.createDirectories(directory);
            javax.imageio.ImageIO.write(image,"png",directory.resolve(name+".png").toFile());
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    private static void layout(java.awt.Container parent){parent.doLayout();for(var component:parent.getComponents())if(component instanceof java.awt.Container child)layout(child);}
    private static Object call(Object target,String name,Object...args){try{Method m=target.getClass().getDeclaredMethod(name,args.length==0?new Class<?>[0]:new Class<?>[]{String.class});m.setAccessible(true);return m.invoke(target,args);}catch(Exception e){throw new RuntimeException(e);}}
    private static void set(Object target,String name,Object value){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new RuntimeException(e);}}
}
