package com.wyattfleming.frametv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Discovers only launchable TV applications. Discovery runs once on a single background thread. */
public final class AppCatalog implements AutoCloseable {
    private static final int MAX_APPS = 120;
    private static final int MAX_ICON_CACHE = 36;

    public interface Listener {
        void onAppsLoaded(List<App> apps);
    }

    public static final class App {
        public final String packageName;
        public final String label;
        public final Drawable icon;
        private final ComponentName component;

        App(String packageName, String label, Drawable icon, ComponentName component) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.component = component;
        }

        public Intent launchIntent() {
            return new Intent(Intent.ACTION_MAIN).setComponent(component);
        }
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    public AppCatalog(Context context) {
        appContext = context.getApplicationContext();
    }

    public void load(Listener listener) {
        worker.execute(() -> {
            final List<App> apps = discover();
            main.post(() -> {
                if (!closed) listener.onAppsLoaded(apps);
            });
        });
    }

    private List<App> discover() {
        PackageManager pm = appContext.getPackageManager();
        LinkedHashMap<String, App> unique = new LinkedHashMap<>();
        collect(pm, tvIntent(), unique);
        // Older Android TV applications occasionally declare only the phone launcher category.
        collect(pm, phoneIntent(), unique);
        List<App> apps = new ArrayList<>(unique.values());
        Collections.sort(apps, Comparator.comparing(app -> app.label, String.CASE_INSENSITIVE_ORDER));
        return apps.size() > MAX_APPS ? new ArrayList<>(apps.subList(0, MAX_APPS)) : apps;
    }

    private Intent tvIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
    }

    private Intent phoneIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    }

    private void collect(PackageManager pm, Intent intent, Map<String, App> unique) {
        for (ResolveInfo resolve : pm.queryIntentActivities(intent, 0)) {
            ActivityInfo info = resolve.activityInfo;
            if (info == null || appContext.getPackageName().equals(info.packageName)) continue;
            if (unique.containsKey(info.packageName) || unique.size() >= MAX_APPS) continue;
            CharSequence title = resolve.loadLabel(pm);
            String label = title == null || title.length() == 0 ? info.packageName : title.toString();
            // Icon loading is also PackageManager work: keep it on this worker and retain only
            // the first visible cache window. Text remains an accessible fallback for the rest.
            Drawable icon = unique.size() < MAX_ICON_CACHE ? resolve.loadIcon(pm) : null;
            unique.put(info.packageName, new App(info.packageName, label, icon,
                    new ComponentName(info.packageName, info.name)));
        }
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
