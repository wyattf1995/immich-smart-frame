package com.wyattfleming.frameos.control

import java.io.InputStream
import java.nio.charset.StandardCharsets

object FrameCompanionResponseReader {
    const val MAX_RESPONSE_BYTES = 64 * 1024
    fun read(input: InputStream): String? {
        val output = ByteArray(MAX_RESPONSE_BYTES + 1); var used = 0
        while (used < output.size) { val count = input.read(output, used, output.size - used); if (count < 0) break; used += count }
        return if (used <= MAX_RESPONSE_BYTES) String(output, 0, used, StandardCharsets.UTF_8) else null
    }
}
