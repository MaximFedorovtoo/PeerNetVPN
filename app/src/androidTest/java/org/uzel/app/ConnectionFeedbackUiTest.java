package org.uzel.app;

import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.widget.*;
import java.lang.reflect.*;

/** Synthetic snapshots only: never enables sharing, pairing or a system route. */
@SuppressWarnings("deprecation")
public final class ConnectionFeedbackUiTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public ConnectionFeedbackUiTest(){super(MainActivity.class);}
    public void testOutgoingFeedbackTracksConnectingReadyFailureAndCancellation()throws Throwable {
        MainActivity activity=getActivity();
        runTestOnUiThread(()->{try {
            Method render=MainActivity.class.getDeclaredMethod("renderSnapshot",NodeController.Snapshot.class);render.setAccessible(true);
            Field title=MainActivity.class.getDeclaredField("status");title.setAccessible(true);
            Field progress=MainActivity.class.getDeclaredField("connectionProgress");progress.setAccessible(true);
            Field button=MainActivity.class.getDeclaredField("vpnButton");button.setAccessible(true);
            NodeController.Snapshot state=new NodeController.Snapshot();state.sharing=true;state.connecting=true;state.status="SHARING";
            render.invoke(activity,state);
            assertEquals(activity.getString(R.string.ui_connecting),((TextView)title.get(activity)).getText().toString());
            assertEquals(View.VISIBLE,((View)progress.get(activity)).getVisibility());
            assertTrue(((ProgressBar)progress.get(activity)).isIndeterminate());
            assertEquals(activity.getString(R.string.cancel),((Button)button.get(activity)).getText().toString());
            Field requested=MainActivity.class.getDeclaredField("connectionRequested");requested.setAccessible(true);requested.set(activity,true);
            state.connecting=false;state.busy=true;state.status="MEASURING";render.invoke(activity,state);
            assertEquals("Calibration during an outgoing attempt remains connecting",activity.getString(R.string.ui_connecting),((TextView)title.get(activity)).getText().toString());
            assertTrue(((Button)button.get(activity)).isEnabled());
            state.busy=false;
            state.connecting=false;state.vpnConnected=true;state.status="CONNECTED";render.invoke(activity,state);
            assertEquals(activity.getString(R.string.ui_connected),((TextView)title.get(activity)).getText().toString());
            assertEquals(View.GONE,((View)progress.get(activity)).getVisibility());
            state.vpnConnected=false;state.status="UNREACHABLE";render.invoke(activity,state);
            assertEquals(activity.getString(R.string.ui_connection_failed),((TextView)title.get(activity)).getText().toString());
            state.status="SHARING";render.invoke(activity,state);
            assertEquals(activity.getString(R.string.ui_disconnected),((TextView)title.get(activity)).getText().toString());
            assertTrue("Rendering leaves sharing state intact",state.sharing);
        }catch(Exception e){throw new RuntimeException(e);}});
    }
}
