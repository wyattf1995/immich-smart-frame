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
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A light HOME activity: app discovery is bounded and dashboards are opened only on request. */
public final class MainActivity extends Activity {
    private static final int FAVORITE_COLUMNS = 5;
    private AppCatalog catalog;
    private TvPreferences preferences;
    private final List<AppCatalog.App> apps = new ArrayList<>();
    private final Map<String, View> focusTargets = new LinkedHashMap<>();
    private String focusId;
    private LinearLayout content;
    private ScrollView scroll;
    private TextClock clock;
    private WeatherWidget weather;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.window(this);
        preferences = new TvPreferences(this);
        catalog = new AppCatalog(this);
        weather = new WeatherWidget(this);
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
        weather.resume();
    }

    @Override protected void onPause() {
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) focusId = (String) focused.getTag();
        weather.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (catalog != null) catalog.close();
        if (weather != null) weather.close();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        // A HOME activity must not reveal an old streaming task when Back is pressed at its root.
        if (isTaskRoot()) { focusId = null; restoreFocus(); scroll.scrollTo(0, 0); }
        else super.onBackPressed();
    }

    private void buildShell() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(TvUi.dp(this, 42), TvUi.dp(this, 20), TvUi.dp(this, 42), TvUi.dp(this, 36));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout home = new FrameLayout(this);
        home.setBackground(new HomeBackgroundDrawable(getResources()));
        home.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(home);
        render();
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        focusTargets.clear();

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        // Leave a scenic interval between the small forecast and the bottom app shelf.
        int heightDp = Math.round(getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().density);
        hero.setMinimumHeight(TvUi.dp(this, Math.max(175, heightDp - 300)));
        content.addView(hero);
        LinearLayout masthead = new LinearLayout(this);
        masthead.setGravity(Gravity.CENTER_VERTICAL);
        masthead.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = TvUi.text(this, "Frame TV", 26, TvUi.TEXT);
        title.setContentDescription("Frame TV home");
        masthead.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        clock = new TextClock(this);
        clock.setTextSize(17);
        clock.setTextColor(TvUi.MUTED);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setContentDescription("Current time");
        masthead.addView(clock);
        hero.addView(masthead);
        if (weather.getParent() instanceof ViewGroup) ((ViewGroup) weather.getParent()).removeView(weather);
        LinearLayout.LayoutParams forecastParams = new LinearLayout.LayoutParams(-1, -2);
        forecastParams.topMargin = TvUi.dp(this, 12);
        hero.addView(weather, forecastParams);

        List<AppCatalog.App> ordered = orderedApps();
        addDashboards();
        addAppsRow("Favorites", orderedFavorites(ordered), true);
        addInputs();
        addUtilities();
        addAppsRow("All apps", ordered, false);
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
        HorizontalScrollView scroll = favorites ? null : horizontalRow();
        LinearLayout strip = favorites ? null : rowContainer();
        int column = 0;
        for (AppCatalog.App app : row) {
            if (favorites && column == 0) {
                strip = rowContainer();
                LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, -2);
                line.bottomMargin = TvUi.dp(this, 8);
                content.addView(strip, line);
            }
            String id = (favorites ? "favorite:" : "all:") + app.packageName;
            android.widget.Button tile = TvUi.compactButton(this, app.label, () -> launch(app));
            if (app.icon != null) {
                tile.setCompoundDrawablesWithIntrinsicBounds(app.icon, null, null, null);
                tile.setCompoundDrawablePadding(TvUi.dp(this, 8));
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
            if (favorites) {
                addGridTile(strip, tile, id, column, FAVORITE_COLUMNS, 56);
                column = (column + 1) % FAVORITE_COLUMNS;
            } else addTile(strip, tile, id);
        }
        if (favorites) {
            // Empty cells keep a partially filled final row aligned with the favorites grid.
            while (column > 0 && column < FAVORITE_COLUMNS) {
                addGridTile(strip, new View(this), null, column++, FAVORITE_COLUMNS, 56);
            }
            for (int i = 0; i < row.size(); i++) {
                View tile = focusTargets.get("favorite:" + row.get(i).packageName);
                int up = FavoriteGridNavigation.verticalNeighbor(i, row.size(), FAVORITE_COLUMNS, -1);
                int down = FavoriteGridNavigation.verticalNeighbor(i, row.size(), FAVORITE_COLUMNS, 1);
                if (up >= 0) tile.setNextFocusUpId(focusTargets.get("favorite:" + row.get(up).packageName).getId());
                if (down >= 0) tile.setNextFocusDownId(focusTargets.get("favorite:" + row.get(down).packageName).getId());
            }
        } else {
            scroll.addView(strip);
            content.addView(scroll);
        }
    }

    private void addDashboards() {
        addHeading("Home");
        LinearLayout strip = rowContainer();
        int column = 0;
        for (Feature feature : Feature.values()) {
            String id = "feature:" + feature.name();
            boolean configured = preferences.configured(feature);
            String detail = configured ? feature.description : "Set up " + feature.title;
            android.widget.Button tile = TvUi.compactButton(this, feature.title + "\n" + detail,
                    () -> openFeature(feature));
            tile.setContentDescription(feature.title + ". " + detail);
            tile.setTag(id);
            addGridTile(strip, tile, id, column++, Feature.values().length, 56);
        }
        content.addView(strip);
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
            CharSequence inputLabel = input.loadLabel(this);
            String label = inputLabel == null ? "HDMI input" : inputLabel.toString();
            String id = "input:" + input.getId();
            android.widget.Button tile = TvUi.compactButton(this, label, () -> openInput(input));
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
        android.widget.Button tile = TvUi.compactButton(this, label, action);
        tile.setTag(id);
        addTile(strip, tile, id);
    }

    private void addHeading(String label) {
        TextView heading = TvUi.text(this, label, 16, TvUi.MUTED);
        heading.setPadding(0, TvUi.dp(this, 14), 0, TvUi.dp(this, 7));
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(TvUi.dp(this, 156), TvUi.dp(this, 60));
        params.setMargins(0, 0, TvUi.dp(this, 10), 0);
        row.addView(tile, params);
        focusTargets.put(id, tile);
    }

    private void addGridTile(LinearLayout row, View tile, String id, int column, int columns, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, TvUi.dp(this, height), 1);
        params.setMargins(0, 0, column == columns - 1 ? 0 : TvUi.dp(this, 10), 0);
        row.addView(tile, params);
        if (id != null) {
            tile.setId(View.generateViewId());
            focusTargets.put(id, tile);
        }
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
        if (candidate == null) candidate = focusTargets.get("favorite:com.plexapp.android");
        if (candidate == null) {
            for (Map.Entry<String, View> entry : focusTargets.entrySet()) {
                if (entry.getKey().startsWith("favorite:")) { candidate = entry.getValue(); break; }
            }
        }
        if (candidate == null && !focusTargets.isEmpty()) candidate = focusTargets.values().iterator().next();
        if (candidate != null) candidate.requestFocus();
    }
}
