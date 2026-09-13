package com.wyattfleming.frametv;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class TvPreferences {
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
        return new HashSet<>(preferences.getStringSet("favorites", Collections.singleton("com.plexapp.android")));
    }
    public void toggleFavorite(String packageName) {
        Set<String> favorites = favorites();
        if (!favorites.remove(packageName)) favorites.add(packageName);
        preferences.edit().putStringSet("favorites", favorites).apply();
    }
}
