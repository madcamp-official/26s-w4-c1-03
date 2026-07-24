package com.gamdo.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * presets — system cache + personal + blend presets in one table (DB schema v2.0 §3.5).
 * Actively used from Day 1 (system 6 seeded from bundled assets/presets.json).
 */
@Entity(tableName = "presets")
data class Presets(
    @PrimaryKey val id: String,
    val source: String, // 'system' | 'user' | 'blend'
    val name: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "params_json") val paramsJson: String, // §5.3 StylePreset (composition+color)
    @ColumnInfo(name = "parent_ids") val parentIds: String? = null,
    val version: Int = 1,
    val etag: String? = null,
    val active: Int = 1,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
