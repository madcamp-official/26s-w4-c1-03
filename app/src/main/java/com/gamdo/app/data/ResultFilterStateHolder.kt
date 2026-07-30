package com.gamdo.app.data

import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.edit.LocalFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ResultFilterKind { ORIGINAL, PRESET, REFERENCE }

data class ResultFilterItem(val id: String, val kind: ResultFilterKind, val displayName: String)

sealed interface FilterRenderState {
    data object Idle : FilterRenderState
    data class Rendering(val filterId: String) : FilterRenderState
    data class Succeeded(val filterId: String) : FilterRenderState
    data class Failed(val filterId: String) : FilterRenderState
}

data class ResultFilterState(
    val items: List<ResultFilterItem>,
    val selectedId: String,
    /** Priority-resolved initial selection, independent from bitmap rendering. */
    val recommendedDefaultFilterId: String,
    val activeReference: ResolvedStyle?,
    val renderState: FilterRenderState,
)

/**
 * P2-owned state contract for the result strip. The catalogue never depends on
 * bitmap rendering, so a failed preview can fall back to original pixels without
 * removing the controls needed to choose another look.
 */
class ResultFilterStateHolder {
    private val _state = MutableStateFlow(
        ResultFilterState(
            items = baseItems,
            selectedId = LocalFilter.ORIGINAL.filter.id,
            recommendedDefaultFilterId = LocalFilter.ORIGINAL.filter.id,
            activeReference = null,
            renderState = FilterRenderState.Idle,
        ),
    )
    val state: StateFlow<ResultFilterState> = _state.asStateFlow()

    fun synchronizeReference(reference: ResolvedStyle?) {
        val hasColor = reference != null && reference.referenceScope != ResolvedStyle.ReferenceScope.COMPOSITION
        val items = if (hasColor) baseItems + referenceItem else baseItems
        val recommended = if (hasColor) REFERENCE_FILTER_ID else LocalFilter.ORIGINAL.filter.id
        val selected = _state.value.selectedId.takeIf { id -> items.any { it.id == id } } ?: recommended
        _state.value = _state.value.copy(
            items = items,
            selectedId = selected,
            recommendedDefaultFilterId = recommended,
            activeReference = reference,
        )
    }

    /** Applies the resolved precedence: reference → session → onboarding → original. */
    fun setRecommendedDefault(filterId: String?) {
        val current = _state.value
        val resolved = current.referenceColorAvailable()
            .let { hasReferenceColor ->
                if (hasReferenceColor) REFERENCE_FILTER_ID
                else filterId?.takeIf { id -> current.items.any { it.id == id } }
                    ?: LocalFilter.ORIGINAL.filter.id
            }
        _state.value = _state.value.copy(recommendedDefaultFilterId = resolved)
    }

    /**
     * Resolves all lower-priority profile sources in one operation.
     *
     * Keeping this policy in the holder prevents a later session/onboarding
     * update from overwriting an active reference's colour recommendation.
     */
    fun setRecommendedDefaults(sessionFilterId: String?, onboardingFilterId: String?) {
        val current = _state.value
        val resolved = when {
            current.referenceColorAvailable() -> REFERENCE_FILTER_ID
            sessionFilterId.isUsable(current) -> sessionFilterId!!
            onboardingFilterId.isUsable(current) -> onboardingFilterId!!
            else -> LocalFilter.ORIGINAL.filter.id
        }
        _state.value = current.copy(recommendedDefaultFilterId = resolved)
    }

    fun select(filterId: String): Boolean {
        if (_state.value.items.none { it.id == filterId }) return false
        _state.value = _state.value.copy(selectedId = filterId)
        return true
    }

    fun renderStarted(filterId: String = _state.value.selectedId) {
        _state.value = _state.value.copy(renderState = FilterRenderState.Rendering(filterId))
    }

    fun renderSucceeded(filterId: String = _state.value.selectedId) {
        _state.value = _state.value.copy(renderState = FilterRenderState.Succeeded(filterId))
    }

    fun renderFailed(filterId: String = _state.value.selectedId) {
        _state.value = _state.value.copy(renderState = FilterRenderState.Failed(filterId))
    }

    companion object {
        const val REFERENCE_FILTER_ID = "my_reference"
        private val baseItems = LocalFilter.entries.map { filter ->
            ResultFilterItem(
                id = filter.filter.id,
                kind = if (filter == LocalFilter.ORIGINAL) ResultFilterKind.ORIGINAL else ResultFilterKind.PRESET,
                displayName = filter.label,
            )
        }
        private val referenceItem = ResultFilterItem(REFERENCE_FILTER_ID, ResultFilterKind.REFERENCE, "내 감도")
    }

    private fun ResultFilterState.referenceColorAvailable(): Boolean =
        activeReference != null && activeReference.referenceScope != ResolvedStyle.ReferenceScope.COMPOSITION

    private fun String?.isUsable(state: ResultFilterState): Boolean =
        this != null && state.items.any { item -> item.id == this }
}
