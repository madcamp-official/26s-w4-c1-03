package com.gamdo.app.data

import com.gamdo.app.data.preset.ResolvedStyle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultFilterStateHolderTest {
    private fun reference(scope: ResolvedStyle.ReferenceScope) = ResolvedStyle.fromReference(
        hash = "hash",
        target = buildJsonObject { put("horizonPosition", 0.5) },
        colorTarget = buildJsonObject { put("exposureBias", 0.1) },
        scope = scope,
    )

    @Test
    fun `six presets and original never disappear after reference render failure`() {
        val holder = ResultFilterStateHolder()
        holder.synchronizeReference(reference(ResolvedStyle.ReferenceScope.BOTH))
        assertEquals(8, holder.state.value.items.size)
        assertTrue(holder.select(ResultFilterStateHolder.REFERENCE_FILTER_ID))
        holder.renderFailed()
        assertEquals(8, holder.state.value.items.size)
        assertEquals(ResultFilterStateHolder.REFERENCE_FILTER_ID, holder.state.value.selectedId)
        assertTrue(holder.state.value.renderState is FilterRenderState.Failed)
    }

    @Test
    fun `composition-only reference keeps base catalogue and cannot select reference color`() {
        val holder = ResultFilterStateHolder()
        holder.synchronizeReference(reference(ResolvedStyle.ReferenceScope.COMPOSITION))
        assertEquals(7, holder.state.value.items.size)
        assertFalse(holder.select(ResultFilterStateHolder.REFERENCE_FILTER_ID))
    }

    @Test
    fun `dismissing creation state cannot alter synchronized active reference`() {
        val holder = ResultFilterStateHolder()
        val active = reference(ResolvedStyle.ReferenceScope.COLOR)
        repeat(5) {
            holder.synchronizeReference(active)
            holder.select(ResultFilterStateHolder.REFERENCE_FILTER_ID)
            holder.renderSucceeded()
        }
        assertEquals(active, holder.state.value.activeReference)
        assertEquals(8, holder.state.value.items.size)
        assertEquals(ResultFilterStateHolder.REFERENCE_FILTER_ID, holder.state.value.selectedId)
    }
}
