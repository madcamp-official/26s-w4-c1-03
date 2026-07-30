package com.gamdo.app.ui.shoot

import com.gamdo.app.data.downloadThenClaim
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The two stages of collecting a friend's photos, split by the point of no return.
 *
 * `claim` deletes the session and its files on the server. Before it, everything is
 * retryable; after it, the bytes in the cache directory are the only copy in existence.
 * These tests pin both halves — that the claim cannot run early, and that the import
 * after it loses nothing when it partly fails.
 *
 * `java.io.File` is not `android.*`, so all of it runs under `testDebugUnitTest` against
 * a real temporary directory.
 */
class ReceivedPhotoImportTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * The download directory for one session.
     *
     * `mkdirs`, not `TemporaryFolder.newFolder`: the real
     * `receiveAndClaim` calls `File(cacheDir, sessionId).apply { mkdirs() }` once per
     * *photo* over the same directory, and `newFolder` throws the second time — a test
     * helper that cannot be called twice would not be modelling the code under test.
     */
    private fun session(name: String = "shoot_a"): File =
        File(temp.root, "received/$name").apply { mkdirs() }

    private fun photo(dir: File, name: String, bytes: Int = 8, modifiedAt: Long? = null): File =
        File(dir, name).apply {
            writeBytes(ByteArray(bytes))
            modifiedAt?.let { setLastModified(it) }
        }

    /** Records what it was asked to import and fails for the names given. */
    private class FakeImporter(private val failOn: Set<String> = emptySet()) : ReceivedPhotoImporter {
        val seen = mutableListOf<String>()
        override suspend fun import(file: File): String {
            seen += file.name
            if (file.name in failOn) throw IOException("disk full")
            return "cap_${file.nameWithoutExtension}"
        }
    }

    // -- before the claim: all or nothing --------------------------------------

    @Test
    fun `every photo is downloaded before the session is claimed`() = runBlocking {
        val order = mutableListOf<String>()
        val files = downloadThenClaim(
            photoIds = listOf("shot_1", "shot_2", "shot_3"),
            download = { id -> order += "download:$id"; photo(session(), "$id.png") },
            claim = { order += "claim" },
        )

        assertEquals(3, files.size)
        assertEquals(
            listOf("download:shot_1", "download:shot_2", "download:shot_3", "claim"),
            order,
        )
    }

    /**
     * The requirement in one test: three of five arrive, and the session must survive.
     *
     * If the claim ran on a partial download the remaining photos would be deleted
     * server-side with no copy anywhere — unrecoverable. It must not run at all.
     */
    @Test
    fun `a failed download aborts before the claim so nothing is lost`() {
        var claimed = false
        val downloaded = mutableListOf<String>()

        assertThrows(IOException::class.java) {
            runBlocking {
                downloadThenClaim(
                    photoIds = listOf("shot_1", "shot_2", "shot_3", "shot_4", "shot_5"),
                    download = { id ->
                        if (id == "shot_3") throw IOException("connection reset")
                        downloaded += id
                        photo(session(), "$id.png")
                    },
                    claim = { claimed = true },
                )
            }
        }

        assertFalse("the session must still exist on the server", claimed)
        assertEquals(listOf("shot_1", "shot_2"), downloaded)
    }

    @Test
    fun `an empty session still claims so the link does not linger`() = runBlocking {
        var claimed = false
        val files = downloadThenClaim(emptyList(), { error("no photos to download") }, { claimed = true })

        assertTrue(files.isEmpty())
        assertTrue(claimed)
    }

    // -- after the claim: lose nothing ----------------------------------------

    @Test
    fun `a whole batch is imported and its cache copies are removed`() = runBlocking {
        val dir = session()
        val files = listOf(photo(dir, "shot_1.png"), photo(dir, "shot_2.png"))
        val importer = FakeImporter()

        val result = importReceivedPhotos(files, importer)

        assertEquals(listOf("cap_shot_1", "cap_shot_2"), result.captureIds)
        assertEquals(0, result.failed)
        assertEquals("cap_shot_1", result.firstCaptureId)
        assertFalse(result.isTotalFailure)
        assertTrue("the bytes now live in the captures dir", files.none { it.exists() })
    }

    /**
     * The case the claim makes unrecoverable, so it must not be lost.
     *
     * Two of three import; the third's file stays exactly where it was, which is what
     * [pendingReceivedPhotos] reads on the next visit.
     */
    @Test
    fun `a failed import keeps its file for a later retry and does not abandon the rest`() = runBlocking {
        val dir = session()
        val first = photo(dir, "shot_1.png")
        val broken = photo(dir, "shot_2.png")
        val last = photo(dir, "shot_3.png")

        val result = importReceivedPhotos(listOf(first, broken, last), FakeImporter(failOn = setOf("shot_2.png")))

        assertEquals("the failure must not abort the ones after it", listOf("cap_shot_1", "cap_shot_3"), result.captureIds)
        assertEquals(1, result.failed)
        assertFalse(first.exists())
        assertTrue("the only copy of this photo must survive", broken.exists())
        assertFalse(last.exists())
        assertEquals(listOf(broken), pendingReceivedPhotos(temp.root.resolve("received")))
    }

    @Test
    fun `a batch that fails entirely reports it and keeps every file`() = runBlocking {
        val dir = session()
        val files = listOf(photo(dir, "shot_1.png"), photo(dir, "shot_2.png"))

        val result = importReceivedPhotos(files, FakeImporter(failOn = setOf("shot_1.png", "shot_2.png")))

        assertTrue(result.isTotalFailure)
        assertNull(result.firstCaptureId)
        assertEquals(2, result.failed)
        assertTrue(files.all { it.exists() })
    }

    @Test
    fun `importing never throws, whatever the importer does`() = runBlocking {
        val dir = session()
        val result = importReceivedPhotos(
            listOf(photo(dir, "shot_1.png")),
            ReceivedPhotoImporter { throw OutOfMemoryError("decode") },
        )

        assertTrue(result.isTotalFailure)
    }

    // -- reading the leftover invariant back ----------------------------------

    @Test
    fun `pending photos are the files no import has removed, oldest first`() {
        val root = File(temp.root, "received2").apply { mkdirs() }
        val a = File(root, "shoot_a").apply { mkdirs() }
        val b = File(root, "shoot_b").apply { mkdirs() }
        val second = photo(a, "shot_2.png", modifiedAt = 2_000_000L)
        val first = photo(b, "shot_1.png", modifiedAt = 1_000_000L)

        assertEquals(listOf(first, second), pendingReceivedPhotos(root))
    }

    @Test
    fun `an absent or empty received directory has nothing pending`() {
        assertEquals(emptyList<File>(), pendingReceivedPhotos(File(temp.root, "never-created")))
        assertEquals(emptyList<File>(), pendingReceivedPhotos(File(temp.root, "empty").apply { mkdirs() }))
    }

    @Test
    fun `zero-length and non-photo files are not offered for import`() {
        val root = File(temp.root, "received3").apply { mkdirs() }
        val dir = File(root, "shoot_a").apply { mkdirs() }
        File(dir, "truncated.png").writeBytes(ByteArray(0))
        File(dir, "notes.txt").writeText("x")
        val real = photo(dir, "shot_1.png")

        assertEquals(listOf(real), pendingReceivedPhotos(root))
    }

    @Test
    fun `a successful import leaves nothing pending`() = runBlocking {
        val root = File(temp.root, "received4").apply { mkdirs() }
        val dir = File(root, "shoot_a").apply { mkdirs() }
        val files = listOf(photo(dir, "shot_1.png"), photo(dir, "shot_2.png"))

        importReceivedPhotos(files, FakeImporter())

        assertEquals(emptyList<File>(), pendingReceivedPhotos(root))
    }
}
