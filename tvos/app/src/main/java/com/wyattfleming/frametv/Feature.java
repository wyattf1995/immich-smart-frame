package com.wyattfleming.frametv;

public enum Feature {
    PHOTOS("Photos", "Your photo collection"),
    HOME("Home", "At a glance and home controls"),
    WEATHER("Weather", "Conditions and forecast"),
    CAMERAS("Cameras", "See around home"),
    CALENDAR("Calendar", "What's coming up");

    public final String title;
    public final String description;
    Feature(String title, String description) {
        this.title = title;
        this.description = description;
    }
}
