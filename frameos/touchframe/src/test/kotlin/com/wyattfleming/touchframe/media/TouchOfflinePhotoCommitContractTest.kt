package com.wyattfleming.touchframe.media

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TouchOfflinePhotoCommitContractTest {
    @Test fun `canceled hidden policy operation is checked at the reserve commit boundary`() {
        val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotoStore.kt").readText()
        assertTrue(source.contains("!task.isCancelled && !Thread.currentThread().isInterrupted"))
        assertTrue(source.contains("scopeGate.isCurrent(expected) && canCommit()"))
    }

    @Test fun `main thread hidden dismissal runs before the companion callback returns`() {
        val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotos.kt").readText()
        assertTrue(source.contains("if (Looper.myLooper() === Looper.getMainLooper()) dismiss()"))
        assertTrue(source.contains("main.post { dismiss() }"))
    }
}
