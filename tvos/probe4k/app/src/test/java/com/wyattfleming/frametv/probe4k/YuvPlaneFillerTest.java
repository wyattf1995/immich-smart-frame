package com.wyattfleming.frametv.probe4k;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;

import org.junit.Test;

public final class YuvPlaneFillerTest {
    @Test
    public void lumaFillRespectsPaddedRows() {
        ByteBuffer buffer = ByteBuffer.allocate(24);
        fill(buffer, (byte) 7);

        YuvPlaneFiller.fillLuma(buffer, 3, 3, 8, 1, false);

        assertNotEquals(7, buffer.get(0));
        assertNotEquals(7, buffer.get(1));
        assertNotEquals(7, buffer.get(2));
        assertEquals(7, buffer.get(3));
        assertNotEquals(7, buffer.get(8));
        assertNotEquals(7, buffer.get(16));
        assertEquals(7, buffer.get(23));
    }

    @Test
    public void lumaFillRespectsPixelStride() {
        ByteBuffer buffer = ByteBuffer.allocate(20);
        fill(buffer, (byte) 7);

        YuvPlaneFiller.fillLuma(buffer, 3, 2, 10, 2, false);

        assertNotEquals(7, buffer.get(0));
        assertEquals(7, buffer.get(1));
        assertNotEquals(7, buffer.get(2));
        assertEquals(7, buffer.get(3));
        assertNotEquals(7, buffer.get(4));
        assertNotEquals(7, buffer.get(10));
    }

    @Test
    public void exactLastRowCapacityIsAccepted() {
        // Last addressed byte is ((2 - 1) * 7) + ((3 - 1) * 2) = 11.
        ByteBuffer buffer = ByteBuffer.allocate(12);
        YuvPlaneFiller.fillNeutralChroma(buffer, 3, 2, 7, 2);
        assertEquals(YuvPlaneFiller.NEUTRAL_CHROMA, buffer.get(11));
    }

    @Test
    public void invalidImageOrPlaneShapeIsRejectedBeforeWriting() {
        expectIllegalArgument(() -> YuvPlaneFiller.validateImageShape(3841, 2160));
        expectIllegalArgument(() -> YuvPlaneFiller.fillLuma(ByteBuffer.allocate(4), 3, 2, 2, 1, false));
        expectIllegalArgument(() -> YuvPlaneFiller.fillNeutralChroma(null, 1, 1, 1, 1));
    }

    @Test
    public void patternPreservesOneTwoAndFourPixelDetail() {
        int width = 32;
        int height = 32;
        // Upper three bands are vertical stripes when x is in the left half.
        assertNotEquals(YuvPlaneFiller.lumaAt(0, 0, width, height, false),
                YuvPlaneFiller.lumaAt(1, 0, width, height, false));
        assertEquals(YuvPlaneFiller.lumaAt(0, 8, width, height, false),
                YuvPlaneFiller.lumaAt(1, 8, width, height, false));
        assertNotEquals(YuvPlaneFiller.lumaAt(0, 8, width, height, false),
                YuvPlaneFiller.lumaAt(2, 8, width, height, false));
        assertEquals(YuvPlaneFiller.lumaAt(0, 16, width, height, false),
                YuvPlaneFiller.lumaAt(3, 16, width, height, false));
        assertNotEquals(YuvPlaneFiller.lumaAt(0, 16, width, height, false),
                YuvPlaneFiller.lumaAt(4, 16, width, height, false));
        // Bottom left is a stable reference gray; bottom next is a 1px checker.
        assertEquals(YuvPlaneFiller.REFERENCE_GRAY, YuvPlaneFiller.lumaAt(0, 24, width, height, false));
        assertNotEquals(YuvPlaneFiller.lumaAt(8, 24, width, height, false),
                YuvPlaneFiller.lumaAt(9, 24, width, height, false));
    }

    private static void fill(ByteBuffer buffer, byte value) {
        for (int index = 0; index < buffer.capacity(); index++) buffer.put(index, value);
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
