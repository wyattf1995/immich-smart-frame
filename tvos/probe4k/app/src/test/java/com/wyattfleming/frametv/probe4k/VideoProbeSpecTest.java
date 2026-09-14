package com.wyattfleming.frametv.probe4k;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class VideoProbeSpecTest {
    @Test
    public void exact4kVisibleDimensionsAreRequired() {
        VideoProbeSpec.requireExpectedDimensions(3840, 2160);
        expectIllegalArgument(() -> VideoProbeSpec.requireExpectedDimensions(1920, 1080));
        expectIllegalArgument(() -> VideoProbeSpec.requireExpectedDimensions(3840, 2159));
    }

    @Test
    public void cropDefaultsAndExplicitValuesProduceInclusiveVisibleDimensions() {
        assertEquals(0, VideoProbeSpec.cropStartOrDefault(null, 3840));
        assertEquals(3839, VideoProbeSpec.cropEndOrDefault(null, 3840));
        assertEquals(12, VideoProbeSpec.cropSize(10, 21));
        assertEquals(3840, VideoProbeSpec.cropSize(0, 3839));
        VideoProbeSpec.requireExpectedDimensions(
                VideoProbeSpec.cropSize(0, 3839), VideoProbeSpec.cropSize(0, 2159));
        expectIllegalArgument(() -> VideoProbeSpec.requireExpectedDimensions(
                VideoProbeSpec.cropSize(0, 3838), VideoProbeSpec.cropSize(0, 2159)));
    }

    @Test
    public void invalidCropCoordinatesAreRejected() {
        expectIllegalArgument(() -> VideoProbeSpec.cropStartOrDefault(-1, 2160));
        expectIllegalArgument(() -> VideoProbeSpec.cropEndOrDefault(2160, 2160));
        expectIllegalArgument(() -> VideoProbeSpec.cropSize(9, 8));
    }

    @Test
    public void decodeBudgetIsFiniteAndSizedForTheStaticClip() {
        assertEquals(8, VideoProbeSpec.MAX_INPUT_SAMPLES);
        assertEquals(120, VideoProbeSpec.MAX_DEQUEUE_ATTEMPTS);
        assertEquals(10_000L, VideoProbeSpec.DEQUEUE_TIMEOUT_US);
    }

    private static void expectIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
