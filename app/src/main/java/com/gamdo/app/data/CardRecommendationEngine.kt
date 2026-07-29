package com.gamdo.app.data

/**
 * Bounded active-learning card picker. It never asks the user to scroll the
 * whole catalogue: an initial diverse batch is followed only by cards that can
 * reduce uncertainty in the current profile.
 */
object CardRecommendationEngine {
    const val INITIAL_BATCH_SIZE = 12
    const val FOLLOW_UP_BATCH_SIZE = 12
    const val FINAL_BATCH_SIZE = 8
    const val MAX_EXPOSED_CARDS = 32

    fun nextBatch(
        cards: List<CardFeature>,
        seenIds: Set<String>,
        selected: List<CardFeature>,
        limit: Int = if (seenIds.isEmpty()) INITIAL_BATCH_SIZE else FOLLOW_UP_BATCH_SIZE,
    ): List<CardFeature> {
        val remaining = cards.filterNot { it.id in seenIds }
        if (remaining.isEmpty()) return emptyList()
        val center = selected.ifEmpty { cards }.let(::mean)
        return remaining
            .sortedWith(
                compareByDescending<CardFeature> { uncertaintyScore(it, center) }
                    .thenByDescending { diversityScore(it, selected) }
                    .thenBy { it.id },
            )
            .take(limit.coerceAtMost(MAX_EXPOSED_CARDS - seenIds.size).coerceAtLeast(0))
    }

    private fun mean(cards: List<CardFeature>): CardFeature = CardFeature(
        id = "mean",
        subjectScale = cards.map { it.subjectScale }.average().toFloat(),
        subjectPosition = cards.map { it.subjectPosition }.average().toFloat(),
        headroom = cards.map { it.headroom }.average().toFloat(),
        backgroundRatio = cards.map { it.backgroundRatio }.average().toFloat(),
        brightness = cards.map { it.brightness }.average().toFloat(),
        lightType = "mixed",
        colorTemperature = cards.map { it.colorTemperature }.average().toFloat(),
        saturation = cards.map { it.saturation }.average().toFloat(),
        contrast = cards.map { it.contrast }.average().toFloat(),
        sharpness = cards.map { it.sharpness }.average().toFloat(),
        grain = cards.map { it.grain }.average().toFloat(),
        candidness = cards.map { it.candidness }.average().toFloat(),
        framing = cards.map { it.framing }.average().toFloat(),
    )

    private fun uncertaintyScore(card: CardFeature, center: CardFeature): Float = distance(card, center)

    private fun diversityScore(card: CardFeature, selected: List<CardFeature>): Float =
        if (selected.isEmpty()) 1f else selected.minOf { distance(card, it) }

    private fun distance(a: CardFeature, b: CardFeature): Float = listOf(
        a.subjectScale - b.subjectScale,
        a.subjectPosition - b.subjectPosition,
        a.backgroundRatio - b.backgroundRatio,
        a.brightness - b.brightness,
        (a.colorTemperature - b.colorTemperature) / 2000f,
        a.saturation - b.saturation,
        a.contrast - b.contrast,
        a.grain - b.grain,
        a.candidness - b.candidness,
        a.framing - b.framing,
    ).sumOf { kotlin.math.abs(it).toDouble() }.toFloat()
}
