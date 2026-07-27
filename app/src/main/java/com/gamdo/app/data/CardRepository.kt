package com.gamdo.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Loads the bundled onboarding card deck (assets/cards.json, 16 v1 cards) into the
 * [CardFeature] shape `ProfileEngine` consumes. Mirrors `PresetRepository.loadBundledPresets()`:
 * a synchronous asset read with no Room dependency, because the onboarding pick step
 * needs the deck before any DB write has necessarily completed.
 *
 * The serialization model (`CardJson`/`CardsFile`) was originally private inside
 * `harness/P2ValueDumpTest.kt` (read-only, kept there as-is for the B-team cross-check);
 * this is a copy promoted to main, not a move.
 */
class CardRepository(
    private val context: Context,
    private val json: Json,
) {
    /** Decodes the bundled 16 cards straight from assets. */
    fun loadBundledCards(): List<CardFeature> {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return decodeCards(text, json)
    }

    private companion object {
        const val ASSET_NAME = "cards.json"
    }
}

@Serializable
internal data class CardJson(
    val id: String,
    val subjectScale: Float,
    val subjectPosition: Float,
    val headroom: Float,
    val backgroundRatio: Float,
    val brightness: Float,
    val lightType: String,
    val colorTemperature: Float,
    val saturation: Float,
    val contrast: Float,
    val sharpness: Float,
    val grain: Float,
    val candidness: Float,
    val framing: Float,
) {
    fun toFeature() = CardFeature(
        id, subjectScale, subjectPosition, headroom, backgroundRatio, brightness,
        lightType, colorTemperature, saturation, contrast, sharpness, grain, candidness, framing,
    )
}

@Serializable
internal data class CardsFile(val v: Int, val cards: List<CardJson>)

/**
 * Pure decode step, split out from the [Context]-dependent asset read so it can be
 * unit-tested on the JVM (this module has no Robolectric/Mockito — Android `Context`
 * throws "not mocked" under plain `testDebugUnitTest`). See `CardRepositoryTest`, which
 * reads `src/main/assets/cards.json` straight off disk the same way
 * `harness/P2ValueDumpTest.kt` does.
 */
internal fun decodeCards(text: String, json: Json): List<CardFeature> =
    json.decodeFromString<CardsFile>(text).cards.map { it.toFeature() }
