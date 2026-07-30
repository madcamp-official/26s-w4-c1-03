package com.gamdo.app.data.rescue

import com.gamdo.app.core.Ulid
import com.gamdo.app.data.CaptureRepository
import com.gamdo.app.data.network.EditJobResult
import com.gamdo.app.data.network.EditJobStatus
import com.gamdo.app.data.network.GamdoApiClient
import com.gamdo.app.data.network.JobTimeoutPolicy
import com.gamdo.app.data.network.RescueAnalysisResponse
import com.gamdo.app.data.GamdoPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

class RescueRepository(
    private val api: GamdoApiClient,
    private val captureRepository: CaptureRepository? = null,
    private val cacheDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    data class DownloadedResult(
        val resultId: String,
        val filePath: String,
        val rank: Int,
    )

    data class RescueRunResult(
        val jobId: String,
        val results: List<EditJobResult>,
        val downloaded: List<DownloadedResult>,
    )

    suspend fun analyze(
        image: File,
        captureRef: String,
        style: JsonObject,
        reference: JsonObject,
        context: RescueContext = RescueContext(),
    ): RescueAnalysisResponse =
        api.analyzeRescue(
            image, captureRef, style, reference,
            profileVersion = context.profileVersion,
            sceneContext = context.sceneContext.name,
            gamdoPolicy = json.encodeToJsonElement(GamdoPolicy.serializer(), context.policy).jsonObject,
            reinterpretationLevel = context.level.name,
        )

    suspend fun submitAndPoll(
        image: File,
        captureId: String?,
        captureRef: String,
        operation: JsonObject,
        style: JsonObject,
        onPolling: () -> Unit = {},
    ): RescueRunResult = withContext(Dispatchers.IO) {
        val jobId = "job_${Ulid.generate()}"
        api.createEditJob(jobId, captureRef, kotlinx.serialization.json.JsonArray(listOf(operation)), style, 2, image)
        // The upload has been accepted. From this point onward the UI must not
        // remain in the short-lived `Submitting` state: the server job is now
        // being polled and the cancel/progress UI needs to reflect that fact.
        onPolling()
        val started = System.currentTimeMillis()
        var status: EditJobStatus
        while (true) {
            if (JobTimeoutPolicy.hasTimedOut(started)) error("edit_job_timeout")
            status = api.getEditJob(jobId)
            when (status.status) {
                "done" -> break
                "fallback", "failed" -> error(status.failReason ?: "edit_job_fallback")
            }
            delay(1000)
        }
        val downloaded = status.results.mapIndexed { index, result ->
            val destination = File(cacheDir, "rescue_${jobId}_$index.png")
            api.downloadResult(result.url, destination)
            captureId?.let { id ->
                captureRepository?.recordDownloadedEditResult(
                    id, jobId, destination.absolutePath, index, result.seed,
                    result.validation ?: "{}", json.encodeToString(JsonElement.serializer(), operation),
                )
            }
            DownloadedResult("res_${jobId}_$index", destination.absolutePath, index)
        }
        RescueRunResult(jobId, status.results, downloaded)
    }
}
