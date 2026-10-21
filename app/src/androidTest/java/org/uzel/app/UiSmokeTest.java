package org.uzel.app;

import android.content.Context;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Smoke coverage for launch only: never starts sharing or requests a VPN. */
@SuppressWarnings("deprecation")
public final class UiSmokeTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public UiSmokeTest() {
        super(MainActivity.class);
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        setActivityInitialTouchMode(true);
        Context target = getInstrumentation().getTargetContext();
        // A clean install must remain inactive until the user explicitly opts in.
        NodeController.Snapshot state = NodeController.get(target).snapshot();
        assertFalse("Smoke test requires an inactive clean-install node", state.sharing);
        assertFalse("Smoke test requires no active VPN", state.vpnConnected);
    }

    public void testLaunchShowsMainScreenWithoutEnablingNetwork() throws Throwable {
        final MainActivity activity = getActivity();
        assertNotNull(activity);
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            View root = activity.findViewById(android.R.id.content);
            assertNotNull("Main content exists", root);
            assertTrue("Main screen has visible labels", containsVisibleText(root));
            NodeController.Snapshot state = NodeController.get(activity).snapshot();
            assertFalse("Launch must not start sharing", state.sharing);
            assertFalse("Launch must not connect VPN", state.vpnConnected);
            assertNotNull("Controller status is available", state.status);
        });
    }

    private static boolean containsVisibleText(View view) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof TextView && ((TextView) view).getText().length() > 0) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsVisibleText(group.getChildAt(i))) return true;
            }
        }
        return false;
    }
}
