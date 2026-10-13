package com.wyattfleming.frametv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
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
            return new Intent(Intent.ACTION_MAIN).setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
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
            List<App> found;
            try {
                found = discover();
            } catch (RuntimeException ignored) {
                found = Collections.emptyList();
            }
            final List<App> apps = found;
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
        List<ResolveInfo> activities;
        try {
            activities = pm.queryIntentActivities(intent, 0);
        } catch (RuntimeException ignored) {
            return;
        }
        for (ResolveInfo resolve : activities) {
            try {
                ActivityInfo info = resolve.activityInfo;
                if (info == null || appContext.getPackageName().equals(info.packageName)) continue;
                if (unique.containsKey(info.packageName) || unique.size() >= MAX_APPS) continue;
                CharSequence title = resolve.loadLabel(pm);
                String label = title == null || title.length() == 0 ? info.packageName : title.toString();
                // Icon loading is PackageManager work. Keep it off-main and retain small, fixed-size art.
                Drawable icon = unique.size() < MAX_ICON_CACHE ? smallIcon(resolve.loadIcon(pm)) : null;
                unique.put(info.packageName, new App(info.packageName, label, icon,
                        new ComponentName(info.packageName, info.name)));
            } catch (RuntimeException ignored) {
                // A removed or malformed package must not hide the remaining catalog.
            }
        }
    }

    private Drawable smallIcon(Drawable source) {
        if (source == null) return null;
        int size = Math.max(1, Math.round(30 * appContext.getResources().getDisplayMetrics().density));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int width = Math.max(1, source.getIntrinsicWidth());
        int height = Math.max(1, source.getIntrinsicHeight());
        float scale = Math.min((float) size / width, (float) size / height);
        int drawWidth = Math.max(1, Math.round(width * scale));
        int drawHeight = Math.max(1, Math.round(height * scale));
        int left = (size - drawWidth) / 2;
        int top = (size - drawHeight) / 2;
        source.setBounds(new Rect(left, top, left + drawWidth, top + drawHeight));
        source.draw(canvas);
        return new BitmapDrawable(appContext.getResources(), bitmap);
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
