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

    @Deprecated(
        message = "레퍼런스 따라 찍기(§5-1/§5-2)는 remain_plan O-1로 컷됐다. 이 엔드포인트를 " +
            "다시 부르려면 진입점·업로드 고지·EXIF 위치 제거 가드가 함께 살아나야 하므로 " +
            "오너 결정이 선행이다. 배선 전에 remain_plan §1을 읽을 것.",
        level = DeprecationLevel.ERROR,
    )
    suspend fun analyzeReference(image: File): ReferenceAnalysisResponse = callApi {
        service.analyzeReference(deviceIdStore.getOrCreate(), imagePart(image))
    }

    @Deprecated(
        message = "생성 복구/사진 살리기(§5-3)는 remain_plan O-1로 컷됐다. 앱에서 나가는 " +
            "업로드 경로이므로 되살리려면 오너 결정이 선행이다. remain_plan §1 참조.",
        level = DeprecationLevel.ERROR,
    )
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

    @Deprecated(
        message = "생성 복구 폴링(§5-3)은 remain_plan O-1로 컷됐다. remain_plan §1 참조.",
        level = DeprecationLevel.ERROR,
    )
    suspend fun getEditJob(jobId: String): EditJobStatus = callApi {
        service.getEditJob(deviceIdStore.getOrCreate(), jobId)
    }

    /** Downloads a result path returned by the job endpoint into app-private storage. */
    @Deprecated(
        message = "생성 결과 다운로드(§5-3)는 remain_plan O-1로 컷됐다. remain_plan §1 참조.",
        level = DeprecationLevel.ERROR,
    )
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
        val body = image.asRequestBody("application/octet-stream".toMediaType())
        return MultipartBody.Part.createFormData("image", image.name, body)
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
