package com.wyattfleming.frameos.config

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Makes the one server-authoritative Photos URL option without rewriting its other bytes. */
object FramePhotosSyncGroupUrl {
    sealed interface ReadResult {
        data class Valid(val group: String?) : ReadResult
        data class Invalid(val reason: String) : ReadResult
    }

    sealed interface UpdateResult {
        data class Updated(val photosUrl: String, val group: String?) : UpdateResult
        data class Invalid(val reason: String) : UpdateResult
    }

    fun configuredGroup(photosUrl: String): ReadResult = parse(photosUrl).let { parsed ->
        if (parsed.invalidReason != null) ReadResult.Invalid(parsed.invalidReason)
        else ReadResult.Valid(parsed.group)
    }

    fun update(photosUrl: String, requestedGroup: String): UpdateResult {
        if (requestedGroup.isNotEmpty() && !GROUP.matches(requestedGroup)) {
            return UpdateResult.Invalid("invalid-sync-group")
        }
        val parsed = parse(photosUrl)
        if (parsed.invalidReason != null) return UpdateResult.Invalid(parsed.invalidReason)

        val remaining = parsed.segments.filterNot { it.name == SYNC_GROUP }
        val updatedSegments = if (requestedGroup.isEmpty()) remaining else remaining + QuerySegment("$SYNC_GROUP=$requestedGroup", SYNC_GROUP)
        val query = updatedSegments.joinToString("&") { it.raw }
        val updatedUrl = when {
            query.isNotEmpty() -> parsed.base + "?" + query + parsed.fragment
            else -> parsed.base + parsed.fragment
        }
        return UpdateResult.Updated(updatedUrl, requestedGroup.ifEmpty { null })
    }

    private fun parse(photosUrl: String): Parsed {
        if (runCatching { URI(photosUrl) }.isFailure) return Parsed.invalid("malformed-photos-url")
        val fragmentIndex = photosUrl.indexOf('#')
        val beforeFragment = if (fragmentIndex >= 0) photosUrl.substring(0, fragmentIndex) else photosUrl
        val fragment = if (fragmentIndex >= 0) photosUrl.substring(fragmentIndex) else ""
        val queryIndex = beforeFragment.indexOf('?')
        val base = if (queryIndex >= 0) beforeFragment.substring(0, queryIndex) else beforeFragment
        val rawQuery = if (queryIndex >= 0) beforeFragment.substring(queryIndex + 1) else ""
        val segments = if (rawQuery.isEmpty()) emptyList() else rawQuery.split("&", ignoreCase = false, limit = Int.MAX_VALUE).map(::segment)
        val syncSegments = segments.filter { it.name == SYNC_GROUP }
        if (syncSegments.size > 1 || syncSegments.any { it.value == null }) return Parsed.invalid("invalid-sync-group")
        if (segments.count { it.name == FRAME_ID } > 1) return Parsed.invalid("duplicate-frame-id")
        val group = syncSegments.singleOrNull()?.value?.let(::decode)
        if (syncSegments.isNotEmpty() && group == null) return Parsed.invalid("invalid-sync-group")
        if (group != null && !GROUP.matches(group)) return Parsed.invalid("invalid-sync-group")
        return Parsed(base, fragment, segments, group, null)
    }

    private fun segment(raw: String): QuerySegment {
        val separator = raw.indexOf('=')
        val name = if (separator >= 0) raw.substring(0, separator) else raw
        return QuerySegment(raw, name, if (separator >= 0) raw.substring(separator + 1) else null)
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()

    private data class QuerySegment(val raw: String, val name: String, val value: String? = null)
    private data class Parsed(
        val base: String,
        val fragment: String,
        val segments: List<QuerySegment>,
        val group: String?,
        val invalidReason: String?,
    ) {
        companion object {
            fun invalid(reason: String) = Parsed("", "", emptyList(), null, reason)
        }
    }

    private const val SYNC_GROUP = "sync_group"
    private const val FRAME_ID = "frame_id"
    private val GROUP = Regex("[a-z0-9][a-z0-9_-]{0,47}")
}
