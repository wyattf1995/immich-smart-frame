package com.wyattfleming.frameos.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal val FRAME_ASSET_ID = Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$")
private val PROFILE = Regex("^[a-zA-Z0-9_-]{1,48}$")

/** Exact Photos URL/profile identity; the raw URL is deliberately not normalized. */
data class FramePhotoScope private constructor(val photosUrl: String, val profile: String, val key: String) {
    companion object {
        fun create(photosUrl: String, profile: String): FramePhotoScope? = runCatching {
            require(PROFILE.matches(profile))
            val uri = URI(photosUrl)
            require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
            require(!uri.host.isNullOrBlank() && uri.userInfo == null)
            require(uri.fragment == null)
            val key = MessageDigest.getInstance("SHA-256")
                .digest((photosUrl + "\n" + profile).toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            FramePhotoScope(photosUrl, profile, key)
        }.getOrNull()
    }
}

interface FramePhotoDecoder {
    /** Returns a newly encoded JPEG or null when the untrusted image cannot be safely decoded. */
    fun decodeAndResize(base64Jpeg: String): ByteArray?
}

/** Decodes only a bounded JPEG, samples before allocation, and never retains a Bitmap. */
class AndroidFramePhotoDecoder : FramePhotoDecoder {
    override fun decodeAndResize(base64Jpeg: String): ByteArray? {
        if (base64Jpeg.length > FramePhotoBridge.MAX_BASE64_CHARS) return null
        return try {
            val input = Base64.decode(base64Jpeg, Base64.NO_WRAP)
            if (input.size > MAX_DECODED_BYTES || input.size < 4 || input[0] != 0xFF.toByte() || input[1] != 0xD8.toByte()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
            if (bounds.outWidth !in 1..MAX_DIMENSION * 32 || bounds.outHeight !in 1..MAX_DIMENSION * 32) return null
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
            val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, options) ?: return null
            val resized = try {
                val scale = minOf(1f, MAX_DIMENSION.toFloat() / decoded.width, MAX_DIMENSION.toFloat() / decoded.height)
                if (scale >= 1f) decoded else Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
            } catch (_: OutOfMemoryError) {
                decoded.recycle()
                return null
            }
            try {
                ByteArrayOutputStream().use { output ->
                    if (!resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return null
                    output.toByteArray().takeIf { it.size <= MAX_OUTPUT_BYTES }
                }
            } finally {
                if (resized !== decoded) resized.recycle()
                decoded.recycle()
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_DIMENSION * 2 || height / sample > MAX_DIMENSION * 2) sample *= 2
        return sample
    }

    private companion object {
        const val MAX_DIMENSION = 1920
        const val MAX_DECODED_BYTES = 3 * 1024 * 1024
        const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024
        const val JPEG_QUALITY = 85
    }
}

/**
 * Private, disk-backed reserve of recent Photos JPEGs. It never writes to Immich or
 * touches Home Assistant, camera, or screenshot data. The active scope is replaced
 * atomically on an exact URL/profile change, clearing all prior entries.
 */
class FrameOfflineReserve(
    private val root: File,
    private val decoder: FramePhotoDecoder = AndroidFramePhotoDecoder(),
    private val maxPhotos: Int = DEFAULT_MAX_PHOTOS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    data class Entry(val assetId: String, val capturedAt: Long, val file: File)
    data class Status(val offlineAssets: Int, val offlineBytes: Long, val latest: Entry?)

    private var scope: FramePhotoScope? = null
    private var entries = emptyList<Entry>()
    /** Device-private full snapshot supplied by the authenticated companion response. */
    private var hiddenAssets = emptySet<String>()

    init {
        require(maxPhotos in 1..DEFAULT_MAX_PHOTOS)
        require(maxBytes in 1..DEFAULT_MAX_BYTES)
    }

    @Synchronized
    fun activateScope(photosUrl: String, profile: String): Boolean {
        val requested = FramePhotoScope.create(photosUrl, profile) ?: return false
        root.mkdirs()
        val stored = readScopeState()
        val storedKey = stored?.key
        if (storedKey != requested.key) clearFiles()
        scope = requested
        hiddenAssets = if (storedKey == requested.key) stored?.hiddenAssets.orEmpty() else emptySet()
        entries = if (storedKey == requested.key) readEntries(requested.key) else emptyList()
        removeHiddenEntries()
        writeScope()
        writeIndex()
        return true
    }

    fun persist(
        assetId: String,
        base64Jpeg: String,
        capturedAt: Long = System.currentTimeMillis(),
        expectedScopeKey: String? = null,
        canCommit: (() -> Boolean)? = null,
    ): Boolean {
        if (!canPersist(assetId, base64Jpeg, expectedScopeKey, canCommit)) return false
        val jpeg = decoder.decodeAndResize(base64Jpeg) ?: return false
        return commitPersist(assetId, jpeg, capturedAt, expectedScopeKey, canCommit)
    }

    /** Validates before decoding without holding the reserve monitor during untrusted JPEG work. */
    @Synchronized
    private fun canPersist(
        assetId: String,
        base64Jpeg: String,
        expectedScopeKey: String?,
        canCommit: (() -> Boolean)?,
    ): Boolean {
        val activeScope = scope ?: return false
        return (expectedScopeKey == null || activeScope.key == expectedScopeKey) &&
            FRAME_ASSET_ID.matches(assetId) && assetId.lowercase() !in hiddenAssets &&
            base64Jpeg.length <= FramePhotoBridge.MAX_BASE64_CHARS && canCommit?.invoke() != false
    }

    /** Re-checks scope and hidden policy at the atomic disk-commit boundary. */
    @Synchronized
    private fun commitPersist(
        assetId: String,
        jpeg: ByteArray,
        capturedAt: Long,
        expectedScopeKey: String?,
        canCommit: (() -> Boolean)?,
    ): Boolean {
        val activeScope = scope ?: return false
        if (expectedScopeKey != null && activeScope.key != expectedScopeKey || assetId.lowercase() in hiddenAssets || canCommit?.invoke() == false) return false
        if (jpeg.isEmpty() || jpeg.size.toLong() > maxBytes) return false
        val target = photoFile(assetId)
        if (!writeAtomically(target, jpeg)) return false
        entries = (listOf(Entry(assetId, capturedAt, target)) + entries.filterNot { it.assetId.equals(assetId, true) }).toMutableList()
        evict(activeScope.key)
        writeIndex()
        return entries.any { it.assetId.equals(assetId, true) }
    }

    /** Replaces the current scoped snapshot and removes any now-hidden persisted photos. */
    @Synchronized
    fun applyHiddenAssets(assetIds: Set<String>): Boolean {
        if (scope == null || assetIds.size > MAX_HIDDEN_ASSETS || assetIds.any { !FRAME_ASSET_ID.matches(it) }) return false
        hiddenAssets = assetIds.mapTo(linkedSetOf()) { it.lowercase() }
        removeHiddenEntries()
        writeScope()
        writeIndex()
        return true
    }

    @Synchronized
    fun latest(): Entry? = entries.firstOrNull { it.file.isFile }

    @Synchronized
    fun entries(): List<Entry> = entries.filter { it.file.isFile }

    @Synchronized
    fun status(): Status {
        val valid = entries()
        return Status(offlineAssets = valid.size, offlineBytes = valid.sumOf { it.file.length() }, latest = valid.firstOrNull())
    }

    /** Drops persisted JPEGs when the process receives a low-memory signal. */
    @Synchronized
    fun clearForLowMemory() {
        clearFiles()
        entries = emptyList()
        if (scope != null) {
            writeScope()
            writeIndex()
        }
    }

    @Synchronized
    fun clear() {
        clearFiles()
        entries = emptyList()
        scope = null
        hiddenAssets = emptySet()
    }

    private fun evict(scopeKey: String) {
        val kept = entries.toMutableList()
        while (kept.size > maxPhotos || kept.sumOf { it.file.length() } > maxBytes) {
            val removed = kept.removeLastOrNull() ?: break
            removed.file.delete()
        }
        entries = kept.filter { it.file.isFile }
        if (scopeKey != scope?.key) entries = emptyList()
    }

    private data class StoredScope(val key: String, val hiddenAssets: Set<String>)

    private fun readScopeState(): StoredScope? = runCatching {
        val data = JSONObject(scopeFile.readText())
        val key = data.optString("scope").takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
        val values = data.optJSONArray("hiddenAssets")
        val hidden = buildSet {
            if (values != null) {
                if (values.length() > MAX_HIDDEN_ASSETS) return null
                for (index in 0 until values.length()) {
                    val asset = values.optString(index)
                    if (!FRAME_ASSET_ID.matches(asset)) return null
                    add(asset.lowercase())
                }
            }
        }
        StoredScope(key, hidden)
    }.getOrNull()

    private fun readEntries(expectedScope: String): List<Entry> = runCatching {
        val data = JSONObject(indexFile.readText())
        if (data.optString("scope") != expectedScope) return emptyList()
        val values = data.optJSONArray("entries") ?: return emptyList()
        buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val asset = item.optString("assetId")
                val capturedAt = item.optLong("capturedAt", -1)
                if (FRAME_ASSET_ID.matches(asset) && capturedAt >= 0) {
                    val file = photoFile(asset)
                    if (file.isFile && asset.lowercase() !in hiddenAssets) add(Entry(asset, capturedAt, file))
                }
            }
        }.take(maxPhotos)
    }.getOrDefault(emptyList())

    private fun writeIndex() {
        val active = scope ?: return
        val values = JSONArray()
        entries.forEach { values.put(JSONObject().put("assetId", it.assetId).put("capturedAt", it.capturedAt)) }
        writeAtomically(indexFile, JSONObject().put("scope", active.key).put("entries", values).toString().toByteArray())
    }

    private fun writeScope() {
        val active = scope ?: return
        val hidden = JSONArray()
        hiddenAssets.sorted().forEach(hidden::put)
        writeAtomically(scopeFile, JSONObject().put("scope", active.key).put("hiddenAssets", hidden).toString().toByteArray())
    }

    private fun removeHiddenEntries() {
        if (hiddenAssets.isEmpty()) return
        entries.filter { it.assetId.lowercase() in hiddenAssets }.forEach { it.file.delete() }
        entries = entries.filterNot { it.assetId.lowercase() in hiddenAssets }
    }

    private fun clearFiles() {
        root.listFiles()?.forEach { file -> if (file.name.startsWith("photo-") || file == indexFile || file == scopeFile) file.delete() }
    }

    private fun photoFile(assetId: String) = File(root, "photo-${assetId.lowercase()}.jpg")
    private val indexFile get() = File(root, "reserve.json")
    private val scopeFile get() = File(root, "scope.json")

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.new")
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    private companion object {
        const val DEFAULT_MAX_PHOTOS = 12
        const val DEFAULT_MAX_BYTES = 24L * 1024L * 1024L
        const val MAX_HIDDEN_ASSETS = 1000
    }
}
