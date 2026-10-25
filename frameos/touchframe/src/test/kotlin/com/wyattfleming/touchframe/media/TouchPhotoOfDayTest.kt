package com.wyattfleming.touchframe.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchPhotoOfDayTest {
    private val ids = listOf("c3", "a1", "b2", "d4")

    @Test
    fun `the same photo all day whatever order the frame saved them in, and a different one tomorrow`() {
        val day = 20_740L
        assertEquals(TouchPhotoOfDay.pick(ids, day), TouchPhotoOfDay.pick(ids.reversed(), day))
        assertNotEquals(TouchPhotoOfDay.pick(ids, day), TouchPhotoOfDay.pick(ids, day + 1))
        assertEquals("a1", TouchPhotoOfDay.pick(ids, -4L))
        assertNull(TouchPhotoOfDay.pick(emptyList(), day))
    }

    @Test
    fun `the decode samples down only while the photo still covers the panel`() {
        assertEquals(2, TouchPhotoOfDay.sampleSize(3840, 2160, 700, 1000))
        assertEquals(1, TouchPhotoOfDay.sampleSize(1920, 1080, 392, 732))
        assertEquals(4, TouchPhotoOfDay.sampleSize(4000, 3000, 392, 732))
        assertEquals(1, TouchPhotoOfDay.sampleSize(0, 1080, 392, 732))
    }
}
