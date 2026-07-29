package com.gamdo.app.data

import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.Composition
import com.gamdo.app.data.preset.ResolvedStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultFilterStateHolderTest {
    @Test fun `reference controls remain after selecting the reference filter`() {
        val holder = ResultFilterStateHolder()
        holder.synchronizeReference(reference())
        assertTrue(holder.select(ResultFilterStateHolder.REFERENCE_FILTER_ID))
        holder.renderFailed()

        val state = holder.state.value
        // Original + six system presets + the active reference.
        assertEquals(8, state.items.size)
        assertEquals(ResultFilterStateHolder.REFERENCE_FILTER_ID, state.selectedId)
        assertEquals(ResultFilterStateHolder.REFERENCE_FILTER_ID, state.recommendedDefaultFilterId)
    }

    private fun reference() = ResolvedStyle(
        source = ResolvedStyle.Source.REFERENCE,
        sourceKey = "hash",
        displayName = "내 감도",
        composition = Composition("4:5", listOf(0.3, 0.6), "center", listOf(0.1, 0.2), 0.5, listOf(-5.0, 5.0), "natural", listOf(0.3, 0.6)),
        color = ColorParams(5500.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    )
}
