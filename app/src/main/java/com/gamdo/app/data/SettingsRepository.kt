package com.gamdo.app.data

import com.gamdo.app.data.local.AppSettingsDao
import com.gamdo.app.data.local.entity.AppSettings

/** Small settings reads/writes backed by the app_settings table. */
class SettingsRepository(private val appSettingsDao: AppSettingsDao) {

    suspend fun isOnboardingDone(): Boolean =
        appSettingsDao.get(KEY_ONBOARDING_DONE) == "1"

    suspend fun setOnboardingDone() {
        appSettingsDao.put(
            AppSettings(
                key = KEY_ONBOARDING_DONE,
                value = "1",
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun saveStylePreference(
        cardIds: Set<String>,
        recommendedPresetId: String = recommendPresetId(cardIds),
        recommendedPresetIds: List<String> = listOf(recommendedPresetId),
    ) {
        // A profile is a ranking, not only its first answer. `style_preset_id`
        // remains the single initial selection for backward compatibility, while
        // the whole ordering lets the camera place every recommended style first.
        // Preset IDs are app-controlled snake_case values, so the existing compact
        // app_settings string format is sufficient and needs no schema change.
        val ranking = recommendedPresetIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
            .ifEmpty { listOf(recommendedPresetId) }
        appSettingsDao.put(
            AppSettings(
                key = KEY_SELECTED_CARD_IDS,
                value = cardIds.sorted().joinToString(","),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        appSettingsDao.put(
            AppSettings(
                key = KEY_STYLE_PRESET_ID,
                value = ranking.first(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        appSettingsDao.put(
            AppSettings(
                key = KEY_RECOMMENDED_PRESET_IDS,
                value = ranking.joinToString(","),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun getStylePresetId(): String? =
        appSettingsDao.get(KEY_STYLE_PRESET_ID)

    /** Full onboarding recommendation order for the camera style strip. */
    suspend fun getRecommendedPresetIds(): List<String> =
        appSettingsDao.get(KEY_RECOMMENDED_PRESET_IDS)
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    suspend fun saveActiveReference(hash: String, scope: String, strength: Double) {
        put(KEY_ACTIVE_REFERENCE_HASH, hash)
        put(KEY_ACTIVE_REFERENCE_SCOPE, scope)
        put(KEY_ACTIVE_REFERENCE_STRENGTH, strength.coerceIn(0.0, 1.0).toString())
    }

    suspend fun getActiveReferenceHash(): String? =
        appSettingsDao.get(KEY_ACTIVE_REFERENCE_HASH)?.takeIf { it.isNotBlank() }
    suspend fun getActiveReferenceScope(): String =
        appSettingsDao.get(KEY_ACTIVE_REFERENCE_SCOPE) ?: "both"
    suspend fun getActiveReferenceStrength(): Double =
        appSettingsDao.get(KEY_ACTIVE_REFERENCE_STRENGTH)?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.7

    suspend fun clearActiveReference() {
        put(KEY_ACTIVE_REFERENCE_HASH, "")
        put(KEY_ACTIVE_REFERENCE_SCOPE, "both")
        put(KEY_ACTIVE_REFERENCE_STRENGTH, "0.7")
    }

    /** Temporary delegated-shoot credentials; they expire with the server session. */
    suspend fun saveShootSession(sessionJson: String) = put(KEY_ACTIVE_SHOOT_SESSION, sessionJson)
    suspend fun getShootSession(): String? = appSettingsDao.get(KEY_ACTIVE_SHOOT_SESSION)?.takeIf { it.isNotBlank() }
    suspend fun clearShootSession() = put(KEY_ACTIVE_SHOOT_SESSION, "")

    private suspend fun put(key: String, value: String) {
        appSettingsDao.put(AppSettings(key = key, value = value, updatedAt = System.currentTimeMillis()))
    }

    private fun recommendPresetId(cardIds: Set<String>): String {
        val groups = mapOf(
            "bright_review" to setOf("card_03", "card_05", "card_07", "card_09", "card_11", "card_13", "card_15"),
            "candid_feed" to setOf("card_04", "card_08", "card_12"),
            "soft_film" to setOf("card_01", "card_02", "card_06", "card_10", "card_14", "card_16"),
        )
        return groups.maxByOrNull { (_, ids) -> cardIds.count { it in ids } }?.key
            ?: "clean_social"
    }

    private companion object {
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_SELECTED_CARD_IDS = "selected_card_ids"
        const val KEY_STYLE_PRESET_ID = "style_preset_id"
        const val KEY_RECOMMENDED_PRESET_IDS = "recommended_preset_ids"
        const val KEY_ACTIVE_REFERENCE_HASH = "active_reference_hash"
        const val KEY_ACTIVE_REFERENCE_SCOPE = "active_reference_scope"
        const val KEY_ACTIVE_REFERENCE_STRENGTH = "active_reference_strength"
        const val KEY_ACTIVE_SHOOT_SESSION = "active_shoot_session"
    }
}
