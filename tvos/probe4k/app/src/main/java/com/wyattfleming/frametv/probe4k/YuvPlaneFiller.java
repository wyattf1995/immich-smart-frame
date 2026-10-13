package com.wyattfleming.frametv.probe4k;

import java.nio.ByteBuffer;

/** Bounded, framework-free YUV plane filling used by the one-frame composition probe. */
final class YuvPlaneFiller {
    static final int MAX_WIDTH = 3840;
    static final int MAX_HEIGHT = 2160;
    static final byte DARK = 52;
    static final byte LIGHT = (byte) 178;
    static final byte REFERENCE_GRAY = (byte) 108;
    static final byte NEUTRAL_CHROMA = (byte) 128;

    private YuvPlaneFiller() { }

    static void validateImageShape(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_WIDTH || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("unsupported image shape " + width + "x" + height);
        }
    }

    interface Cancellation { boolean isCancelled(); }

    static void fillLuma(ByteBuffer plane, int width, int height, int rowStride, int pixelStride,
                         boolean alternatePattern) {
        fillLuma(plane, width, height, rowStride, pixelStride, alternatePattern, () -> false);
    }

    static boolean fillLuma(ByteBuffer plane, int width, int height, int rowStride, int pixelStride,
                            boolean alternatePattern, Cancellation cancellation) {
        validatePlane(plane, width, height, rowStride, pixelStride);
        int base = plane.position();
        for (int y = 0; y < height; y++) {
            if (cancellation.isCancelled()) return false;
            for (int x = 0; x < width; x++) {
                plane.put(base + y * rowStride + x * pixelStride, lumaAt(x, y, width, height, alternatePattern));
            }
        }
        return true;
    }

    static void fillNeutralChroma(ByteBuffer plane, int width, int height, int rowStride, int pixelStride) {
        fillNeutralChroma(plane, width, height, rowStride, pixelStride, () -> false);
    }

    static boolean fillNeutralChroma(ByteBuffer plane, int width, int height, int rowStride, int pixelStride,
                                     Cancellation cancellation) {
        validatePlane(plane, width, height, rowStride, pixelStride);
        int base = plane.position();
        for (int y = 0; y < height; y++) {
            if (cancellation.isCancelled()) return false;
            for (int x = 0; x < width; x++) {
                plane.put(base + y * rowStride + x * pixelStride, NEUTRAL_CHROMA);
            }
        }
        return true;
    }

    static byte lumaAt(int x, int y, int width, int height, boolean alternatePattern) {
        validateImageShape(width, height);
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException("coordinate outside image");
        }
        int row = Math.min(3, y * 4 / height);
        int column = Math.min(3, x * 4 / width);
        int offset = alternatePattern ? 1 : 0;
        if (row < 3) {
            int stripeWidth = row == 0 ? 1 : (row == 1 ? 2 : 4);
            int coordinate = column < 2 ? x : y;
            return alternating(coordinate + offset, stripeWidth);
        }
        if (column == 0) return REFERENCE_GRAY;
        int cell = column == 1 ? 1 : (column == 2 ? 2 : 4);
        return (((x + offset) / cell + (y + offset) / cell) & 1) == 0 ? LIGHT : DARK;
    }

    static void validatePlane(ByteBuffer plane, int width, int height, int rowStride, int pixelStride) {
        validateImageShape(width, height);
        if (plane == null || rowStride < 1 || pixelStride < 1) {
            throw new IllegalArgumentException("invalid plane layout");
        }
        long minimumRowStride = (long) (width - 1) * pixelStride + 1;
        if (rowStride < minimumRowStride) {
            throw new IllegalArgumentException("row stride overlaps a row's samples");
        }
        long lastOffset = (long) (height - 1) * rowStride + (long) (width - 1) * pixelStride;
        long lastIndex = (long) plane.position() + lastOffset;
        if (lastIndex < plane.position() || lastIndex >= plane.limit()) {
            throw new IllegalArgumentException("plane buffer is too small for layout");
        }
    }

    private static byte alternating(int coordinate, int width) {
        return (coordinate / width & 1) == 0 ? LIGHT : DARK;
    }
}
