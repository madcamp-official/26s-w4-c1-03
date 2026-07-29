package com.gamdo.app.data

import android.content.Context
import android.net.Uri
import com.gamdo.app.data.preset.ResolvedStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** User-triggered orchestration for AI 2. No picker or Compose dependency. */
class ReferenceCreateController(
    private val repository: ReferenceRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ReferenceCreateState>(ReferenceCreateState.Idle)
    val state: StateFlow<ReferenceCreateState> = _state.asStateFlow()

    private var selectedUri: Uri? = null
    private var analysisJob: Job? = null

    fun select(uri: Uri) {
        analysisJob?.cancel()
        selectedUri = uri
        _state.value = ReferenceCreateState.AwaitingConsent(uri)
    }

    fun confirmUpload(context: Context) {
        val uri = selectedUri ?: return
        analysisJob?.cancel()
        analysisJob = scope.launch {
            _state.value = ReferenceCreateState.Analyzing
            runCatching { repository.resolve(context, uri) }
                .onSuccess { _state.value = ReferenceCreateState.Preview(it) }
                .onFailure { error ->
                    _state.value = ReferenceCreateState.Error(retryable = error.isRetryable())
                }
        }
    }

    fun apply(scope: ResolvedStyle.ReferenceScope, strength: Double = ResolvedStyle.DEFAULT_STRENGTH) {
        val preview = (_state.value as? ReferenceCreateState.Preview)?.resolution ?: return
        android.util.Log.d("ReferenceFlow", "referenceApplied scope=$scope cached=${preview.fromCache}")
        analysisJob?.cancel()
        analysisJob = this.scope.launch {
            runCatching { repository.activate(settings, preview, scope, strength) }
                .onSuccess {
                    android.util.Log.d("ReferenceFlow", "activeReferenceRestored")
                    _state.value = ReferenceCreateState.Applied(it)
                }
                .onFailure { _state.value = ReferenceCreateState.Error(retryable = false) }
        }
    }

    fun cancel() {
        analysisJob?.cancel()
        selectedUri = null
        _state.value = ReferenceCreateState.Idle
    }

    fun clearActive() {
        analysisJob?.cancel()
        analysisJob = scope.launch {
            repository.clearActive(settings)
            selectedUri = null
            _state.value = ReferenceCreateState.Idle
        }
    }

    fun close() {
        analysisJob?.cancel()
    }

    private fun Throwable.isRetryable(): Boolean = this is java.io.IOException || cause is java.io.IOException
}

sealed interface ReferenceCreateState {
    data object Idle : ReferenceCreateState
    data class AwaitingConsent(val uri: Uri) : ReferenceCreateState
    data object Analyzing : ReferenceCreateState
    data class Preview(val resolution: ReferenceResolution) : ReferenceCreateState
    data class Applied(val style: ResolvedStyle) : ReferenceCreateState
    data class Error(val retryable: Boolean) : ReferenceCreateState
}
