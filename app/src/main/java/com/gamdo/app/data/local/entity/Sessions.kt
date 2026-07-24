package com.gamdo.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * sessions — capture sessions (DB schema v2.0 §3.6). Actively used (records
 * added from Day 3 onward). ID = 'ses_' + ULID.
 */
@Entity(tableName = "sessions")
data class Sessions(
    @PrimaryKey val id: String,
    val mode: String, // 'style' | 'reference' | 'free'
    @ColumnInfo(name = "style_preset_id") val stylePresetId: String? = null,
    @ColumnInfo(name = "reference_hash") val referenceHash: String? = null,
    @ColumnInfo(name = "scene_type") val sceneType: String? = null,
    @ColumnInfo(name = "resolved_style_json") val resolvedStyleJson: String = "{}",
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "final_match_score") val finalMatchScore: Double? = null,
)
