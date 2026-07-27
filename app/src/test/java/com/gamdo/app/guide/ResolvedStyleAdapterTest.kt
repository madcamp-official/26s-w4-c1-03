package com.gamdo.app.guide

import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.Composition
import com.gamdo.app.data.preset.ResolvedStyle
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
}
