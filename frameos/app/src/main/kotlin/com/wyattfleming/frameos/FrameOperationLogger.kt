package com.wyattfleming.frameos

import android.util.Log

data class FrameOperationLogEntry(
    val route: String,
    val stage: String,
    val outcome: String,
    val elapsedMillis: Long,
)

fun interface FrameOperationLogger {
    fun log(entry: FrameOperationLogEntry)
}

val NoOpFrameOperationLogger = FrameOperationLogger { }

class AndroidFrameOperationLogger(
    private val tag: String = TAG,
) : FrameOperationLogger {
    override fun log(entry: FrameOperationLogEntry) {
        Log.i(
            tag,
            "route=${entry.route} stage=${entry.stage} outcome=${entry.outcome} elapsedMs=${entry.elapsedMillis.coerceAtLeast(0L)}",
        )
    }

    private companion object {
        const val TAG = "FrameOS"
    }
}
