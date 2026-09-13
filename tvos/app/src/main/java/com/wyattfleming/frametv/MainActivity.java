package com.wyattfleming.frametv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.media.tv.TvContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TextClock;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A light HOME activity: app discovery is bounded and dashboards are opened only on request. */
public final class MainActivity extends Activity {
    private AppCatalog catalog;
    private TvPreferences preferences;
    private final List<AppCatalog.App> apps = new ArrayList<>();
    private final Map<String, View> focusTargets = new LinkedHashMap<>();
    private String focusId;
    private LinearLayout content;
    private TextClock clock;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.window(this);
        preferences = new TvPreferences(this);
        catalog = new AppCatalog(this);
        buildShell();
        catalog.load(loaded -> {
            apps.clear();
            apps.addAll(loaded);
            render();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    @Override protected void onPause() {
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) focusId = (String) focused.getTag();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (catalog != null) catalog.close();
        super.onDestroy();
    }

    private void buildShell() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(TvUi.dp(this, 58), TvUi.dp(this, 42), TvUi.dp(this, 58), TvUi.dp(this, 54));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        render();
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        focusTargets.clear();

        LinearLayout masthead = new LinearLayout(this);
        masthead.setGravity(Gravity.CENTER_VERTICAL);
        masthead.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = TvUi.text(this, "Frame TV", 32, TvUi.TEXT);
        title.setContentDescription("Frame TV home");
        masthead.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        clock = new TextClock(this);
        clock.setTextSize(19);
        clock.setTextColor(TvUi.MUTED);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setContentDescription("Current time");
        masthead.addView(clock);
        content.addView(masthead);

        List<AppCatalog.App> ordered = orderedApps();
        addAppsRow("Favorites", orderedFavorites(ordered), true);
        addAppsRow("All apps", ordered, false);
        addDashboards();
        addInputs();
        addUtilities();
        content.post(this::restoreFocus);
    }

    private List<AppCatalog.App> orderedApps() {
        Map<String, AppCatalog.App> byPackage = new HashMap<>();
        List<String> packages = new ArrayList<>();
        for (AppCatalog.App app : apps) {
            packages.add(app.packageName);
            byPackage.put(app.packageName, app);
        }
        List<AppCatalog.App> result = new ArrayList<>();
        for (String packageName : LauncherOrder.order(packages, preferences.favorites())) {
            AppCatalog.App app = byPackage.get(packageName);
            if (app != null) result.add(app);
        }
        return result;
    }

    private List<AppCatalog.App> orderedFavorites(List<AppCatalog.App> ordered) {
        Set<String> favorites = preferences.favorites();
        List<AppCatalog.App> result = new ArrayList<>();
        for (AppCatalog.App app : ordered) if (favorites.contains(app.packageName)) result.add(app);
        return result;
    }

    private void addAppsRow(String heading, List<AppCatalog.App> row, boolean favorites) {
        addHeading(heading);
        if (row.isEmpty()) {
            TextView empty = TvUi.text(this, favorites ? "Hold Select on an app to add it here." : "Looking for installed apps…", 16, TvUi.MUTED);
            empty.setPadding(0, 0, 0, TvUi.dp(this, 20));
            content.addView(empty);
            return;
        }
        HorizontalScrollView scroll = horizontalRow();
        LinearLayout strip = rowContainer();
        for (AppCatalog.App app : row) {
            String id = (favorites ? "favorite:" : "all:") + app.packageName;
            android.widget.Button tile = TvUi.button(this, app.label, () -> launch(app));
            if (app.icon != null) {
                tile.setCompoundDrawablesWithIntrinsicBounds(app.icon, null, null, null);
                tile.setCompoundDrawablePadding(TvUi.dp(this, 10));
            }
            tile.setTag(id);
            tile.setOnLongClickListener(view -> {
                boolean wasFavorite = preferences.favorites().contains(app.packageName);
                preferences.toggleFavorite(app.packageName);
                focusId = (wasFavorite ? "all:" : "favorite:") + app.packageName;
                render();
                return true;
            });
            tile.setContentDescription(app.label + ". Press Select to open. Hold Select to "
                    + (preferences.favorites().contains(app.packageName) ? "remove favorite" : "add favorite"));
            addTile(strip, tile, id);
        }
        scroll.addView(strip);
        content.addView(scroll);
    }

    private void addDashboards() {
        addHeading("Home");
        HorizontalScrollView scroll = horizontalRow();
        LinearLayout strip = rowContainer();
        for (Feature feature : Feature.values()) {
            String id = "feature:" + feature.name();
            boolean configured = preferences.configured(feature);
            String detail = configured ? feature.description : "Set up " + feature.title;
            android.widget.Button tile = TvUi.button(this, feature.title + "\n" + detail,
                    () -> openFeature(feature));
            tile.setContentDescription(feature.title + ". " + detail);
            tile.setTag(id);
            addTile(strip, tile, id);
        }
        scroll.addView(strip);
        content.addView(scroll);
    }

    private void addInputs() {
        TvInputManager manager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (manager == null) return;
        List<TvInputInfo> inputs;
        try {
            inputs = manager.getTvInputList();
        } catch (SecurityException ignored) {
            return;
        }
        LinearLayout strip = rowContainer();
        for (TvInputInfo input : inputs) {
            if (input.getType() != TvInputInfo.TYPE_HDMI) continue;
            CharSequence inputLabel = input.loadLabel(getPackageManager());
            String label = inputLabel == null ? "HDMI input" : inputLabel.toString();
            String id = "input:" + input.getId();
            android.widget.Button tile = TvUi.button(this, label, () -> openInput(input));
            tile.setTag(id);
            tile.setContentDescription("Input " + label);
            addTile(strip, tile, id);
        }
        if (strip.getChildCount() == 0) return;
        addHeading("Inputs");
        HorizontalScrollView scroll = horizontalRow();
        scroll.addView(strip);
        content.addView(scroll);
    }

    private void addUtilities() {
        addHeading("System");
        HorizontalScrollView scroll = horizontalRow();
        LinearLayout strip = rowContainer();
        addActionTile(strip, "Configure", "utility:configure", this::configure);
        addActionTile(strip, "Refresh apps", "utility:refresh", this::refreshApps);
        addActionTile(strip, "TV settings", "utility:settings", this::openSettings);
        addActionTile(strip, "Sony Home", "utility:sony-home", this::openOtherHome);
        scroll.addView(strip);
        content.addView(scroll);
    }

    private void addActionTile(LinearLayout strip, String label, String id, Runnable action) {
        android.widget.Button tile = TvUi.button(this, label, action);
        tile.setTag(id);
        addTile(strip, tile, id);
    }

    private void addHeading(String label) {
        TextView heading = TvUi.text(this, label, 19, TvUi.MUTED);
        heading.setPadding(0, TvUi.dp(this, 34), 0, TvUi.dp(this, 12));
        content.addView(heading);
    }

    private HorizontalScrollView horizontalRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        return scroll;
    }

    private LinearLayout rowContainer() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private void addTile(LinearLayout row, View tile, String id) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(TvUi.dp(this, 220), TvUi.dp(this, 92));
        params.setMargins(0, 0, TvUi.dp(this, 16), 0);
        row.addView(tile, params);
        focusTargets.put(id, tile);
    }

    private void launch(AppCatalog.App app) {
        try {
            startActivity(app.launchIntent());
        } catch (RuntimeException error) {
            Toast.makeText(this, "Couldn’t open " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openFeature(Feature feature) {
        if (!preferences.configured(feature)) {
            configure();
            return;
        }
        startActivity(new Intent(this, DashboardActivity.class).putExtra("feature", feature.name()));
    }

    private void refreshApps() {
        catalog.load(loaded -> {
            apps.clear();
            apps.addAll(loaded);
            render();
        });
    }

    private void openInput(TvInputInfo input) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(input.getId());
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(this, "This input isn’t available right now", Toast.LENGTH_SHORT).show();
        }
    }

    private void configure() {
        try {
            startActivity(new Intent(this, SetupActivity.class));
        } catch (RuntimeException error) {
            Toast.makeText(this, "Configuration isn’t available yet", Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException error) {
            Toast.makeText(this, "TV settings aren’t available", Toast.LENGTH_SHORT).show();
        }
    }

    private void openOtherHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        for (ResolveInfo resolve : getPackageManager().queryIntentActivities(home, 0)) {
            if (resolve.activityInfo == null || getPackageName().equals(resolve.activityInfo.packageName)) continue;
            try {
                home.setComponent(new ComponentName(resolve.activityInfo.packageName, resolve.activityInfo.name));
                startActivity(home);
                return;
            } catch (RuntimeException ignored) {
                // Try the next available launcher rather than trapping the user here.
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (RuntimeException error) {
            Toast.makeText(this, "Choose Sony Home in TV settings", Toast.LENGTH_LONG).show();
        }
    }

    private void restoreFocus() {
        View candidate = focusId == null ? null : focusTargets.get(focusId);
        if (candidate == null && !focusTargets.isEmpty()) candidate = focusTargets.values().iterator().next();
        if (candidate != null) candidate.requestFocus();
    }
}
