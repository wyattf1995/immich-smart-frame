package com.wyattfleming.frametv;

/** A user-confirmed geocoding result. No household location is bundled with the app. */
public final class WeatherLocation {
    public final String label;
    public final double latitude;
    public final double longitude;
    public WeatherLocation(String label, double latitude, double longitude) {
        this.label = label == null ? "" : label;
        this.latitude = latitude;
        this.longitude = longitude;
    }
    public boolean valid() {
        return !label.isEmpty() && !Double.isNaN(latitude) && !Double.isNaN(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }
    public String sourceKey() { return latitude + "," + longitude; }
}
