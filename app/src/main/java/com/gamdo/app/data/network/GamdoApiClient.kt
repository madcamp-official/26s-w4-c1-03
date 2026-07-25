package com.gamdo.app.data.network

import com.gamdo.app.core.DeviceIdStore
import com.gamdo.app.data.preset.StylePreset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

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
        @Part jobId: okhttp3.RequestBody,
        @Part captureRef: okhttp3.RequestBody,
        @Part operations: okhttp3.RequestBody,
        @Part styleParams: okhttp3.RequestBody,
        @Part resultCount: okhttp3.RequestBody,
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
    val analysis: JsonObject,
    val targetComposition: JsonObject,
    val colorTarget: JsonObject,
)

/** Public app-facing client; API details stay out of Compose screens. */
class GamdoApiClient(
    baseUrl: String,
    private val deviceIdStore: DeviceIdStore,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    },
    httpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {

    private val service: GamdoApiService = Retrofit.Builder()
        .baseUrl(normalizeBaseUrl(baseUrl))
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GamdoApiService::class.java)

    suspend fun getPresets(): List<StylePreset> =
        service.getPresets(deviceIdStore.getOrCreate())

    suspend fun analyzeReference(image: File): ReferenceAnalysisResponse =
        service.analyzeReference(deviceIdStore.getOrCreate(), imagePart(image))

    suspend fun createEditJob(
        jobId: String,
        captureRef: String,
        operations: JsonElement,
        styleParams: JsonObject = JsonObject(emptyMap()),
        resultCount: Int = 2,
        image: File,
    ): EditJobAccepted {
        val text = "text/plain".toMediaType()
        return service.createEditJob(
            deviceId = deviceIdStore.getOrCreate(),
            jobId = jobId.toRequestBody(text),
            captureRef = captureRef.toRequestBody(text),
            operations = json.encodeToString(JsonElement.serializer(), operations).toRequestBody(text),
            styleParams = json.encodeToString(JsonObject.serializer(), styleParams).toRequestBody(text),
            resultCount = resultCount.toString().toRequestBody(text),
            image = imagePart(image),
        )
    }

    suspend fun getEditJob(jobId: String): EditJobStatus =
        service.getEditJob(deviceIdStore.getOrCreate(), jobId)

    private fun imagePart(image: File): MultipartBody.Part {
        val body = image.asRequestBody("application/octet-stream".toMediaType())
        return MultipartBody.Part.createFormData("image", image.name, body)
    }

    companion object {
        fun normalizeBaseUrl(value: String): String =
            value.trim().let { if (it.endsWith('/')) it else "$it/" }
    }
}
