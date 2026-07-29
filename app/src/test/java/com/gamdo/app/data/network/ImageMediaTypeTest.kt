package com.gamdo.app.data.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every image upload must declare a real image content type.
 *
 * `imagePart` used to send `application/octet-stream` for all three upload
 * endpoints. `gamdo-server`'s `routes/references.py:21` and `routes/rescue.py:28`
 * both reject anything outside `{image/jpeg, image/png, image/webp}` with a
 * **non-retryable 415**, so every 내 감도 만들기 and every 사진 살리기 analysis
 * would fail — and fail in the worst way, as "이 사진은 사용할 수 없어요",
 * blaming the user's photo for a header the client got wrong.
 *
 * This did not show up when AI 2 was verified end to end on 2026-07-29 because
 * the deployed server predates those checks. It is latent, not absent: the moment
 * CAMP-2 redeploys at current `main` — which we are asking for, so that AI 3's
 * `/rescue/analyze` and the O-9 GPS strip go live — a working feature breaks. The
 * fix has to land *before* that redeploy, not after.
 *
 * `routes/edit_jobs.py` has no such check, so generation was never affected. That
 * asymmetry is exactly why this could sit unnoticed.
 */
class ImageMediaTypeTest {

    @Test
    fun `jpeg is declared as an image and not as a byte stream`() {
        assertEquals("image/jpeg", imageMediaTypeFor("cap_01ABC.jpg"))
        assertEquals("image/jpeg", imageMediaTypeFor("photo.jpeg"))
    }

    @Test
    fun `png and webp keep their own types`() {
        assertEquals("image/png", imageMediaTypeFor("shot.png"))
        assertEquals("image/webp", imageMediaTypeFor("shot.webp"))
    }

    @Test
    fun `the extension is matched case-insensitively`() {
        assertEquals("image/jpeg", imageMediaTypeFor("IMG_0001.JPG"))
        assertEquals("image/png", imageMediaTypeFor("Screenshot.PNG"))
    }

    /**
     * The server's allowed set is exactly these three, so an unknown extension has
     * no honest answer. JPEG is the right default rather than octet-stream: every
     * path that reaches an upload has already been through
     * `ReferenceImagePreprocessor` or `CaptureRepository`, both of which write
     * JPEG, so the default describes what is actually in the file. Falling back to
     * `application/octet-stream` would reintroduce the exact 415 this exists to
     * prevent.
     */
    @Test
    fun `an unknown extension falls back to jpeg rather than a byte stream`() {
        assertEquals("image/jpeg", imageMediaTypeFor("reference"))
        assertEquals("image/jpeg", imageMediaTypeFor("noextension."))
        assertEquals("image/jpeg", imageMediaTypeFor("archive.heic"))
    }

    /** A name that is all extension, or empty, must not throw on the upload path. */
    @Test
    fun `degenerate names do not throw`() {
        assertEquals("image/jpeg", imageMediaTypeFor(""))
        assertEquals("image/png", imageMediaTypeFor(".png"))
    }

    /**
     * The whole point: whatever comes out is something the server accepts. If a
     * future edit reintroduces a non-image type, this fails regardless of which
     * branch produced it.
     */
    /**
     * The function above being correct is worth nothing if `imagePart` stops
     * calling it, and no JVM test can observe that — building a real
     * `MultipartBody.Part` needs OkHttp against a live request.
     *
     * So this reads the source, the way `ObjectDetectorWiringTest` does for the
     * detector wiring. The property it pins is narrow and load-bearing: the upload
     * path must not name a non-image content type anywhere. That is the exact edit
     * that would reintroduce the 415, and it would otherwise be invisible until a
     * device hit a redeployed server.
     */
    @Test
    fun `the upload path declares no non-image content type`() {
        val source = File("src/main/java/com/gamdo/app/data/network/GamdoApiClient.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/gamdo/app/data/network/GamdoApiClient.kt")
        assertTrue("GamdoApiClient.kt not found from ${System.getProperty("user.dir")}", source.isFile)

        val code = source.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

        val offenders = code.lines().withIndex()
            .filter { (_, line) -> "application/octet-stream" in line }
            .map { (i, line) -> "${source.name}:${i + 1}: ${line.trim()}" }

        assertEquals(
            "The image part must declare a real image type. routes/references.py:21 and " +
                "routes/rescue.py:28 answer anything outside {image/jpeg, image/png, image/webp} " +
                "with a non-retryable 415, which reaches the user as \"이 사진은 사용할 수 없어요\".\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )

        assertTrue(
            "imagePart must derive its media type from the file name",
            "imageMediaTypeFor(image.name)" in code,
        )
    }

    @Test
    fun `every result is in the set the server allows`() {
        val allowed = setOf("image/jpeg", "image/png", "image/webp")
        val names = listOf(
            "a.jpg", "a.jpeg", "a.png", "a.webp", "a.JPG", "a.WEBP",
            "a.heic", "a.gif", "a.bmp", "a", "", ".", "..", "a.tar.gz",
        )
        for (name in names) {
            val actual = imageMediaTypeFor(name)
            assert(actual in allowed) {
                "imageMediaTypeFor(\"$name\") returned $actual, which routes/references.py " +
                    "and routes/rescue.py would reject with 415"
            }
        }
    }
}
