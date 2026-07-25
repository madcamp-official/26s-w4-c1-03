package com.gamdo.app.data

import android.content.Context
import com.gamdo.app.data.local.PresetsDao
import com.gamdo.app.data.local.entity.Presets
import com.gamdo.app.data.preset.StylePreset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Loads system presets. On first run the 6 bundled presets (assets/presets.json,
 * same schema as `GET /presets`) are seeded into the local `presets` table.
 * The server fetch (with ETag) replaces this later; the bundle is the offline fallback.
 */
class PresetRepository(
    private val context: Context,
    private val presetsDao: PresetsDao,
    private val json: Json,
) {
    /**
     * Seeds the bundled presets if none are present yet. Idempotent — safe to call
     * on every launch. Returns the number of system presets after seeding.
     */
    suspend fun seedFromAssetsIfEmpty(): Int {
        val existing = presetsDao.systemCount()
        if (existing > 0) return existing

        val presets = loadBundledPresets()
        val now = System.currentTimeMillis()
        val rows = presets.map { preset ->
            Presets(
                id = preset.id,
                source = "system",
                name = preset.name,
                displayName = preset.displayName,
                paramsJson = json.encodeToString(preset),
                version = 1,
                etag = null,
                active = 1,
                updatedAt = now,
            )
        }
        presetsDao.upsertAll(rows)
        return rows.size
    }

    /**
     * Decodes the bundled 6 presets straight from assets. The camera guide needs the
     * composition block synchronously while building its first frame, before the Room
     * seed has necessarily completed, so it reads the bundle rather than the table.
     */
    fun loadBundledPresets(): List<StylePreset> {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return json.decodeFromString<List<StylePreset>>(text)
    }

    suspend fun count(): Int = presetsDao.count()

    private companion object {
        const val ASSET_NAME = "presets.json"
    }
}
