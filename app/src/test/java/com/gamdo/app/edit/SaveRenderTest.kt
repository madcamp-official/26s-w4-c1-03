package com.gamdo.app.edit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The save pass never writes anything but the full-resolution render.
 *
 * The defect this pins: when the full decode failed, the screen fell back to the
 * preview bitmap — capped at 1440px against a 3630px capture — wrote *that* to the
 * gallery, and told the user the photo was saved. Every assertion below is about
 * the difference between "did not save" and "saved something else".
 *
 * `String` stands in for `Bitmap` on purpose. The pass is three stages and a
 * decision about their failures; none of that needs pixels, and `android.graphics`
 * does not run in a JVM test.
 */
class SaveRenderTest {

    private val full = "full-3630px"

    @Test
    fun `a failed decode saves nothing, even with a preview sitting right there`() = runBlocking {
        var styled = 0
        val outcome = renderForSave(
            decodeFullResolution = { null },
            correct = { fail("correct must not run without a source"); it },
            style = { styled++; it },
        )
        assertEquals(SaveRender.SourceUnreadable, outcome)
        assertEquals("nothing to style means nothing was rendered", 0, styled)
    }

    @Test
    fun `a successful decode is what gets written, corrected then styled`() = runBlocking {
        val outcome = renderForSave(
            decodeFullResolution = { full },
            correct = { "$it+levelled" },
            style = { "$it+look" },
        )
        assertEquals(SaveRender.Ready("full-3630px+levelled+look"), outcome)
    }

    /**
     * Order is not cosmetic. The correction is a geometry pass — it rotates and
     * crops — so applying the look first would filter pixels the crop then throws
     * away, and the grain and vignette terms would land relative to the uncropped
     * frame instead of the saved one.
     */
    @Test
    fun `correction runs before the look, not after`() = runBlocking {
        val order = mutableListOf<String>()
        renderForSave(
            decodeFullResolution = { order += "decode"; full },
            correct = { order += "correct"; it },
            style = { order += "style"; it },
        )
        assertEquals(listOf("decode", "correct", "style"), order)
    }

    /**
     * Deliberate, and inherited from the open path: an auto-correction nobody asked
     * for must not be the reason a photo cannot be saved. Pinned so that whoever
     * decides the saved file has to match the preview exactly changes it here and
     * sees this test, rather than discovering the difference on a device.
     */
    @Test
    fun `a correction that throws still saves the full-resolution frame, and says so`() = runBlocking {
        var reported: Throwable? = null
        val outcome = renderForSave(
            decodeFullResolution = { full },
            correct = { error("levelling blew up") },
            style = { "$it+look" },
            onCorrectionFailed = { reported = it },
        )
        assertEquals(SaveRender.Ready("full-3630px+look"), outcome)
        assertTrue("the skipped correction has to reach the log", reported is IllegalStateException)
    }

    /**
     * The look is the user's own choice. Saving without it would be the same lie in
     * a different coat, so a failure here reaches the caller's error handling
     * instead of being swallowed into a "saved" that is not what was on screen.
     */
    @Test
    fun `a look that throws is not swallowed`() {
        val thrown = runCatching {
            runBlocking {
                renderForSave(
                    decodeFullResolution = { full },
                    correct = { it },
                    style = { error("filter blew up") },
                )
            }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }
}
