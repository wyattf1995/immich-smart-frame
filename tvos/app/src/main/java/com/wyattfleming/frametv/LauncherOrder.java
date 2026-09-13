package com.wyattfleming.frametv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps launcher presentation predictable without storing a second app list. */
public final class LauncherOrder {
    private static final String PLEX = "com.plexapp.android";

    private LauncherOrder() {}

    public static List<String> order(List<String> packages, Set<String> favorites) {
        List<String> result = new ArrayList<>();
        if (packages == null || packages.isEmpty()) return result;

        Set<String> seen = new HashSet<>();
        Set<String> pins = favorites == null ? new HashSet<String>() : favorites;

        // Plex is promoted only when installed and favorited; it is never invented.
        for (String packageName : packages) {
            if (PLEX.equals(packageName) && pins.contains(packageName) && seen.add(packageName)) {
                result.add(packageName);
                break;
            }
        }
        for (String packageName : packages) {
            if (packageName != null && pins.contains(packageName) && seen.add(packageName)) {
                result.add(packageName);
            }
        }
        for (String packageName : packages) {
            if (packageName != null && seen.add(packageName)) result.add(packageName);
        }
        return result;
    }
}
