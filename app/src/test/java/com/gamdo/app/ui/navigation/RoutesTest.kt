package com.gamdo.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route table is plain strings, and a mismatch between a template and its
 * builder is not a compile error — it is a screen that never opens, found on a
 * device. These are the two things about W3.5-6's new route that can be checked
 * without one.
 */
class RoutesTest {

    /**
     * The template's placeholders turned into a regex that matches a real route —
     * per path segment, because `Regex.escape` wraps the whole string in `\Q…\E`
     * and a substitution afterwards would land inside the quoted region.
     */
    private fun matcher(template: String) = Regex(
        template.split("/").joinToString("/") { segment ->
            if (segment.startsWith("{") && segment.endsWith("}")) "[^/]+" else Regex.escape(segment)
        },
    )

    @Test
    fun `the device-photo builder produces a route its own template matches`() {
        val route = Routes.devicePhoto(1234L)

        assertEquals("device-photo/1234", route)
        assertTrue(route, matcher(Routes.DEVICE_PHOTO).matches(route))
    }

    @Test
    fun `the argument name in the template is the one the nav host reads`() {
        assertTrue(Routes.DEVICE_PHOTO.contains("{${Routes.ARG_MEDIA_STORE_ID}}"))
        assertTrue(Routes.RESULT.contains("{${Routes.ARG_CAPTURE_ID}}"))
    }

    @Test
    fun `a device photo cannot be routed to the capture screen, or the reverse`() {
        // `result/{captureId}` and `device-photo/{mediaStoreId}` are two segments
        // each, so a collision here would not be theoretical — whichever route was
        // registered first would swallow the other, and the O-12 branch rides
        // entirely on which one matched.
        assertFalse(matcher(Routes.RESULT).matches(Routes.devicePhoto(1234L)))
        assertFalse(matcher(Routes.DEVICE_PHOTO).matches(Routes.result("cap_01J")))
    }
}
