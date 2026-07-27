package com.gamdo.app.data

import com.gamdo.app.data.local.CachedReferencesDao
import com.gamdo.app.data.local.entity.CachedReferences
import com.gamdo.app.data.network.ReferenceAnalysisResponse
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory [CachedReferencesDao] fake, keyed exactly like the real table (PK = content_hash). */
private class FakeCachedReferencesDao : CachedReferencesDao {
    val rows = mutableMapOf<String, CachedReferences>()

    override suspend fun upsert(reference: CachedReferences) {
        rows[reference.contentHash] = reference
    }

    override suspend fun get(contentHash: String): CachedReferences? = rows[contentHash]

    override suspend fun recent(limit: Int): List<CachedReferences> =
        rows.values.sortedByDescending { it.createdAt }.take(limit)

    override suspend fun count(): Int = rows.size
}

/**
 * Counts calls so tests can assert "0 network calls on a cache hit". Optionally
 * appends to a shared [callLog] so a test can assert D8-5's call *order*
 * (sanitize strictly before upload), not just that both happened.
 */
private class FakeAnalysisClient(
    private val callLog: MutableList<String>? = null,
    private val response: (File) -> ReferenceAnalysisResponse,
) : ReferenceAnalysisClient {
    var callCount = 0
        private set
    val uploadedFiles = mutableListOf<File>()

    override suspend fun analyzeReference(image: File): ReferenceAnalysisResponse {
        callCount++
        uploadedFiles.add(image)
        callLog?.add("upload")
        return response(image)
    }
}

/** Counts calls so tests can assert the D8-5 sanitizer ran exactly once per upload. */
private class FakeSanitizer(
    private val callLog: MutableList<String>? = null,
) : ReferenceImageSanitizer {
    var callCount = 0
        private set

    override fun sanitize(file: File) {
        callCount++
        callLog?.add("sanitize")
    }
}

private fun sampleResponse(tag: String = "x") = ReferenceAnalysisResponse(
    analysis = buildJsonObject {
        put("peopleCount", 1)
        put("tag", tag)
    },
    targetComposition = buildJsonObject { put("horizonPosition", 0.5) },
    colorTarget = buildJsonObject { put("colorTemperature", 5200) },
)

/**
 * Covers the §5-1 completion criterion verbatim from `P1_Plan_1.md`: "같은 사진
 * 재선택 시 네트워크 호출 없음(로그 확인)". [ReferenceRepository] is deliberately
 * Context-free (see its class doc) so it can be constructed here directly with
 * fakes — no Robolectric, no MockWebServer needed.
 */
class ReferenceRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var tempDir: File
    private lateinit var dao: FakeCachedReferencesDao
    private lateinit var client: FakeAnalysisClient
    private lateinit var sanitizer: FakeSanitizer
    private lateinit var repository: ReferenceRepository

    @Before
    fun setUp() {
        tempDir = File.createTempFile("reference-repo-test", "").apply {
            delete()
            mkdirs()
        }
        dao = FakeCachedReferencesDao()
        client = FakeAnalysisClient { sampleResponse() }
        sanitizer = FakeSanitizer()
        repository = ReferenceRepository(
            cachedReferencesDao = dao,
            analysisClient = client,
            json = json,
            cacheDir = tempDir,
            sanitizer = sanitizer,
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `sha256Hex matches the known test vector for empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ReferenceRepository.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun `sha256Hex matches the known test vector for a short ascii string`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ReferenceRepository.sha256Hex("hello".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `cache miss uploads once, sanitizes once, and writes cached_references`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)

        val resolution = repository.resolveBytes(bytes)

        assertEquals(1, client.callCount)
        assertEquals(1, sanitizer.callCount)
        assertFalse(resolution.fromCache)
        assertEquals(ReferenceRepository.sha256Hex(bytes), resolution.contentHash)
        assertEquals(1, dao.count())
        assertEquals(resolution.contentHash, dao.rows.keys.single())
    }

    @Test
    fun `reselecting the same photo makes zero network calls on the second resolve`() = runBlocking {
        val bytes = byteArrayOf(9, 9, 9)

        val first = repository.resolveBytes(bytes)
        val second = repository.resolveBytes(bytes)

        assertEquals("exactly one upload for the miss, none for the repeat", 1, client.callCount)
        assertEquals("sanitizer must not run again on a cache hit", 1, sanitizer.callCount)
        assertFalse(first.fromCache)
        assertTrue("second resolve of the same bytes must be a cache hit", second.fromCache)
        assertEquals(first.contentHash, second.contentHash)
        assertEquals(first.analysis, second.analysis)
        assertEquals(first.targetComposition, second.targetComposition)
        assertEquals(first.colorTarget, second.colorTarget)
    }

    @Test
    fun `different photos hash differently and each triggers its own upload`() = runBlocking {
        val a = repository.resolveBytes(byteArrayOf(1))
        val b = repository.resolveBytes(byteArrayOf(2))

        assertEquals(2, client.callCount)
        assertNotEquals(a.contentHash, b.contentHash)
        assertEquals(2, dao.count())
    }

    @Test
    fun `resolveBytes cleans up its scratch file after a successful upload`() = runBlocking {
        repository.resolveBytes(byteArrayOf(5, 5, 5))

        assertTrue(
            "no leftover temp file expected, found: ${tempDir.list()?.toList()}",
            tempDir.listFiles()?.isEmpty() ?: true,
        )
    }

    @Test
    fun `cached analysis JSON round-trips through the resolution`() = runBlocking {
        client = FakeAnalysisClient { sampleResponse(tag = "round-trip") }
        repository = ReferenceRepository(dao, client, json, tempDir, sanitizer)

        val resolution = repository.resolveBytes(byteArrayOf(7))

        assertEquals("round-trip", resolution.analysis.getValue("tag").jsonPrimitive.content)
    }

    @Test
    fun `D8-5 - the sanitizer always runs before the upload, never after`() = runBlocking {
        val callLog = mutableListOf<String>()
        sanitizer = FakeSanitizer(callLog)
        client = FakeAnalysisClient(callLog) { sampleResponse() }
        repository = ReferenceRepository(dao, client, json, tempDir, sanitizer)

        repository.resolveBytes(byteArrayOf(3, 1, 4))

        assertEquals(listOf("sanitize", "upload"), callLog)
    }

    @Test
    fun `an upload failure leaves no cache entry and still cleans up the scratch file`() = runBlocking {
        client = FakeAnalysisClient { error("network down") }
        repository = ReferenceRepository(dao, client, json, tempDir, sanitizer)

        try {
            repository.resolveBytes(byteArrayOf(4, 2))
            org.junit.Assert.fail("expected the upload failure to propagate to the caller")
        } catch (expected: IllegalStateException) {
            // propagates as-is — the repository must not swallow it into a fake success
        }

        assertEquals("a failed upload must not poison the cache", 0, dao.count())
        assertTrue(
            "scratch file must still be cleaned up on failure",
            tempDir.listFiles()?.isEmpty() ?: true,
        )
    }
}
