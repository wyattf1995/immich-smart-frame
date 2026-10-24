package com.wyattfleming.frametv;

public enum Feature {
    PHOTOS("Photos", "Your collection"),
    HOME("Home", "Home controls"),
    WEATHER("Weather", "Your forecast"),
    OCEAN("Ocean", "Main Beach", false),
    CAMERAS("Cameras", "Around home"),
    CALENDAR("Calendar", "Coming up"),
    BIRDS("Birds", "Recent visitors");

    public final String title;
    public final String description;
    /** False for a native page that needs no dashboard address, so setup never asks for one. */
    public final boolean addressed;
    Feature(String title, String description) {
        this(title, description, true);
    }
    Feature(String title, String description, boolean addressed) {
        this.title = title;
        this.description = description;
        this.addressed = addressed;
    }
}
