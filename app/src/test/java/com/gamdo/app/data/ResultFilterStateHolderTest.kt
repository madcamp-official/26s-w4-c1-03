package com.gamdo.app.data

import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.Composition
import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.edit.LocalFilter
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

    @Test fun `reference colour remains highest priority over later session defaults`() {
        val holder = ResultFilterStateHolder()
        holder.synchronizeReference(reference())

        holder.setRecommendedDefaults(
            sessionFilterId = LocalFilter.NIGHT_STREET.filter.id,
            onboardingFilterId = LocalFilter.CLEAN_SOCIAL.filter.id,
        )

        assertEquals(ResultFilterStateHolder.REFERENCE_FILTER_ID, holder.state.value.recommendedDefaultFilterId)
    }

    @Test fun `composition only reference yields session then onboarding then original`() {
        val holder = ResultFilterStateHolder()
        holder.synchronizeReference(reference().copy(referenceScope = ResolvedStyle.ReferenceScope.COMPOSITION))

        holder.setRecommendedDefaults(
            sessionFilterId = LocalFilter.NIGHT_STREET.filter.id,
            onboardingFilterId = LocalFilter.CLEAN_SOCIAL.filter.id,
        )
        assertEquals(LocalFilter.NIGHT_STREET.filter.id, holder.state.value.recommendedDefaultFilterId)

        holder.setRecommendedDefaults(sessionFilterId = "unknown", onboardingFilterId = LocalFilter.CLEAN_SOCIAL.filter.id)
        assertEquals(LocalFilter.CLEAN_SOCIAL.filter.id, holder.state.value.recommendedDefaultFilterId)

        holder.setRecommendedDefaults(sessionFilterId = "unknown", onboardingFilterId = "also-unknown")
        assertEquals(LocalFilter.ORIGINAL.filter.id, holder.state.value.recommendedDefaultFilterId)
    }

    private fun reference() = ResolvedStyle(
        source = ResolvedStyle.Source.REFERENCE,
        sourceKey = "hash",
        displayName = "내 감도",
        composition = Composition("4:5", listOf(0.3, 0.6), "center", listOf(0.1, 0.2), 0.5, listOf(-5.0, 5.0), "natural", listOf(0.3, 0.6)),
        color = ColorParams(5500.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    )
}
