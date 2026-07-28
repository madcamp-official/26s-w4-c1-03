package com.gamdo.app.data

import com.gamdo.app.data.local.AppSettingsDao
import com.gamdo.app.data.local.entity.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {
    private class FakeAppSettingsDao : AppSettingsDao {
        private val rows = mutableMapOf<String, AppSettings>()

        override suspend fun get(key: String): String? = rows[key]?.value

        override suspend fun put(setting: AppSettings) {
            rows[setting.key] = setting
        }
    }

    @Test
    fun `style preference preserves every distinct recommendation in rank order`() = runBlocking {
        val repository = SettingsRepository(FakeAppSettingsDao())

        repository.saveStylePreference(
            cardIds = setOf("card_03", "card_09"),
            recommendedPresetId = "bright_review",
            recommendedPresetIds = listOf(
                "soft_film",
                "night_street",
                "soft_film",
                " ",
                "bright_review",
            ),
        )

        assertEquals("soft_film", repository.getStylePresetId())
        assertEquals(
            listOf("soft_film", "night_street", "bright_review"),
            repository.getRecommendedPresetIds(),
        )
    }

    @Test
    fun `empty ranking falls back to the initial style`() = runBlocking {
        val repository = SettingsRepository(FakeAppSettingsDao())

        repository.saveStylePreference(
            cardIds = emptySet(),
            recommendedPresetId = "clean_social",
            recommendedPresetIds = emptyList(),
        )

        assertEquals(listOf("clean_social"), repository.getRecommendedPresetIds())
    }
}
