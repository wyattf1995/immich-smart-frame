package com.wyattfleming.frametv;

public enum Feature {
    PHOTOS("Photos", "Your collection"),
    HOME("Home", "Home controls"),
    WEATHER("Weather", "Your forecast"),
    CAMERAS("Cameras", "Around home"),
    CALENDAR("Calendar", "Coming up");

    public final String title;
    public final String description;
    Feature(String title, String description) {
        this.title = title;
        this.description = description;
    }
}
