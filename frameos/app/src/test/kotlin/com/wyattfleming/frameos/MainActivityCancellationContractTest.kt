package com.wyattfleming.frameos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class MainActivityCancellationContractTest {
    @Test
    fun `onStop delegates to centralized Home Assistant cancellation and resume wiring`() {
        val source = source("app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt")
        val onStop = methodBody(source, "override fun onStop()")
        val onResume = methodBody(source, "override fun onResume()")

        assertContainsInOrder(
            onStop,
            listOf(
                """cancelHomeAssistantWork(stage = "activity_stop", clearPendingAuthorizationCode = false)""",
                "super.onStop()",
            ),
        )
        assertContainsInOrder(
            onResume,
            listOf(
                "if (pendingAuthorizationCode != null && !weatherAuthorizationInProgress) {",
                "startAuthorizationExchange()",
                "} else if (",
                "refreshWeather()",
            ),
        )
    }

    @Test
    fun `cancelHomeAssistantWork cancels transport coordinator futures and resets lifecycle flags`() {
        val source = source("app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt")
        val cancel = methodBody(source, "private fun cancelHomeAssistantWork(")

        assertContainsInOrder(
            cancel,
            listOf(
                "weatherRequestGeneration += 1",
                "homeAssistantTransport?.cancelInFlight()",
                "weatherCoordinator?.cancel()",
                "weatherRefreshFuture?.cancel(true)",
                "weatherRefreshFuture = null",
                "weatherRefreshStartedAtUptimeMillis = 0L",
                "weatherRefreshInProgress = false",
                "authorizationFuture?.cancel(true)",
                "authorizationFuture = null",
                "authorizationStartedAtUptimeMillis = 0L",
                "weatherAuthorizationInProgress = false",
            ),
        )
        assertTrue(cancel.contains("if (clearPendingAuthorizationCode) {"))
        assertTrue(cancel.contains("pendingAuthorizationCode = null"))
        assertTrue(cancel.contains("pendingAuthorizationPreviousAuthEpoch = null"))
    }

    private fun source(path: String): String {
        val candidates = listOf(
            Paths.get(path),
            Paths.get(path.removePrefix("app/")),
        )
        val existing = candidates.firstOrNull(Files::exists)
            ?: error("Missing source file in candidates: $candidates")
        return Files.readString(existing)
    }

    private fun methodBody(source: String, signature: String): String {
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

    private fun assertContainsInOrder(source: String, snippets: List<String>) {
        var searchFrom = 0
        snippets.forEach { snippet ->
            val foundAt = source.indexOf(snippet, startIndex = searchFrom)
            assertTrue("Missing snippet in order: $snippet", foundAt >= 0)
            searchFrom = foundAt + snippet.length
        }
    }
}
