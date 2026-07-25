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

    suspend fun saveStylePreference(cardIds: Set<String>) {
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
                value = recommendPresetId(cardIds),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun getStylePresetId(): String? =
        appSettingsDao.get(KEY_STYLE_PRESET_ID)

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
    }
}
