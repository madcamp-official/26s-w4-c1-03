package com.gamdo.app.data.rescue

import com.gamdo.app.data.network.EditJobResult
import com.gamdo.app.data.network.RescueAnalysisResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.io.File

sealed interface RescueState {
    data object Idle : RescueState
    data object Analyzing : RescueState
    data class Recommendations(val response: RescueAnalysisResponse) : RescueState
    data class Editing(val operation: JsonObject) : RescueState
    data object Submitting : RescueState
    data object Polling : RescueState
    data class Candidates(val jobId: String, val results: List<EditJobResult>) : RescueState
    data class LocalFallback(val reason: String) : RescueState
}

/** UI-agnostic state holder. The P1 result screen can collect this flow. */
class RescueController(private val repository: RescueRepository) {
    private val mutableState = MutableStateFlow<RescueState>(RescueState.Idle)
    val state: StateFlow<RescueState> = mutableState.asStateFlow()

    suspend fun analyze(image: File, captureRef: String, style: JsonObject = JsonObject(emptyMap()), reference: JsonObject = JsonObject(emptyMap())) {
        mutableState.value = RescueState.Analyzing
        runCatching { repository.analyze(image, captureRef, style, reference) }
            .onSuccess { mutableState.value = RescueState.Recommendations(it) }
            .onFailure { mutableState.value = RescueState.LocalFallback("analysis_unavailable") }
    }

    fun choose(operation: JsonObject) { mutableState.value = RescueState.Editing(operation) }

    suspend fun submit(image: File, captureId: String, captureRef: String, operation: JsonObject, style: JsonObject = JsonObject(emptyMap())) {
        mutableState.value = RescueState.Submitting
        runCatching { repository.submitAndPoll(image, captureId, captureRef, operation, style) }
            .onSuccess { mutableState.value = RescueState.Candidates(it.first, it.second) }
            .onFailure { mutableState.value = RescueState.LocalFallback("generation_unavailable") }
    }

    fun reset() { mutableState.value = RescueState.Idle }
}
