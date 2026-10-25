package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android Settings on the wall can turn off Wi-Fi, change the date or remove the app (the 10/14 large frame was left
 * in Settings with no way back). A tap only explains, a press and hold asks once more, and only that answer opens it.
 */
class TouchSystemSettingsGateTest {
    private val notices = mutableListOf<TouchSystemSettingsGate.Notice>()
    private var opens = 0
    private var openResult = true
    private val gate = TouchSystemSettingsGate(notices::add) { opens++; openResult }

    @Test fun `a tap explains the hold and never opens Settings`() {
        repeat(3) { gate.tap() }

        assertEquals(0, opens)
        assertEquals(3, notices.size)
        notices.forEach {
            assertEquals(TouchSystemSettingsGate.Tone.INFO, it.tone)
            assertNull(it.action)
            assertTrue(it.message.contains("hold", ignoreCase = true))
        }
    }

    @Test fun `a hold warns and opens Settings only when the warning is confirmed`() {
        gate.hold()

        assertEquals(0, opens)
        val warning = notices.single()
        assertEquals(TouchSystemSettingsGate.Tone.WARNING, warning.tone)
        assertTrue(warning.message.contains("Wi-Fi"))
        assertNotNull(warning.actionLabel)
        requireNotNull(warning.action).invoke()
        assertEquals(1, opens)
        assertEquals(1, notices.size)
    }

    @Test fun `a refused open says how to enable the return button`() {
        openResult = false
        gate.hold()
        requireNotNull(notices.single().action).invoke()

        assertEquals(1, opens)
        assertEquals(TouchSystemSettingsGate.Tone.WARNING, notices.last().tone)
        assertTrue(notices.last().message.contains("return button"))
        assertNull(notices.last().action)
    }

    @Test fun `the shell sends a tap and a hold separately and the activity opens Settings only through the gate`() {
        val shell = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayout.kt").readText()
        val activity = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/TouchFrameActivity.kt").readText()

        assertTrue(shell.contains("setOnClickListener { onSettings() }\n        setOnLongClickListener { onSettingsHold(); true }"))
        assertTrue(activity.contains("settingsGate = TouchSystemSettingsGate({ notice(it) }) { settingsReturn.open() }"))
        assertTrue(activity.contains("onSettingsHold = { settingsGate.hold() }) {\n            settingsGate.tap()\n        }"))
        assertEquals(1, Regex("""settingsReturn\.open\(\)""").findAll(activity).count())
    }
}
