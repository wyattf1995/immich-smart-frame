package com.wyattfleming.touchframe.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchCameraLabelTest {
    @Test fun cameraLabelsCollapseOnlyImmediatelyRepeatedLeadingTokens() {
        assertEquals("Dining room camera", TouchCameraLabels.forEntityId("camera.dining_room_dining_room_camera"))
        assertEquals("Garage camera", TouchCameraLabels.forEntityId("camera.garage_garage_camera"))
        assertEquals("Backyard camera", TouchCameraLabels.forEntityId("camera.backyard_backyard_camera"))
        assertEquals("Living room camera", TouchCameraLabels.forEntityId("camera.living_room_living_room_camera"))
        assertEquals("Front door camera", TouchCameraLabels.forEntityId("camera.front_door_camera"))
        assertEquals("Entryway", TouchCameraLabels.forEntityId("camera.entryway"))
    }
}
