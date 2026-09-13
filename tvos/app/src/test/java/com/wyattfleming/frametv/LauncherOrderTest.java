package com.wyattfleming.frametv;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class LauncherOrderTest {
    @Test
    public void favoritePlexComesFirstThenOtherFavoritesThenStableRemainder() {
        List<String> packages = Arrays.asList("com.netflix.ninja", "com.plexapp.android",
                "com.spotify.tv.android", "com.netflix.ninja", "com.example.other");
        Set<String> favorites = new HashSet<>(Arrays.asList("com.spotify.tv.android",
                "com.plexapp.android"));

        assertEquals(Arrays.asList("com.plexapp.android", "com.spotify.tv.android",
                "com.netflix.ninja", "com.example.other"), LauncherOrder.order(packages, favorites));
    }

    @Test
    public void plexIsNotPromotedUnlessFavorited() {
        assertEquals(Arrays.asList("com.netflix.ninja", "com.plexapp.android"),
                LauncherOrder.order(Arrays.asList("com.netflix.ninja", "com.plexapp.android"),
                        Collections.singleton("com.netflix.ninja")));
    }

    @Test
    public void absentFavoritesAreNotFabricated() {
        assertEquals(Arrays.asList("com.netflix.ninja", "com.spotify.tv.android"),
                LauncherOrder.order(Arrays.asList("com.netflix.ninja", "com.spotify.tv.android"),
                        new HashSet<>(Arrays.asList("com.plexapp.android", "com.example.missing"))));
    }

    @Test
    public void nullAndDuplicatePackagesAreIgnoredAndNullFavoritesAreSafe() {
        assertEquals(Arrays.asList("com.spotify.tv.android", "com.netflix.ninja"),
                LauncherOrder.order(Arrays.asList(null, "com.netflix.ninja", "com.spotify.tv.android",
                        null, "com.netflix.ninja"), Collections.singleton("com.spotify.tv.android")));
        assertEquals(Arrays.asList("com.netflix.ninja", "com.spotify.tv.android"),
                LauncherOrder.order(Arrays.asList("com.netflix.ninja", "com.spotify.tv.android"), null));
        assertEquals(Collections.emptyList(), LauncherOrder.order(null, null));
    }
}
