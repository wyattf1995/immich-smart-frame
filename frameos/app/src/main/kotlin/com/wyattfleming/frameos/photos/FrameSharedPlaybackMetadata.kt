package com.wyattfleming.frameos.photos

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Strictly recognizes the server-authoritative shared playback URL option. */
object FrameSharedPlaybackMetadata {
    private val group = Regex("[a-z0-9][a-z0-9_-]{0,47}")

    fun configuredGroup(photosUrl: String?): String? {
        val query = runCatching { URI(photosUrl ?: return null).rawQuery }.getOrNull() ?: return null
        val values = query.split('&').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator < 1) null else part.substring(0, separator) to part.substring(separator + 1)
        }
        val groups = values.filter { it.first == "sync_group" }.map { decode(it.second) }
        // A duplicated frame id is ambiguous device identity; it is never a sync signal.
        if (groups.size != 1 || values.count { it.first == "frame_id" } > 1) return null
        return groups.single().takeIf { group.matches(it) }
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault("")
}
