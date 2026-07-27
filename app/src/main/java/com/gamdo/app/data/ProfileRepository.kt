package com.gamdo.app.data

import com.gamdo.app.data.local.CardSelectionsDao
import com.gamdo.app.data.local.StyleProfileDao
import com.gamdo.app.data.local.entity.CardSelections
import com.gamdo.app.data.local.entity.StyleProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists the on-device preference calculation in the frozen local schema. */
class ProfileRepository(
    private val styleProfileDao: StyleProfileDao,
    private val cardSelectionsDao: CardSelectionsDao,
    private val json: Json,
) {
    suspend fun saveInitialProfile(cardIds: Set<String>, profile: StyleProfileResult) {
        val now = System.currentTimeMillis()
        cardSelectionsDao.deleteRound(INITIAL_ROUND)
        cardSelectionsDao.insertAll(
            cardIds.sorted().map { cardId ->
                CardSelections(
                    id = "round-$INITIAL_ROUND:$cardId",
                    cardId = cardId,
                    round = INITIAL_ROUND,
                    createdAt = now,
                )
            },
        )
        styleProfileDao.upsert(
            StyleProfile(
                compositionJson = json.encodeToString(profile.composition),
                colorJson = json.encodeToString(profile.color),
                confidenceJson = json.encodeToString(
                    mapOf(
                        "composition" to profile.composition.mapValues { it.value.confidence },
                        "color" to profile.color.mapValues { it.value.confidence },
                    ),
                ),
                summaryText = profile.summary,
                updatedAt = now,
            ),
        )
    }

    companion object {
        private const val INITIAL_ROUND = 1
    }
}
