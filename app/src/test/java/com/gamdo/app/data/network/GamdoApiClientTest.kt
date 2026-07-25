package com.gamdo.app.data.network

import org.junit.Assert.assertEquals
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
}
