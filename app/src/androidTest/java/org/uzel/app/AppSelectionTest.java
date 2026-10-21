package org.uzel.app;

import android.content.Context;
import android.test.InstrumentationTestCase;
import java.util.HashSet;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Selection validation only; does not start a VPN or enable network sharing. */
@SuppressWarnings("deprecation")
public final class AppSelectionTest extends InstrumentationTestCase {
    private Context context;
    private Set<String> previous;
    private boolean existed;

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        existed = context.getSharedPreferences("vpn_apps", Context.MODE_PRIVATE).contains("selected");
        previous = new HashSet<>(context.getSharedPreferences("vpn_apps", Context.MODE_PRIVATE)
                .getStringSet("selected", Collections.emptySet()));
        context.getSharedPreferences("vpn_apps", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Override protected void tearDown() throws Exception {
        if (existed) context.getSharedPreferences("vpn_apps", Context.MODE_PRIVATE).edit()
                .putStringSet("selected", previous).commit();
        else context.getSharedPreferences("vpn_apps", Context.MODE_PRIVATE).edit().remove("selected").commit();
        super.tearDown();
    }

    public void testDefaultAndExplicitEmptyNeverMeanAllApplications() {
        assertTrue(AppSelection.load(context).isEmpty());
        assertTrue(AppSelection.validPackages(context).isEmpty());
        AppSelection.save(context, Collections.emptySet());
        assertTrue(AppSelection.validPackages(context).isEmpty());
    }

    public void testOwnAndUninstalledPackagesCannotBeSaved() {
        AppSelection.save(context, new HashSet<>(Arrays.asList(context.getPackageName(), "org.uzel.missing.fixture")));
        assertTrue(AppSelection.load(context).isEmpty());
        assertTrue(AppSelection.validPackages(context).isEmpty());
    }

    public void testInvalidPersistedPackagesCannotReachVpnBuilder() {
        context.getSharedPreferences("vpn_apps", Context.MODE_PRIVATE).edit().putStringSet("selected",
                new HashSet<>(Arrays.asList(context.getPackageName(), "org.uzel.missing.fixture"))).commit();
        assertTrue(AppSelection.validPackages(context).isEmpty());
    }

    public void testCandidatesAreUniqueAndExcludeOwnApp() {
        Set<String> seen = new HashSet<>();
        for (AppSelection.App app : AppSelection.candidateApps(context)) {
            assertFalse(context.getPackageName().equals(app.packageName));
            assertTrue(seen.add(app.packageName));
            assertNotNull(app.label);
            assertFalse(app.label.isEmpty());
        }
    }

    public void testSavedSelectionIsExplicitAndDefensive() {
        List<AppSelection.App> candidates = AppSelection.candidateApps(context);
        if (candidates.isEmpty()) {
            AppSelection.save(context, Collections.emptySet());
            assertTrue(AppSelection.validPackages(context).isEmpty());
            return;
        }
        String selected = candidates.get(0).packageName;
        Set<String> input = new HashSet<>(); input.add(selected);
        AppSelection.save(context, input); input.clear();
        assertEquals(Collections.singleton(selected), AppSelection.load(context));
        assertEquals(Collections.singletonList(selected), AppSelection.validPackages(context));
        Set<String> loaded = AppSelection.load(context);
        loaded.clear();
        assertEquals(Collections.singleton(selected), AppSelection.load(context));
    }
}
