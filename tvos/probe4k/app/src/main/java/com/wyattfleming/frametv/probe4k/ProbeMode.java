package com.wyattfleming.frametv.probe4k;

/** Fixed surface sizes used by the diagnostic. These are requested buffers, not panel claims. */
public enum ProbeMode {
    HD(1920, 1080, "1080p buffer"),
    UHD(3840, 2160, "4K buffer");

    public final int width;
    public final int height;
    public final String displayName;

    ProbeMode(int width, int height, String displayName) {
        this.width = width;
        this.height = height;
        this.displayName = displayName;
    }

    /** Keeps input policy independently testable without Android framework stubs. */
    public static ProbeMode fromRightSelection(boolean rightSelected) {
        return rightSelected ? UHD : HD;
    }
}
