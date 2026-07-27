package com.gamdo.app.data.preset

// `add` for Number/String/Boolean is an extension on JsonArrayBuilder, not a member:
// without this import `add(0.2)` binds to the member add(JsonElement) and fails to
// compile. That failure took the whole test source set down, so *no* unit test ran.
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedStyleTest {
    @Test
    fun `reference response maps composition and color into common contract`() {
        val style = ResolvedStyle.fromReference(
            hash = "abc",
            target = buildJsonObject {
                put("targetAspectRatio", "1:1")
                put("subjectPosition", "third_left")
                put("horizonPosition", 0.42)
                put("subjectScaleRange", buildJsonArray { add(0.2); add(0.5) })
            },
            colorTarget = buildJsonObject {
                put("colorTemperature", 6100)
                put("exposureBias", 0.2)
                put("saturation", 0.1)
            },
            strength = 1.7,
        )

        assertEquals(ResolvedStyle.Source.REFERENCE, style.source)
        assertEquals("abc", style.referenceHash)
        assertEquals("1:1", style.composition.targetAspectRatio)
        assertEquals("third_left", style.composition.subjectPosition)
        assertEquals(6100.0, style.color.colorTemperature, 0.01)
        assertEquals(1.0, style.strength, 0.01)
    }

    @Test
    fun `system preset remains a separate source`() {
        val preset = StylePreset(
            id = "clean_social",
            name = "Clean Social",
            displayName = "Clean Social",
            composition = Composition("4:5", listOf(0.2, 0.6), "center", listOf(0.1, 0.3), 0.5, listOf(-5.0, 5.0), "natural", listOf(0.3, 0.8)),
            color = ColorParams(5200.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )
        assertTrue(ResolvedStyle.fromPreset(preset).source == ResolvedStyle.Source.PRESET)
    }
}
