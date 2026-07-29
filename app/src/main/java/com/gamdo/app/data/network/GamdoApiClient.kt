package com.gamdo.app.data.network

import com.gamdo.app.core.DeviceIdStore
import com.gamdo.app.data.preset.StylePreset
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import java.io.File
import java.io.IOException
import okhttp3.Request

/**
 * Retrofit contract for the versioned GAMDO API.
 *
 * The server intentionally remains stateless and identifies the device with
 * X-Device-Id. The client keeps that detail in one place so every request uses
 * the same persisted device UUID. The base URL includes /api/v1/.
 */
interface GamdoApiService {

    @GET("presets")
    suspend fun getPresets(
        @Header("X-Device-Id") deviceId: String,
    ): List<StylePreset>

    @Multipart
    @POST("references/analyze")
    suspend fun analyzeReference(
        @Header("X-Device-Id") deviceId: String,
        @Part image: MultipartBody.Part,
    ): ReferenceAnalysisResponse

    @Multipart
    @POST("rescue/analyze")
    suspend fun analyzeRescue(
        @Header("X-Device-Id") deviceId: String,
        @Part("captureRef") captureRef: okhttp3.RequestBody,
        @Part("styleParams") styleParams: okhttp3.RequestBody,
        @Part("referenceComposition") referenceComposition: okhttp3.RequestBody,
        @Part image: MultipartBody.Part,
    ): RescueAnalysisResponse

    @Multipart
    @POST("edit-jobs")
    suspend fun createEditJob(
        @Header("X-Device-Id") deviceId: String,
        @Part("jobId") jobId: okhttp3.RequestBody,
        @Part("captureRef") captureRef: okhttp3.RequestBody,
        @Part("operations") operations: okhttp3.RequestBody,
        @Part("styleParams") styleParams: okhttp3.RequestBody,
        @Part("resultCount") resultCount: okhttp3.RequestBody,
        @Part image: MultipartBody.Part,
    ): EditJobAccepted

    @GET("edit-jobs/{jobId}")
    suspend fun getEditJob(
        @Header("X-Device-Id") deviceId: String,
        @Path("jobId") jobId: String,
    ): EditJobStatus
}

@Serializable
data class EditJobAccepted(
    val jobId: String,
    val status: String,
)

@Serializable
data class EditJobStatus(
    val jobId: String,
    val status: String,
    val progressStage: String? = null,
    val results: List<EditJobResult> = emptyList(),
    val failReason: String? = null,
)

@Serializable
data class EditJobResult(
    val url: String,
    val generative: Boolean = false,
    val validation: String? = null,
    val seed: Int? = null,
)

@Serializable
data class ReferenceAnalysisResponse(
    val analysisVersion: Int = 1,
    val analysis: JsonObject,
    val targetComposition: JsonObject,
    val colorTarget: JsonObject,
    val capabilities: ReferenceCapabilities = ReferenceCapabilities(),
)

@Serializable
data class ReferenceCapabilities(
    val composition: Boolean = false,
    val color: Boolean = true,
)

@Serializable
data class RescueAnalysisResponse(
    val analysisVersion: Int = 1,
    val captureRef: String = "",
    val image: RescueImageInfo = RescueImageInfo(),
    val analysis: JsonObject = JsonObject(emptyMap()),
    val recommendations: List<RescueRecommendation> = emptyList(),
    val capabilities: RescueCapabilities = RescueCapabilities(),
)

@Serializable
data class RescueImageInfo(val width: Int = 0, val height: Int = 0)

@Serializable
data class RescueRecommendation(
    val id: String,
    val kind: String,
    val title: String,
    val reason: String,
    val operation: JsonObject? = null,
    val confidence: Double = 0.0,
)

@Serializable
data class RescueCapabilities(
    val localStyle: Boolean = true,
    val removeObjects: Boolean = false,
    val outpaint: Boolean = false,
)

/**
 * The error shape every endpoint shares (기능명세서 §10 "공통": `{code, message,
 * retryable}`). Gap A from the wave-0 audit — approved without a B signature
 * because the shape is already fixed by the spec, not invented here.
 */
@Serializable
data class ApiErrorEnvelope(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

/**
 * Thrown in place of Retrofit's [HttpException] for any non-2xx response, with
 * the response body parsed into the shared [envelope] contract. If the body
 * doesn't parse as `{code, message, retryable}` (a proxy error page, for
 * instance), [envelope] is a synthesized fallback derived from the HTTP status
 * alone — `retryable` defaults to true only for 408/429/5xx.
 *
 * §6-1's `pending_requests` retry queue (a later wave) is the intended consumer
 * of [envelope].retryable; this wave only introduces the parsed shape.
 */
class GamdoApiException(
    val envelope: ApiErrorEnvelope,
    val httpCode: Int,
    cause: Throwable,
) : Exception("HTTP $httpCode ${envelope.code}: ${envelope.message}", cause)

/**
 * The multipart content type for an image upload, from its file name.
 *
 * Every part used to go out as `application/octet-stream`. `gamdo-server`'s
 * `routes/references.py:21` and `routes/rescue.py:28` accept only
 * `{image/jpeg, image/png, image/webp}` and answer anything else with a
 * **non-retryable 415** — which the app surfaces as "이 사진은 사용할 수 없어요",
 * blaming the user's photo for a header we got wrong.
 *
 * It has not bitten yet only because the deployed CAMP-2 build predates those
 * checks; AI 2 was verified end to end against it on 2026-07-29. It becomes a
 * live break the moment the server is redeployed at current `main`, which is
 * exactly what we are asking for so `/rescue/analyze` and the O-9 GPS strip go
 * live. `routes/edit_jobs.py` has no such check, so generation was never
 * affected — which is why the gap stayed invisible.
 *
 * Unknown extensions fall back to JPEG rather than to a byte stream: every upload
 * path runs through `ReferenceImagePreprocessor` or `CaptureRepository` first and
 * both write JPEG, so the default states what is actually in the file instead of
 * refusing to answer.
 */
internal fun imageMediaTypeFor(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

/** Public app-facing client; API details stay out of Compose screens. */
class GamdoApiClient(
    baseUrl: String,
    private val deviceIdStore: DeviceIdStore,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    },
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {

    private val normalizedBaseUrl = normalizeBaseUrl(baseUrl)

    private val service: GamdoApiService = Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GamdoApiService::class.java)

    suspend fun getPresets(): List<StylePreset> = callApi {
        service.getPresets(deviceIdStore.getOrCreate())
    }

    suspend fun analyzeReference(image: File): ReferenceAnalysisResponse = callApi {
        service.analyzeReference(deviceIdStore.getOrCreate(), imagePart(image))
    }

    suspend fun createEditJob(
        jobId: String,
        captureRef: String,
        operations: JsonElement,
        styleParams: JsonObject = JsonObject(emptyMap()),
        resultCount: Int = 2,
        image: File,
    ): EditJobAccepted = callApi {
        val text = "text/plain".toMediaType()
        service.createEditJob(
            deviceId = deviceIdStore.getOrCreate(),
            jobId = jobId.toRequestBody(text),
            captureRef = captureRef.toRequestBody(text),
            operations = json.encodeToString(JsonElement.serializer(), operations).toRequestBody(text),
            styleParams = json.encodeToString(JsonObject.serializer(), styleParams).toRequestBody(text),
            resultCount = resultCount.toString().toRequestBody(text),
            image = imagePart(image),
        )
    }

    suspend fun getEditJob(jobId: String): EditJobStatus = callApi {
        service.getEditJob(deviceIdStore.getOrCreate(), jobId)
    }

    /** Downloads a result path returned by the job endpoint into app-private storage. */
    suspend fun downloadResult(resultUrl: String, destination: File) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = if (resultUrl.startsWith("http://") || resultUrl.startsWith("https://")) {
                resultUrl
            } else {
                normalizedBaseUrl.removeSuffix("api/v1/") + resultUrl.trimStart('/')
            }
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("result download failed: ${response.code}")
                val body = response.body ?: throw IOException("result response has no body")
                destination.parentFile?.mkdirs()
                destination.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
        }

    private fun imagePart(image: File): MultipartBody.Part {
        val body = image.asRequestBody(imageMediaTypeFor(image.name).toMediaType())
        return MultipartBody.Part.createFormData("image", image.name, body)
    }

    suspend fun analyzeRescue(
        image: File,
        captureRef: String,
        styleParams: JsonObject = JsonObject(emptyMap()),
        referenceComposition: JsonObject = JsonObject(emptyMap()),
    ): RescueAnalysisResponse = callApi {
        val text = "text/plain".toMediaType()
        service.analyzeRescue(
            deviceIdStore.getOrCreate(),
            captureRef.toRequestBody(text),
            json.encodeToString(JsonObject.serializer(), styleParams).toRequestBody(text),
            json.encodeToString(JsonObject.serializer(), referenceComposition).toRequestBody(text),
            imagePart(image),
        )
    }

    /** Runs [block], converting a raw [HttpException] into a parsed [GamdoApiException]. */
    private suspend fun <T> callApi(block: suspend () -> T): T =
        try {
            block()
        } catch (e: HttpException) {
            throw toApiException(e)
        }

    private fun toApiException(e: HttpException): GamdoApiException {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val envelope = body?.let { raw ->
            runCatching { json.decodeFromString<ApiErrorEnvelope>(raw) }.getOrNull()
        } ?: ApiErrorEnvelope(
            code = "http_${e.code()}",
            message = e.message() ?: "HTTP ${e.code()}",
            retryable = e.code() >= 500 || e.code() == 408 || e.code() == 429,
        )
        return GamdoApiException(envelope, e.code(), e)
    }

    companion object {
        fun normalizeBaseUrl(value: String): String =
            value.trim().let { if (it.endsWith('/')) it else "$it/" }
    }
}
