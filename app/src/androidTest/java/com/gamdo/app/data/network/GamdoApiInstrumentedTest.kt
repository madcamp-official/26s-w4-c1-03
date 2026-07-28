// 대상이 remain_plan O-1로 컷돼 @Deprecated(ERROR)가 붙었다. 테스트는 **의도적으로 남긴다** —
// §1-6에서 서명한 /edit-jobs 요청·응답 계약이 여기서만 실호출로 검증된다.
// 폐기를 걷어내고 경로를 되살리는 사람에게 이 테스트가 안전망이 된다. 이 suppress를 지우려면
// 테스트도 함께 지워야 하고, 그건 컷을 되돌리는 것이 아니라 되돌릴 수단을 버리는 것이다.
@file:Suppress("DEPRECATION_ERROR")

package com.gamdo.app.data.network

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamdo.app.core.DeviceIdStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires adb reverse tcp:18000 tcp:8000 and a running GAMDO FastAPI server. */
@RunWith(AndroidJUnit4::class)
class GamdoApiInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun client(): GamdoApiClient = GamdoApiClient(
        baseUrl = "http://127.0.0.1:18000/api/v1/",
        deviceIdStore = DeviceIdStore(context),
    )

    @Test
    fun presets_request_uses_versioned_path_and_device_header() = runBlocking {
        val presets = client().getPresets()
        assertEquals(6, presets.size)
    }

    @Test
    fun edit_job_reaches_gpu_or_explicit_fallback() = runBlocking {
        val image = File(context.cacheDir, "api-smoke.jpg")
        context.assets.open("presets/clean_social.jpg").use { input ->
            image.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            val jobId = "job_android_${System.currentTimeMillis()}"
            val accepted = client().createEditJob(
                jobId = jobId,
                captureRef = "cap_android_smoke",
                operations = buildJsonArray {
                    add(buildJsonObject {
                        put("type", "remove_objects")
                        put("masks", buildJsonArray {
                            add(buildJsonObject {
                                put("rect", buildJsonObject {
                                    put("x", 0.1f)
                                    put("y", 0.1f)
                                    put("width", 0.1f)
                                    put("height", 0.1f)
                                })
                            })
                        })
                    })
                },
                styleParams = buildJsonObject { put("v", 1) },
                resultCount = 1,
                image = image,
            )
            assertEquals(jobId, accepted.jobId)
            assertEquals("queued", accepted.status)

            val terminal = withTimeout(TimeUnit.MINUTES.toMillis(4)) {
                var status = client().getEditJob(jobId)
                while (status.status !in setOf("done", "fallback", "failed", "canceled")) {
                    kotlinx.coroutines.delay(1_000)
                    status = client().getEditJob(jobId)
                }
                status
            }
            assertTrue(terminal.status == "done" || terminal.status == "fallback")
        } finally {
            image.delete()
        }
    }
}
