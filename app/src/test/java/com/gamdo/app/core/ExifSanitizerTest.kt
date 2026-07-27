package com.gamdo.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [ExifTagAccess] fake — no real [android.media.ExifInterface] I/O. */
private class FakeExifTagAccess(initial: Map<String, String>) : ExifTagAccess {
    private val tags = initial.toMutableMap()
    var saveCount = 0
        private set

    override fun getAttribute(tag: String): String? = tags[tag]

    override fun setAttribute(tag: String, value: String?) {
        if (value == null) tags.remove(tag) else tags[tag] = value
    }

    override fun saveAttributes() {
        saveCount++
    }
}

/**
 * Exercises [ExifSanitizer.stripGps] — the pure removal algorithm — against a
 * fake [ExifTagAccess]. [ExifSanitizer.sanitizeFile] itself (the real
 * `ExifInterface(file)` adapter) is not covered here; it needs a real photo file
 * with real GPS tags and is a DONE-DEVICE item, not DONE-JVM (see class doc).
 */
class ExifSanitizerTest {

    @Test
    fun `removes every GPS tag and leaves non-GPS tags untouched`() {
        val access = FakeExifTagAccess(
            mapOf(
                "GPSLatitude" to "37/1,33/1,0/1",
                "GPSLatitudeRef" to "N",
                "GPSLongitude" to "127/1,0/1,0/1",
                "GPSLongitudeRef" to "E",
                "GPSAltitude" to "50/1",
                "Make" to "Pixel",
                "DateTime" to "2026:07:25 12:00:00",
                "Orientation" to "1",
            ),
        )

        val removed = ExifSanitizer.stripGps(access)

        assertEquals(5, removed)
        for (tag in ExifSanitizer.GPS_TAG_NAMES) {
            assertNull("$tag must be stripped", access.getAttribute(tag))
        }
        assertEquals("Pixel", access.getAttribute("Make"))
        assertEquals("2026:07:25 12:00:00", access.getAttribute("DateTime"))
        assertEquals("1", access.getAttribute("Orientation"))
        assertEquals("saveAttributes must be called exactly once", 1, access.saveCount)
    }

    @Test
    fun `no GPS data is a safe no-op and skips the write`() {
        val access = FakeExifTagAccess(mapOf("Make" to "Pixel", "Model" to "Pixel 8"))

        val removed = ExifSanitizer.stripGps(access)

        assertEquals(0, removed)
        assertEquals("Pixel", access.getAttribute("Make"))
        assertEquals("saveAttributes must not be called when nothing changed", 0, access.saveCount)
    }

    @Test
    fun `stripGps is idempotent on a second pass`() {
        val access = FakeExifTagAccess(mapOf("GPSLatitude" to "37/1,33/1,0/1"))

        val firstPass = ExifSanitizer.stripGps(access)
        val secondPass = ExifSanitizer.stripGps(access)

        assertEquals(1, firstPass)
        assertEquals(0, secondPass)
        assertEquals(1, access.saveCount)
    }

    @Test
    fun `GPS_TAG_NAMES has no duplicates and matches the real ExifInterface constants`() {
        assertEquals(31, ExifSanitizer.GPS_TAG_NAMES.size)
        assertEquals(ExifSanitizer.GPS_TAG_NAMES.size, ExifSanitizer.GPS_TAG_NAMES.distinct().size)
        assertTrue(ExifSanitizer.GPS_TAG_NAMES.all { it.startsWith("GPS") })
        assertTrue(ExifSanitizer.GPS_TAG_NAMES.containsAll(listOf("GPSLatitude", "GPSLongitude", "GPSLatitudeRef", "GPSLongitudeRef")))
    }
}
