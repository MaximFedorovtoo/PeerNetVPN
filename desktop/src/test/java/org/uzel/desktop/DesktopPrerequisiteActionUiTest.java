package org.uzel.desktop;

import org.junit.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DesktopPrerequisiteActionUiTest {
    @Test public void eachDeviceOffersMissingPrerequisiteAndOnlyConnectsWhenEligible()throws Exception {
        assertDeviceAction(true,false,0,true,true,false,false,"Выбрать приложения",true);
        assertDeviceAction(false,false,2,true,true,false,false,"Включить раздачу",true);
        assertDeviceAction(true,false,2,true,false,false,false,"Компонент Windows",true);
        assertDeviceAction(true,false,2,false,false,false,false,"Нужна Windows x64",false);
        assertDeviceAction(true,true,2,true,true,false,false,"Дождитесь замера",false);
        assertDeviceAction(true,false,2,true,true,false,false,"Подключить",false);
        assertDeviceAction(true,false,2,true,true,true,false,"Подключить",true);
        assertDeviceAction(true,false,0,true,true,false,true,"Выбрать приложения",false);
    }
    @Test public void deviceSharingActionStartsSharingWithoutAttemptingConnection()throws Exception {
        try(Fixture fixture=new Fixture(false,false,2,true,true,false)) {
            JButton action=fixture.deviceAction(false);
            assertEquals("Включить раздачу",action.getAccessibleContext().getAccessibleName());assertTrue(action.isEnabled());
            SwingUtilities.invokeAndWait(action::doClick);
            assertTrue("Device action should resolve the missing sharing prerequisite",fixture.sharingStarted.await(3,TimeUnit.SECONDS));
            assertEquals(0,fixture.connections.get());
        }
    }
    private void assertDeviceAction(boolean sharing,boolean measuring,int applications,boolean supported,boolean installed,boolean eligible,boolean busy,String title,boolean enabled)throws Exception {
        try(Fixture fixture=new Fixture(sharing,measuring,applications,supported,installed,eligible)) {
            for(int width:new int[]{445,480}) {
                JButton action=fixture.deviceAction(busy,width);
                assertEquals(title,action.getAccessibleContext().getAccessibleName());assertEquals(title,enabled,action.isEnabled());
                assertTrue("Action label must fit at page width "+width,action.getFontMetrics(new Font("Segoe UI",Font.BOLD,14)).stringWidth(title)<=action.getWidth()-16);
                assertTrue("Action stays within its card",action.getX()+action.getWidth()<=width-35);
                String reason=DesktopConnectionPresentation.reason(sharing,measuring,applications,supported,installed,eligible);
                assertTrue("Prerequisite explanation must not overlap its button",37+action.getFontMetrics(new Font("Segoe UI",Font.PLAIN,12)).stringWidth(reason)<action.getX());
            }
            assertEquals("Rendering must not connect",0,fixture.connections.get());
        }
    }
    private static Object field(Object object,String name)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void set(Object object,String name,Object value)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static final class Fixture implements AutoCloseable {
        final DesktopWindow view;final CountDownLatch observed=new CountDownLatch(1),sharingStarted=new CountDownLatch(1);final AtomicInteger connections=new AtomicInteger();
        Fixture(boolean sharing,boolean measuring,int applications,boolean supported,boolean installed,boolean eligible)throws Exception {
            DesktopBackend backend=(DesktopBackend)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DesktopBackend.class},(proxy,method,args)->switch(method.getName()) {
                case "snapshot"->new DesktopBackend.Snapshot(true,0,1,0,false,"");
                case "traffic"->new DesktopTrafficService.Snapshot(sharing,false,measuring,0,0,sharing?1000:0,0,0,0,"","OFF");
                case "devices"->List.of(new DeviceDirectoryView.Device("peer","RU",0,1000,Long.MAX_VALUE));
                case "selectedApplications"->java.util.Collections.nCopies(applications,Path.of("Browser.exe"));
                case "routingAvailability"->new WindowsAppRouter.Availability(supported,installed,"");
                case "routingState"->new WindowsAppRouter.Snapshot(false,"OFF","");
                case "discoveryStatus"->new DesktopBackend.DiscoveryStatus("ONLINE","",1);
                case "canConnect"->eligible;
                case "hasTrafficRoute"->{observed.countDown();yield true;}
                case "startSharing"->{sharingStarted.countDown();yield null;}
                case "connect"->{connections.incrementAndGet();yield null;}
                default->throw new AssertionError("Unexpected backend operation "+method.getName());
            });
            DesktopWindow[] created=new DesktopWindow[1];Path root=Files.createTempDirectory("desktop-prerequisite-ui-");
            SwingUtilities.invokeAndWait(()->created[0]=new DesktopWindow(root,backend));view=created[0];
            assertTrue(observed.await(3,TimeUnit.SECONDS));
            // Drain the observer after its publication has been queued, then drain the EDT.
            ((ExecutorService)field(view,"observer")).submit(()->{}).get(3,TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(()->{});
        }
        JButton deviceAction(boolean busy)throws Exception {return deviceAction(busy,480);}
        JButton deviceAction(boolean busy,int width)throws Exception {
            SwingUtilities.invokeAndWait(()->{try{
                set(view,"previewRendering",true);set(view,"busy",busy);view.showTab(1);
                JPanel page=(JPanel)field(view,"page");page.setSize(width,700);
                BufferedImage image=new BufferedImage(width,700,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();page.paint(graphics);graphics.dispose();
            }catch(Exception e){throw new RuntimeException(e);}});
            SwingUtilities.invokeAndWait(()->{});
            JPanel page=(JPanel)field(view,"page");
            for(Component component:page.getComponents())if(component instanceof JButton action&&action.getY()>=400&&action.getX()>100)return action;
            throw new AssertionError("Device action absent");
        }
        public void close()throws Exception {
            SwingUtilities.invokeAndWait(()->{try{((Timer)field(view,"timer")).stop();for(String executor:List.of("worker","observer","iconWorker","discoveryWorker"))((ExecutorService)field(view,executor)).shutdownNow();}catch(Exception e){throw new RuntimeException(e);}});
        }
    }
}
