package com.gamdo.app.detect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke test for the bundled model. Delegate selection is intentionally
 * internal: construction must succeed with GPU when available and still keep
 * the detector usable through its CPU/ML Kit fallback otherwise.
 */
@RunWith(AndroidJUnit4::class)
class EfficientDetSceneDetectorInstrumentedTest {

    @Test
    fun bundled_model_is_readable_and_detector_constructs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val asset = context.assets.open("models/efficientdet_lite0_coco_int8.tflite")
        val size = asset.use { it.available() }
        assertTrue("EfficientDet asset must be bundled", size > 4_000_000)

        val detector = EfficientDetSceneDetector(context)
        assertTrue(detector.modelId.contains("efficientdet-lite0"))
        detector.close()
    }
}
