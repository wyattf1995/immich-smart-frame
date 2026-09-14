package com.wyattfleming.frametv.probe4k;

/** Fixed, bounded contract for the opaque-video diagnostic. */
final class VideoProbeSpec {
    static final int WIDTH = 3840;
    static final int HEIGHT = 2160;
    static final int MAX_INPUT_SAMPLES = 8;
    static final int MAX_DEQUEUE_ATTEMPTS = 120;
    static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private VideoProbeSpec() { }

    static void requireExpectedDimensions(int width, int height) {
        if (width != WIDTH || height != HEIGHT) {
            throw new IllegalArgumentException("expected 3840x2160 input, got " + width + "x" + height);
        }
    }

    static int cropEndOrDefault(Integer end, int dimension) {
        if (end == null) return dimension - 1;
        if (end < 0 || end >= dimension) {
            throw new IllegalArgumentException("crop end outside dimension");
        }
        return end;
    }

    static int cropStartOrDefault(Integer start, int dimension) {
        if (start == null) return 0;
        if (start < 0 || start >= dimension) {
            throw new IllegalArgumentException("crop start outside dimension");
        }
        return start;
    }

    static int cropSize(int start, int end) {
        if (end < start) throw new IllegalArgumentException("crop end precedes crop start");
        return end - start + 1;
    }
}
