package com.gamdo.app.data.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamdoApiClientTest {

    @Test
    fun `base URL is normalized for Retrofit`() {
        assertEquals(
            "http://10.0.2.2:8000/api/v1/",
            GamdoApiClient.normalizeBaseUrl("  http://10.0.2.2:8000/api/v1  "),
        )
        assertEquals(
            "http://server/api/v1/",
            GamdoApiClient.normalizeBaseUrl("http://server/api/v1/"),
        )
    }

    // Gap A (TEAM.md §8, approved without a B signature — 기능명세서 §10's
    // "공통" error shape is already fixed): the client must decode the shared
    // {code, message, retryable} contract every endpoint's error responses use.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ApiErrorEnvelope decodes the shared {code,message,retryable} contract`() {
        val envelope = json.decodeFromString<ApiErrorEnvelope>(
            """{"code":"invalid_image","message":"이미지를 읽을 수 없어요","retryable":false}""",
        )

        assertEquals("invalid_image", envelope.code)
        assertEquals("이미지를 읽을 수 없어요", envelope.message)
        assertFalse(envelope.retryable)
    }

    @Test
    fun `ApiErrorEnvelope tolerates unknown extra fields`() {
        val envelope = json.decodeFromString<ApiErrorEnvelope>(
            """{"code":"rate_limited","message":"잠시 후 다시 시도해주세요","retryable":true,"traceId":"abc123"}""",
        )

        assertEquals("rate_limited", envelope.code)
        assertTrue(envelope.retryable)
    }

    @Test
    fun `GamdoApiException message embeds the http code and envelope code`() {
        val envelope = ApiErrorEnvelope(code = "server_error", message = "문제가 발생했어요", retryable = true)
        val exception = GamdoApiException(envelope, httpCode = 503, cause = RuntimeException("boom"))

        assertTrue(exception.message!!.contains("503"))
        assertTrue(exception.message!!.contains("server_error"))
    }
}
