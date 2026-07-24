package com.gamdo.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * captures — capture results; the original file lives in the app directory, this
 * row is metadata (DB schema v2.0 §3.8). Actively used (records added from §1-5).
 * ID = 'cap_' + ULID (shared with the server job).
 */
@Entity(
    tableName = "captures",
    indices = [Index(name = "ix_captures_created", value = ["created_at"])],
)
data class Captures(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String? = null, // NULL = gallery import
    val source: String, // 'camera_manual' | 'gallery_import'
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "thumb_path") val thumbPath: String? = null,
    @ColumnInfo(name = "analysis_json") val analysisJson: String = "{}",
    @ColumnInfo(name = "conditions_json") val conditionsJson: String = "{}",
    @ColumnInfo(name = "problems_json") val problemsJson: String = "[]",
    @ColumnInfo(name = "selected_result_id") val selectedResultId: String? = null,
    @ColumnInfo(name = "saved_to_gallery") val savedToGallery: Int = 0,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
