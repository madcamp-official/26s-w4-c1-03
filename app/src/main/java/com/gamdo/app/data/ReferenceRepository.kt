// 이 파일 전체가 remain_plan O-1로 컷된 §5-1 경로다. 같은 컷으로 폐기된 ExifSanitizer를
// import·호출하는 것은 옳다 — 레퍼런스 경로가 부활하면 D8-5 가드도 함께 부활해야 하므로
// 둘의 결합은 끊지 않고 통째로 폐기 상태에 둔다. 파일 수준 suppress인 이유는 클래스 수준
// 어노테이션이 import 문까지는 덮지 못하기 때문이다.
@file:Suppress("DEPRECATION_ERROR")

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
import kotlinx.serialization.json.JsonObject
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
 * §5-1 resolution result — either a `cached_references` hit or a fresh
 * `/references/analyze` response, normalized to the same shape either way.
 * [targetComposition] and [colorTarget] are what §5-2 (wave 2, out of scope in
 * this repository) will feed into `AlignmentEngine`'s `StyleTarget` and the
 * result screen's reference-color step, respectively.
 */
data class ReferenceResolution(
    val contentHash: String,
    val analysis: JsonObject,
    val targetComposition: JsonObject,
    val colorTarget: JsonObject,
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
@Deprecated(
    message = "§5-1 레퍼런스 따라 찍기는 remain_plan O-1로 컷됐다. AppContainer에서 배선을 " +
        "걷어냈으므로 프로덕션 생성자 호출이 0이다. 되살리려면 카메라 진입점·업로드 고지 " +
        "문구·D8-5 가드가 함께 필요하고, 그건 오너 결정 사항이다. remain_plan §1 참조.",
    level = DeprecationLevel.ERROR,
)
class ReferenceRepository(
    private val cachedReferencesDao: CachedReferencesDao,
    private val analysisClient: ReferenceAnalysisClient,
    private val json: Json,
    private val cacheDir: File,
    private val sanitizer: ReferenceImageSanitizer = ReferenceImageSanitizer(ExifSanitizer::sanitizeFile),
) {

    suspend fun activate(
        settings: SettingsRepository,
        resolution: ReferenceResolution,
        scope: ResolvedStyle.ReferenceScope = ResolvedStyle.ReferenceScope.BOTH,
        strength: Double = ResolvedStyle.DEFAULT_STRENGTH,
    ): ResolvedStyle {
        settings.saveActiveReference(resolution.contentHash, scope.name.lowercase(), strength)
        cleanupCache(resolution.contentHash)
        return ResolvedStyle.fromReference(
            hash = resolution.contentHash,
            target = resolution.targetComposition,
            colorTarget = resolution.colorTarget,
            scope = scope,
            strength = strength,
        )
    }

    suspend fun cleanupCache(activeHash: String? = null) {
        val active = activeHash.orEmpty()
        val now = System.currentTimeMillis()
        cachedReferencesDao.deleteExpiredInactive(now - CACHE_MAX_AGE_MS, active)
        cachedReferencesDao.trimInactive(MAX_CACHE_ENTRIES, active)
    }

    /**
     * §5-1 entry point: [uri] just came back from the system photo picker.
     * Reads the picked bytes through [context]'s resolver and delegates to
     * [resolveBytes]. Not unit-tested — needs a real `ContentResolver`; verify
     * on-device (DONE-DEVICE).
     */
    suspend fun resolve(context: Context, uri: Uri): ReferenceResolution = withContext(Dispatchers.IO) {
        val bytes = context.applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
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
        cachedReferencesDao.get(hash)?.let { cached ->
            return@withContext cached.toResolution(json, fromCache = true)
        }

        cacheDir.mkdirs()
        val tempFile = File(cacheDir, "$hash.jpg")
        try {
            tempFile.writeBytes(imageBytes)
            // D8-5 blocker: unconditional and not reorderable — every byte that
            // leaves the device through analyzeReference() must go through the
            // sanitizer first.
            sanitizer.sanitize(tempFile)
            val response = analysisClient.analyzeReference(tempFile)

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
                createdAt = System.currentTimeMillis(),
            )
            cachedReferencesDao.upsert(entry)
            entry.toResolution(json, fromCache = false)
        } finally {
            tempFile.delete()
        }
    }

    companion object {
        const val MAX_CACHE_ENTRIES = 20
        const val CACHE_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
        /** SHA-256 of [bytes] as lowercase hex — the `cached_references` cache key. */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = StringBuilder(digest.size * 2)
            for (b in digest) hex.append("%02x".format(b.toInt() and 0xFF))
            return hex.toString()
        }
    }
}

private fun CachedReferences.toResolution(json: Json, fromCache: Boolean): ReferenceResolution =
    ReferenceResolution(
        contentHash = contentHash,
        analysis = json.parseToJsonElement(analysisJson).jsonObject,
        targetComposition = json.parseToJsonElement(targetJson).jsonObject,
        colorTarget = json.parseToJsonElement(paletteJson).jsonObject,
        fromCache = fromCache,
    )
