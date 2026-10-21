package org.uzel.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.test.InstrumentationTestCase;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/** Product UI render smoke test; does not change sharing, routing or user preferences. */
public final class InterfaceDeviceTest extends InstrumentationTestCase {
    public void testReferenceScenes()throws Exception {
        Activity activity=getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            // Test-only snapshots; no controller state or saved preferences are modified.
            for(int scene=0;scene<5;scene++){
                final int selected=scene;
                getInstrumentation().runOnMainSync(()->{
                    try {
                        java.lang.reflect.Field hf=MainActivity.class.getDeclaredField("handler");hf.setAccessible(true);
                        java.lang.reflect.Field tf=MainActivity.class.getDeclaredField("ticker");tf.setAccessible(true);
                        ((android.os.Handler)hf.get(activity)).removeCallbacks((Runnable)tf.get(activity));
                        Method show=MainActivity.class.getDeclaredMethod("showTab",int.class);show.setAccessible(true);show.invoke(activity,selected==0?0:1);
                        NodeController.Snapshot state=new NodeController.Snapshot();state.sharing=true;state.status="SHARING";state.discoveryState=selected==2?"STARTING":selected==4?"OFFLINE":"ONLINE";
                        if(selected<2){
                            state.vpnConnected=selected==0;state.status=selected==0?"CONNECTED":"SHARING";state.connectedCountry="DE";state.sharedBytes=640L*1024*1024;state.receivedBytes=1200L*1024*1024;state.clients=1;
                            for(String code:new String[]{"DE","FR"}){NodeController.Peer peer=new NodeController.Peer();peer.id=code;peer.country=code;peer.autoDiscovered=true;peer.announced=true;peer.freeSlots=2;peer.upload=1000000;state.peers.add(peer);}
                        }
                        Method render=MainActivity.class.getDeclaredMethod("renderSnapshot",NodeController.Snapshot.class);render.setAccessible(true);render.invoke(activity,state);
                        save(activity,"reference-scene-"+selected+".png");
                    }catch(Exception failure){throw new AssertionError(failure);}
                });
            }
        }finally{getInstrumentation().runOnMainSync(activity::finish);}
    }
    private void save(Activity activity,String name)throws Exception {
        View view=activity.getWindow().getDecorView();int width=view.getWidth(),height=view.getHeight();assertTrue(width>0&&height>0);
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        try(FileOutputStream out=new FileOutputStream(new File(activity.getExternalFilesDir(null),name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
    public void testScreens()throws Exception {
        Activity activity=getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            for(int tab=0;tab<4;tab++){
                final int selected=tab;
                getInstrumentation().runOnMainSync(()->{
                    try{Method show=MainActivity.class.getDeclaredMethod("showTab",int.class);show.setAccessible(true);show.invoke(activity,selected);}
                    catch(Exception failure){throw new AssertionError(failure);}
                });
                getInstrumentation().waitForIdleSync();
                getInstrumentation().runOnMainSync(()->{
                    View view=activity.getWindow().getDecorView();assertTrue("Screen must be laid out",view.getWidth()>0&&view.getHeight()>0);
                    // A locked device may suppress display traversals. Render the product view
                    // at its real window dimensions without relying on an unlocked display.
                    int width=view.getWidth(),height=view.getHeight();
                    view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
                    view.layout(0,0,width,height);
                    Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
                    view.draw(new Canvas(bitmap));
                    File file=new File(activity.getExternalFilesDir(null),"ui-screen-"+selected+".png");
                    try(FileOutputStream output=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
                    catch(Exception failure){throw new AssertionError(failure);}finally{bitmap.recycle();}
                });
            }
        } finally { getInstrumentation().runOnMainSync(activity::finish); }
    }
}
