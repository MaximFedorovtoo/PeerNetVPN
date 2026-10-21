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
