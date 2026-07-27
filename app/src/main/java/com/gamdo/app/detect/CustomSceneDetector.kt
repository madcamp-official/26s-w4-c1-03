package com.gamdo.app.detect

/**
 * Optional seam for a GAMDO-trained TFLite detector. The app can ship without
 * the model; [SceneDetector] then continues with the ML Kit fallback.
 */
interface CustomSceneDetector : ObjectSceneDetector {
    val modelId: String
}

data class CustomSceneDetectorConfig(
    val enabled: Boolean = false,
    val modelAsset: String = "models/gamdo_scene.tflite",
    val minimumConfidence: Float = 0.65f,
)
