package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Home behaviours that only show on the device. Todoist removes a completed item from Home
 * Assistant's list, so Undo cannot reopen it later: a check is held back for the Undo delay and only
 * then sent. The photo of the day must stay one small bitmap on a frame the low-memory killer already stalks.
 */
class TouchHomeTodoSourceTest {
    private val home = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchHomeView.kt").readText()
    private val photo = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchPhotoOfDay.kt").readText()
    private val activity = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/TouchFrameActivity.kt").readText()

    private fun body(source: String, start: String): String {
        val from = source.indexOf(start)
        return source.substring(from, source.indexOf("\n    }\n", from))
    }

    @Test
    fun `a checked item is sent to Home Assistant only after the Undo delay`() {
        val markDone = body(home, "private fun markDone(")
        assertTrue(markDone.contains("postDelayed(send, UNDO_MILLIS)"))
        assertFalse("checking must not complete the item at once", markDone.contains("complete("))
        assertTrue(home.contains("const val UNDO_MILLIS = 5_000L"))
        assertTrue(body(home, "private fun undo(").contains("removeCallbacks(it.second)"))
    }

    @Test
    fun `the photo of the day is sampled to the panel and stored at two bytes a pixel`() {
        assertTrue(photo.contains("inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)"))
        assertTrue(photo.contains("inPreferredConfig = Bitmap.Config.RGB_565"))
        assertTrue("the photo is released when Android asks for memory", body(activity, "override fun onTrimMemory(").contains("home.setPhoto(null)"))
    }
}
