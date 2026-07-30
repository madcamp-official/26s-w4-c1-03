package com.gamdo.app.data

import android.content.Context
import android.net.Uri
import com.gamdo.app.core.ExifSanitizer
import com.gamdo.app.data.local.CachedReferencesDao
import com.gamdo.app.data.local.entity.CachedReferences
import com.gamdo.app.data.network.ReferenceAnalysisResponse
import com.gamdo.app.data.preset.ResolvedStyle
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Narrow network seam — the one `GamdoApiClient` call this repository needs.
 * Exists so [ReferenceRepository] can be unit-tested with a counting fake
 * instead of a live Retrofit stack (no MockWebServer on this project's test
 * classpath). Production wiring: `AppContainer` adapts
 * `GamdoApiClient.analyzeReference` to this SAM.
 */
fun interface ReferenceAnalysisClient {
    suspend fun analyzeReference(image: File): ReferenceAnalysisResponse
}

/**
 * GPS-strip seam so tests can swap in a no-op instead of touching real EXIF
 * I/O (see [ExifSanitizer] for why the real implementation cannot run under
 * plain `testDebugUnitTest`). The production default is
 * [ExifSanitizer.sanitizeFile] — D8-5.
 */
fun interface ReferenceImageSanitizer {
    fun sanitize(file: File)
}

/**
 * Re-encodes a picker result before upload so orientation and all metadata are
 * normalized at the app boundary. JVM tests use the no-op default; Android
 * production wiring supplies the bitmap implementation from [ReferenceImagePreprocessor].
 */
fun interface ReferenceImagePreprocessor {
    fun normalize(file: File)
}

/**
 * §5-1 resolution result — either a `cached_references` hit or a fresh
 * `/references/analyze` response, normalized to the same shape either way.
 * [targetComposition] and [colorTarget] are what §5-2 (wave 2, out of scope in
 * this repository) will feed into `AlignmentEngine`'s `StyleTarget` and the
 * result screen's reference-color step, respectively.
 */
data class ReferenceResolution(
    val contentHash: String,
    val analysisVersion: Int,
    val analysis: JsonObject,
    val targetComposition: JsonObject,
    val colorTarget: JsonObject,
    val compositionAvailable: Boolean,
    val colorAvailable: Boolean,
    val fromCache: Boolean,
)

/**
 * §5-1 — reference photo selection, content-hash cache, and (on a cache miss)
 * the `POST /references/analyze` upload.
 *
 * Flow: photo picker → SHA-256 of the picked bytes → `cached_references` lookup
 * → if present, return it as-is (**zero network calls** — the wave's stated
 * completion criterion; see `ReferenceRepositoryTest`). If absent: write the
 * bytes to a scratch file, run it through [sanitizer] (D8-5 — mandatory,
 * unconditional, not reorderable after the upload), call [analysisClient], then
 * cache the response keyed by the same hash so the next pick of the same photo
 * is free.
 *
 * D7-1: [resolve]/[resolveBytes] must only run from a user-triggered callback —
 * the picker's result callback, or an explicit follow-on `onClick` — never from
 * `LaunchedEffect`/`init`/a scheduler. This class cannot enforce that from the
 * inside; it is a call-site obligation for whoever wires the camera-screen entry
 * point (guide-capture-agent — see the slot request sent over SendMessage this
 * wave).
 *
 * Deliberately Context-free in its constructor. Unlike [CaptureRepository], this
 * class is meant to be constructed directly inside a JVM unit test (there is no
 * Robolectric here), so [Context] is a parameter of [resolve] only and is never
 * stored. [resolveBytes] is the Context-free core where the orchestration (and
 * the tests) actually live.
 */
class ReferenceRepository(
    private val cachedReferencesDao: CachedReferencesDao,
    private val analysisClient: ReferenceAnalysisClient,
    private val json: Json,
    private val cacheDir: File,
    private val sanitizer: ReferenceImageSanitizer = ReferenceImageSanitizer(ExifSanitizer::sanitizeFile),
    private val preprocessor: ReferenceImagePreprocessor = ReferenceImagePreprocessor { },
    private val activeReferenceSink: (ResolvedStyle?) -> Unit = { },
) {
    private val _activeReferenceStyle = MutableStateFlow<ResolvedStyle?>(null)
    val activeReferenceStyle: StateFlow<ResolvedStyle?> = _activeReferenceStyle.asStateFlow()

    suspend fun activate(
        settings: SettingsRepository,
        resolution: ReferenceResolution,
        scope: ResolvedStyle.ReferenceScope = ResolvedStyle.ReferenceScope.BOTH,
        strength: Double = ResolvedStyle.DEFAULT_STRENGTH,
    ): ResolvedStyle {
        require(scope == ResolvedStyle.ReferenceScope.COLOR || resolution.compositionAvailable) {
            "composition is unavailable for this reference; choose color-only"
        }
        require(scope == ResolvedStyle.ReferenceScope.COMPOSITION || resolution.colorAvailable) {
            "color analysis is unavailable; choose composition-only"
        }
        val previousHash = settings.getActiveReferenceHash()
        settings.saveActiveReference(resolution.contentHash, scope.name.lowercase(), strength)
        if (previousHash != null && previousHash != resolution.contentHash) {
            overlayFile(previousHash).delete()
        }
        cleanupCache(resolution.contentHash)
        return ResolvedStyle.fromReference(
            hash = resolution.contentHash,
            target = resolution.targetComposition,
            colorTarget = resolution.colorTarget,
            scope = scope,
            strength = strength,
        ).also(::publishActiveReference)
    }

    suspend fun cleanupCache(activeHash: String? = null) {
        val active = activeHash.orEmpty()
        val now = System.currentTimeMillis()
        cachedReferencesDao.deleteExpiredInactive(now - CACHE_MAX_AGE_MS, active)
        cachedReferencesDao.trimInactive(MAX_CACHE_ENTRIES, active)
        cleanupOverlayCache(active)
    }

    /**
     * Returns the short-lived, locally sanitized source image for the camera's
     * reference-opacity overlay. Analysis JSON remains the durable reference; this
     * bitmap is merely a 24-hour visual aid and is never uploaded again.
     */
    suspend fun activeOverlayUri(settings: SettingsRepository): Uri? = withContext(Dispatchers.IO) {
        val hash = settings.getActiveReferenceHash() ?: return@withContext null
        val file = overlayFile(hash)
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > OVERLAY_CACHE_MAX_AGE_MS) {
            file.delete()
            return@withContext null
        }
        Uri.fromFile(file)
    }

    suspend fun active(settings: SettingsRepository): ReferenceResolution? {
        val hash = settings.getActiveReferenceHash() ?: return null
        return cachedReferencesDao.get(hash)?.toResolution(json, fromCache = true)?.also { resolution ->
            val scope = runCatching {
                ResolvedStyle.ReferenceScope.valueOf(settings.getActiveReferenceScope().uppercase())
            }.getOrDefault(ResolvedStyle.ReferenceScope.BOTH)
            publishActiveReference(ResolvedStyle.fromReference(
                hash = resolution.contentHash,
                target = resolution.targetComposition,
                colorTarget = resolution.colorTarget,
                scope = scope,
                strength = settings.getActiveReferenceStrength(),
            ))
        }
    }

    suspend fun clearActive(settings: SettingsRepository) {
        val hash = settings.getActiveReferenceHash()
        settings.clearActiveReference()
        hash?.let { overlayFile(it).delete() }
        publishActiveReference(null)
        cleanupCache()
    }

    private fun publishActiveReference(reference: ResolvedStyle?) {
        _activeReferenceStyle.value = reference
        activeReferenceSink(reference)
    }

    /**
     * §5-1 entry point: [uri] just came back from the system photo picker.
     * Reads the picked bytes through [context]'s resolver and delegates to
     * [resolveBytes]. Not unit-tested — needs a real `ContentResolver`; verify
     * on-device (DONE-DEVICE).
     */
    suspend fun resolve(context: Context, uri: Uri): ReferenceResolution = withContext(Dispatchers.IO) {
        val bytes = context.applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_INPUT_BYTES) { "reference image exceeds the 20MB limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
            ?: error("cannot open reference image: $uri")
        resolveBytes(bytes)
    }

    /**
     * Context-free core: hash → cache lookup → (sanitize + upload + cache-write)
     * only on a miss. Public and Context-free specifically so it is
     * unit-testable — see `ReferenceRepositoryTest` for the "동일 사진 재선택 시
     * 네트워크 호출 0건" completion-criterion test.
     */
    suspend fun resolveBytes(imageBytes: ByteArray): ReferenceResolution = withContext(Dispatchers.IO) {
        val hash = sha256Hex(imageBytes)
        cachedReferencesDao.get(hash)
            ?.takeIf { it.analysisV >= CURRENT_ANALYSIS_VERSION }
            ?.let { cached ->
            return@withContext cached.toResolution(json, fromCache = true)
        }

        cacheDir.mkdirs()
        val tempFile = File(cacheDir, "$hash.jpg")
        try {
            tempFile.writeBytes(imageBytes)
            preprocessor.normalize(tempFile)
            // D8-5 blocker: unconditional and not reorderable — every byte that
            // leaves the device through analyzeReference() must go through the
            // sanitizer first.
            sanitizer.sanitize(tempFile)
            val response = analysisClient.analyzeReference(tempFile)

            // The same normalized/sanitized bytes used for analysis are the only
            // image allowed behind the in-camera opacity control. Keeping them for a
            // short session window fixes the otherwise empty overlay after a relaunch
            // without turning a reference photo into permanent app data.
            val overlay = overlayFile(hash)
            overlay.parentFile?.mkdirs()
            tempFile.copyTo(overlay, overwrite = true)

            val entry = CachedReferences(
                contentHash = hash,
                analysisJson = json.encodeToString(JsonObject.serializer(), response.analysis),
                targetJson = json.encodeToString(JsonObject.serializer(), response.targetComposition),
                // `paletteJson` is a frozen column name (R2-1 / TEAM.md §8) but
                // the payload it actually carries is the *full* colorTarget
                // response (palette + colorTemperature + exposureBias, M7-04),
                // not just a palette array. The KDoc that should sit on
                // `CachedReferences.paletteJson` itself belongs in
                // `data/local/entity/DefinitionOnlyEntities.kt`, which is
                // outside reference-net-agent's edit scope (편집 금지 — see the
                // §8 escalation note this wave). This comment, plus the mirrored
                // one in `NetworkDaos.kt`, is the documentation of record until
                // the lead applies it there directly.
                paletteJson = json.encodeToString(JsonObject.serializer(), response.colorTarget),
                analysisV = response.analysisVersion,
                createdAt = System.currentTimeMillis(),
            )
            cachedReferencesDao.upsert(entry)
            entry.toResolution(json, fromCache = false)
        } finally {
            tempFile.delete()
        }
    }

    companion object {
        const val CURRENT_ANALYSIS_VERSION = 3
        const val MAX_INPUT_BYTES = 20 * 1024 * 1024
        const val MAX_CACHE_ENTRIES = 20
        const val CACHE_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
        const val OVERLAY_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        /** SHA-256 of [bytes] as lowercase hex — the `cached_references` cache key. */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = StringBuilder(digest.size * 2)
            for (b in digest) hex.append("%02x".format(b.toInt() and 0xFF))
            return hex.toString()
        }
    }

    private fun overlayFile(hash: String): File = File(cacheDir, "reference-overlays/$hash.jpg")

    private fun cleanupOverlayCache(activeHash: String) {
        val directory = File(cacheDir, "reference-overlays")
        directory.listFiles()?.forEach { file ->
            val hash = file.nameWithoutExtension
            if (hash != activeHash || System.currentTimeMillis() - file.lastModified() > OVERLAY_CACHE_MAX_AGE_MS) {
                file.delete()
            }
        }
    }
}

private fun CachedReferences.toResolution(json: Json, fromCache: Boolean): ReferenceResolution {
    val target = json.parseToJsonElement(targetJson).jsonObject
    val color = json.parseToJsonElement(paletteJson).jsonObject
    return ReferenceResolution(
        contentHash = contentHash,
        analysisVersion = analysisV,
        analysis = json.parseToJsonElement(analysisJson).jsonObject,
        targetComposition = target,
        colorTarget = color,
        compositionAvailable = target["layoutSlots"]?.jsonArray?.isNotEmpty() == true,
        colorAvailable = color.isNotEmpty(),
        fromCache = fromCache,
    )
}
