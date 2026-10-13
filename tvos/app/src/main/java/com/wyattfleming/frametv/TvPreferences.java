package com.wyattfleming.frametv;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class TvPreferences {
    // Only discovered, installed applications are displayed; this is a starting set, not an installer.
    private static final Set<String> DEFAULT_FAVORITES = new HashSet<>(Arrays.asList(
            "com.plexapp.android", "com.netflix.ninja", "com.wbd.stream",
            "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
            "com.hulu.livingroomplus", "com.apple.atve.androidtv.appletv",
            "com.google.android.youtube.tv", "com.peacocktv.peacockandroid", "com.cbs.ott"));
    private final SharedPreferences preferences;
    public TvPreferences(Context context) {
        preferences = context.getSharedPreferences("frame_tv", Context.MODE_PRIVATE);
    }
    public String url(Feature feature) {
        try { return UrlPolicy.validate(preferences.getString("url_" + feature.name(), "")); }
        catch (IllegalArgumentException e) { return ""; }
    }
    public boolean configured(Feature feature) { return !url(feature).isEmpty(); }
    public void saveUrls(java.util.Map<Feature, String> values) {
        java.util.Map<Feature, String> checked = new java.util.EnumMap<>(Feature.class);
        for (java.util.Map.Entry<Feature, String> entry : values.entrySet()) {
            checked.put(entry.getKey(), UrlPolicy.validate(entry.getValue()));
        }
        SharedPreferences.Editor editor = preferences.edit();
        for (java.util.Map.Entry<Feature, String> entry : checked.entrySet()) {
            editor.putString("url_" + entry.getKey().name(), entry.getValue());
        }
        editor.apply();
    }
    public Set<String> favorites() {
        return new HashSet<>(preferences.getStringSet("favorites", DEFAULT_FAVORITES));
    }
    public void toggleFavorite(String packageName) {
        Set<String> favorites = favorites();
        if (!favorites.remove(packageName)) favorites.add(packageName);
        preferences.edit().putStringSet("favorites", favorites).apply();
    }
}
