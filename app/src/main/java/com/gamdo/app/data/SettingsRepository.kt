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

    private companion object {
        const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
