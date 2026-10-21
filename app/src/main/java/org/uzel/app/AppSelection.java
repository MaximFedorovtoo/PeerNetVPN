package org.uzel.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Explicit VPN allowlist. An absent or empty selection always means no VPN. */
public final class AppSelection {
    private AppSelection() {}

    public static final class App {
        public final String packageName;
        public final String label;
        private App(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    public static synchronized Set<String> load(Context context) {
        return new HashSet<>(context.getSharedPreferences("vpn_apps", Context.MODE_PRIVATE)
                .getStringSet("selected", Collections.emptySet()));
    }

    public static synchronized void save(Context context, Set<String> packages) {
        Set<String> allowed = new HashSet<>();
        for (App app : candidateApps(context)) {
            if (packages != null && packages.contains(app.packageName)) allowed.add(app.packageName);
        }
        context.getSharedPreferences("vpn_apps", Context.MODE_PRIVATE).edit()
                .putStringSet("selected", allowed).apply();
    }

    /** Revalidates launchability, installation, internet access and own-app exclusion. */
    public static synchronized List<String> validPackages(Context context) {
        Set<String> selected = load(context);
        List<String> valid = new ArrayList<>();
        for (App app : candidateApps(context)) {
            if (selected.contains(app.packageName)) valid.add(app.packageName);
        }
        // Package order is independent of translated app labels and locale.
        valid.sort(String::compareTo);
        return valid;
    }

    @SuppressWarnings("deprecation")
    public static List<App> candidateApps(Context context) {
        PackageManager manager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        LinkedHashMap<String, App> unique = new LinkedHashMap<>();
        for (ResolveInfo resolved : manager.queryIntentActivities(launcher, 0)) {
            if (resolved.activityInfo == null || resolved.activityInfo.applicationInfo == null) continue;
            String name = resolved.activityInfo.packageName;
            if (context.getPackageName().equals(name) || !resolved.activityInfo.enabled
                    || !resolved.activityInfo.applicationInfo.enabled
                    || manager.checkPermission(Manifest.permission.INTERNET, name) != PackageManager.PERMISSION_GRANTED) continue;
            CharSequence label = resolved.activityInfo.applicationInfo.loadLabel(manager);
            unique.put(name, new App(name, label == null || label.length() == 0 ? name : label.toString()));
        }
        List<App> apps = new ArrayList<>(unique.values());
        Collator collator = Collator.getInstance(context.getResources().getConfiguration().getLocales().get(0));
        apps.sort((left, right) -> {
            int order = collator.compare(left.label, right.label);
            return order == 0 ? left.packageName.compareTo(right.packageName) : order;
        });
        return apps;
    }
}
