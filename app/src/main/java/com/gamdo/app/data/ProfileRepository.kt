package com.gamdo.app.data

import com.gamdo.app.core.Ulid
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
                    // DB 스키마 v2.0 §3.4: id TEXT PRIMARY KEY, -- 'sel_' + ULID.
                    // Was "round-$INITIAL_ROUND:$cardId" — readable, but not the
                    // contracted prefix (same class of defect as session_guides'
                    // sgd_ -> gid_ fix in fe60b9e). (card_id, round) already carries
                    // the uniqueness; the id only needs to be a valid primary key.
                    id = "sel_" + Ulid.generate(),
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
