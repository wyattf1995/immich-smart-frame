package com.wyattfleming.frameos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class MainActivityModeGestureContractTest {
    private val source by lazy {
        val candidates = listOf(
            Paths.get("app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt"),
            Paths.get("src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt"),
        )
        Files.readString(candidates.firstOrNull(Files::exists) ?: error("Missing MainActivity source"))
    }

    @Test
    fun `touch and physical gestures enter the coalescing path with their event metadata`() {
        val onFling = methodBody("override fun onFling(")
        val onKeyDown = methodBody("override fun onKeyDown(")

        assertContainsInOrder(
            onFling,
            listOf(
                "queueModeGesture(",
                "ModeGestureDirection.NEXT",
                "ModeGestureDirection.PREVIOUS",
                "eventTimeMillis = end.eventTime",
            ),
        )
        assertContainsInOrder(
            onKeyDown,
            listOf(
                "val physicalIntent = inputMapper.mapKeyDown",
                "physicalIntent == FrameIntent.NextMode || physicalIntent == FrameIntent.PreviousMode",
                "queueModeGesture(",
                "eventTimeMillis = event.eventTime",
                "repeatCount = event.repeatCount",
                "val intent = physicalIntent ?: debugKeyboardIntent(keyCode)",
            ),
        )
    }

    @Test
    fun `translated gesture keys are intercepted before focused web content`() {
        val dispatchKeyEvent = methodBody("override fun dispatchKeyEvent(")
        val handleTranslatedGestureKeyEvent = methodBody("private fun handleTranslatedGestureKeyEvent(")

        assertContainsInOrder(
            dispatchKeyEvent,
            listOf(
                "if (handleTranslatedGestureKeyEvent(event)) return true",
                "contextualInputMapper.map(state.mode, event.keyCode, event.isShiftPressed)",
            ),
        )
        assertContainsInOrder(
            handleTranslatedGestureKeyEvent,
            listOf(
                "event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT",
                "event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT",
                "val physicalIntent = inputMapper.mapKeyDown",
                "event.action == KeyEvent.ACTION_DOWN",
                "queueModeGesture(",
                "eventTimeMillis = event.eventTime",
                "repeatCount = event.repeatCount",
                "return true",
            ),
        )
    }

    @Test
    fun `lenovo firmware gestures navigate once from key release`() {
        val dispatchKeyEvent = methodBody("override fun dispatchKeyEvent(")
        val handleLenovoGestureKeyEvent = methodBody("private fun handleLenovoGestureKeyEvent(")

        assertContainsInOrder(
            dispatchKeyEvent,
            listOf(
                "if (handleLenovoGestureKeyEvent(event)) return true",
                "if (handleTranslatedGestureKeyEvent(event)) return true",
                "contextualInputMapper.map(state.mode, event.keyCode, event.isShiftPressed)",
            ),
        )
        assertContainsInOrder(
            handleLenovoGestureKeyEvent,
            listOf(
                "val physicalIntent = inputMapper.mapLenovoGesture",
                "if (physicalIntent == null) return false",
                "event.action == KeyEvent.ACTION_UP",
                "queueModeGesture(",
                "eventTimeMillis = event.eventTime",
                "repeatCount = event.repeatCount",
                "return true",
            ),
        )
    }

    @Test
    fun `volume plus advances and volume minus goes back within the current view`() {
        val dispatchKeyEvent = methodBody("override fun dispatchKeyEvent(")
        val handleContextualVolumeKeyEvent = methodBody("private fun handleContextualVolumeKeyEvent(")
        val dispatchContextualTabClick = methodBody("private fun dispatchContextualTabClick(")

        assertContainsInOrder(
            dispatchKeyEvent,
            listOf(
                "if (handleContextualVolumeKeyEvent(event)) return true",
                "contextualInputMapper.map(state.mode, event.keyCode, event.isShiftPressed)",
            ),
        )
        assertContainsInOrder(
            handleContextualVolumeKeyEvent,
            listOf(
                "KeyEvent.KEYCODE_VOLUME_UP -> true",
                "KeyEvent.KEYCODE_VOLUME_DOWN -> false",
                "event.action == KeyEvent.ACTION_UP",
                "dispatchContextualTabClick(forward, event.eventTime)",
                "return true",
            ),
        )
        assertContainsInOrder(
            dispatchContextualTabClick,
            listOf(
                "KeyEvent.KEYCODE_TAB",
                "KeyEvent.META_SHIFT_ON",
                "KeyEvent.ACTION_DOWN",
                "KeyEvent.ACTION_UP",
                "dispatchKeyEvent(down)",
                "dispatchKeyEvent(up)",
            ),
        )
    }

    @Test
    fun `pending gestures update the HUD and render only their final destination`() {
        val queue = methodBody("private fun queueModeGesture(")
        val dispatch = methodBody("private fun dispatch(")
        val onPause = methodBody("override fun onPause()")

        assertContainsInOrder(
            queue,
            listOf(
                "modeGestureBurst.offer(",
                "displayedMode = state.mode",
                "receivedAtMillis = SystemClock.uptimeMillis()",
                "handler.removeCallbacks(commitModeGesture)",
                "showHud(update.targetMode.label, update.targetMode)",
                "handler.postDelayed(commitModeGesture, update.commitDelayMillis)",
            ),
        )
        assertContainsInOrder(
            source,
            listOf(
                "modeGestureBurst.consumeTarget()",
                "showMode(target, announceHud = false)",
            ),
        )
        assertTrue("explicit actions must supersede a pending gesture", dispatch.contains("cancelModeGesture()"))
        assertTrue("a paused activity must discard pending navigation", onPause.contains("cancelModeGesture()"))
    }

    private fun methodBody(signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing signature: $signature" }
        val bodyStart = source.indexOf('{', start)
        require(bodyStart >= 0) { "Missing method body for: $signature" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("Unterminated method body for: $signature")
    }

    private fun assertContainsInOrder(body: String, snippets: List<String>) {
        var searchFrom = 0
        snippets.forEach { snippet ->
            val foundAt = body.indexOf(snippet, startIndex = searchFrom)
            assertTrue("Missing snippet in order: $snippet", foundAt >= 0)
            searchFrom = foundAt + snippet.length
        }
    }
}
