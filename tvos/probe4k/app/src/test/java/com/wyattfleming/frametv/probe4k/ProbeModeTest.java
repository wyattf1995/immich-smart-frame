package com.wyattfleming.frametv.probe4k;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public final class ProbeModeTest {
    @Test
    public void uhdModeRequestsTheExact4kBufferUsedByTheProbe() {
        assertEquals(3840, ProbeMode.UHD.width);
        assertEquals(2160, ProbeMode.UHD.height);
        assertEquals("4K buffer", ProbeMode.UHD.displayName);
    }

    @Test
    public void dpadSelectionMapsLeftAndRightToDistinctDiagnosticBuffers() {
        assertSame(ProbeMode.HD, ProbeMode.fromRightSelection(false));
        assertSame(ProbeMode.UHD, ProbeMode.fromRightSelection(true));
        assertEquals(1920, ProbeMode.HD.width);
        assertEquals(1080, ProbeMode.HD.height);
    }
}
