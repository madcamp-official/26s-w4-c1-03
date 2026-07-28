package com.gamdo.app.guide

import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.Composition
import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.data.preset.ReferenceCompositionSlot
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedStyleAdapterTest {
    @Test
    fun `reference composition becomes the same StyleTarget contract`() {
        val style = ResolvedStyle(
            source = ResolvedStyle.Source.REFERENCE,
            sourceKey = "hash",
            displayName = "내 레퍼런스",
            composition = Composition("4:5", listOf(0.3, 0.6), "third_right", listOf(0.1, 0.2), 0.4, listOf(-4.0, 4.0), "portrait", listOf(0.3, 0.8)),
            color = ColorParams(5600.0, 0.1, 0.0, 0.1, 0.0, 0.0, 0.0, 0.0),
        )
        val target = style.toStyleTarget()
        assertEquals(2f / 3f, target.subjectAnchorX, 0.001f)
        assertEquals(0.4f, target.horizonPosition, 0.001f)
    }

    @Test
    fun `reference slots become a screen fixed reference target`() {
        val style = ResolvedStyle(
            source = ResolvedStyle.Source.REFERENCE,
            sourceKey = "hash",
            displayName = "내 레퍼런스",
            composition = Composition("4:5", listOf(0.3, 0.6), "center", listOf(0.1, 0.2), 0.4, listOf(-4.0, 4.0), "static", listOf(0.3, 0.8)),
            color = ColorParams(5600.0, 0.1, 0.0, 0.1, 0.0, 0.0, 0.0, 0.0),
            referenceSlots = listOf(
                ReferenceCompositionSlot("object", "generic_object", listOf(0.1, 0.2, 0.3, 0.4)),
                ReferenceCompositionSlot("object", "plate", listOf(0.55, 0.5, 0.25, 0.2)),
            ),
        )

        val target = style.toStyleTarget()

        assertEquals(2, target.referenceSlots.size)
        assertEquals(0.1f, target.referenceSlots.first().bounds.left, 0.001f)
        assertEquals(0.80f, target.referenceSlots.last().bounds.right, 0.001f)
    }

    @Test
    fun `color-only reference does not install a composition target`() {
        val style = ResolvedStyle(
            source = ResolvedStyle.Source.REFERENCE,
            sourceKey = "hash",
            displayName = "내 레퍼런스",
            composition = Composition("4:5", listOf(0.3, 0.6), "center", listOf(0.1, 0.2), 0.4, listOf(-4.0, 4.0), "static", listOf(0.3, 0.8)),
            color = ColorParams(5600.0, 0.1, 0.0, 0.1, 0.0, 0.0, 0.0, 0.0),
            referenceScope = ResolvedStyle.ReferenceScope.COLOR,
            referenceSlots = listOf(ReferenceCompositionSlot("object", "generic_object", listOf(0.1, 0.2, 0.3, 0.4))),
        )

        assertEquals(0, style.toStyleTarget().referenceSlots.size)
    }
}
