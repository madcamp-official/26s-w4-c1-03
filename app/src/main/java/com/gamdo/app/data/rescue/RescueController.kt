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
    data class Candidates(
        val jobId: String,
        val results: List<EditJobResult>,
        val downloaded: List<RescueRepository.DownloadedResult> = emptyList(),
    ) : RescueState
    data class LocalFallback(val reason: String) : RescueState
}

/** UI-agnostic state holder. The P1 result screen can collect this flow. */
class RescueController(private val repository: RescueRepository) {
    private val mutableState = MutableStateFlow<RescueState>(RescueState.Idle)
    val state: StateFlow<RescueState> = mutableState.asStateFlow()

    suspend fun analyze(
        image: File,
        captureRef: String,
        style: JsonObject = JsonObject(emptyMap()),
        reference: JsonObject = JsonObject(emptyMap()),
        context: RescueContext = RescueContext(),
    ) {
        android.util.Log.d("RescueController", "rescueAnalyzeStarted")
        mutableState.value = RescueState.Analyzing
        runCatching { repository.analyze(image, captureRef, style, reference, context) }
            .onSuccess {
                android.util.Log.d("RescueController", "recommendationShown count=${it.recommendations.size}")
                mutableState.value = RescueState.Recommendations(it)
            }
            .onFailure {
                android.util.Log.w("RescueController", "rescueAnalyzeFailed", it)
                mutableState.value = RescueState.LocalFallback("analysis_unavailable")
            }
    }

    fun choose(operation: JsonObject) {
        android.util.Log.d("RescueController", "operationChosen type=${operation["type"]}")
        mutableState.value = RescueState.Editing(operation)
    }

    suspend fun submit(image: File, captureId: String?, captureRef: String, operation: JsonObject, style: JsonObject = JsonObject(emptyMap())) {
        android.util.Log.d("RescueController", "editJobSubmitted capture=${captureId != null} operation=${operation["type"]}")
        mutableState.value = RescueState.Submitting
        runCatching { repository.submitAndPoll(image, captureId, captureRef, operation, style) }
            .onSuccess {
                android.util.Log.d("RescueController", "editJobCompleted job=${it.jobId} candidates=${it.downloaded.size}")
                mutableState.value = RescueState.Candidates(it.jobId, it.results, it.downloaded)
            }
            .onFailure {
                android.util.Log.w("RescueController", "editJobFailed", it)
                mutableState.value = RescueState.LocalFallback("generation_unavailable")
            }
    }

    fun reset() { mutableState.value = RescueState.Idle }
}
