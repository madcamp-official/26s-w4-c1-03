package com.gamdo.app.data.rescue

import com.gamdo.app.core.Ulid
import com.gamdo.app.data.CaptureRepository
import com.gamdo.app.data.network.EditJobResult
import com.gamdo.app.data.network.EditJobStatus
import com.gamdo.app.data.network.GamdoApiClient
import com.gamdo.app.data.network.JobTimeoutPolicy
import com.gamdo.app.data.network.RescueAnalysisResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class RescueRepository(
    private val api: GamdoApiClient,
    private val captureRepository: CaptureRepository? = null,
    private val cacheDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    suspend fun analyze(image: File, captureRef: String, style: JsonObject, reference: JsonObject): RescueAnalysisResponse =
        api.analyzeRescue(image, captureRef, style, reference)

    suspend fun submitAndPoll(
        image: File,
        captureId: String,
        captureRef: String,
        operation: JsonObject,
        style: JsonObject,
    ): Pair<String, List<EditJobResult>> = withContext(Dispatchers.IO) {
        val jobId = "job_${Ulid.generate()}"
        val operationType = operation["type"]?.jsonPrimitive?.contentOrNull
        api.createEditJob(jobId, captureRef, kotlinx.serialization.json.JsonArray(listOf(operation)), style, if (operationType == "outpaint") 1 else 2, image)
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
        val saved = status.results.mapIndexed { index, result ->
            val destination = File(cacheDir, "rescue_${jobId}_$index.png")
            api.downloadResult(result.url, destination)
            captureRepository?.recordDownloadedEditResult(
                captureId, jobId, destination.absolutePath, index, result.seed,
                result.validation ?: "{}", json.encodeToString(JsonElement.serializer(), operation),
            )
            result
        }
        jobId to saved
    }
}
