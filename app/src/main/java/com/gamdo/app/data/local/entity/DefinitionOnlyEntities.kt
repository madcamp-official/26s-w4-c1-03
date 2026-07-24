package com.gamdo.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The remaining 10 local tables from DB schema v2.0 §3. Day 1 defines them so the
 * DB matches the frozen schema; DAOs/usage arrive on the days that need them.
 * Column names mirror the DDL exactly (schema is frozen — additive changes only).
 */

/** consents — privacy consent audit trail (§3.2). */
@Entity(tableName = "consents")
data class Consents(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "consent_type") val consentType: String,
    @ColumnInfo(name = "policy_version") val policyVersion: String,
    val granted: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** style_profile — single-row personalization source (§3.3, id must be 1). */
@Entity(tableName = "style_profile")
data class StyleProfile(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "composition_json") val compositionJson: String = "{}",
    @ColumnInfo(name = "color_json") val colorJson: String = "{}",
    @ColumnInfo(name = "subject_prefs_json") val subjectPrefsJson: String = "{}",
    @ColumnInfo(name = "aspect_usage_json") val aspectUsageJson: String = "{}",
    @ColumnInfo(name = "confidence_json") val confidenceJson: String = "{}",
    @ColumnInfo(name = "summary_text") val summaryText: String? = null,
    @ColumnInfo(name = "reset_count") val resetCount: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** card_selections — onboarding card picks (§3.4). */
@Entity(
    tableName = "card_selections",
    indices = [Index(value = ["card_id", "round"], unique = true)],
)
data class CardSelections(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "card_id") val cardId: String,
    val round: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** session_guides — realtime guide history for KPI (§3.7). */
@Entity(
    tableName = "session_guides",
    indices = [Index(name = "ix_guides_session", value = ["session_id", "issued_at"])],
)
data class SessionGuides(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "guide_type") val guideType: String,
    val message: String,
    @ColumnInfo(name = "issued_at") val issuedAt: Long,
    val resolved: Int? = null,
    @ColumnInfo(name = "delta_json") val deltaJson: String = "{}",
)

/** capture_edit_stack — non-destructive edit stack (§3.9). */
@Entity(
    tableName = "capture_edit_stack",
    indices = [Index(value = ["capture_id", "step_order"], unique = true)],
)
data class CaptureEditStack(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "step_order") val stepOrder: Int,
    @ColumnInfo(name = "step_type") val stepType: String,
    @ColumnInfo(name = "params_json") val paramsJson: String,
    val active: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** edit_results_local — local copies of server edit results (§3.10). */
@Entity(
    tableName = "edit_results_local",
    indices = [Index(name = "ix_results_capture", value = ["capture_id", "rank"])],
)
data class EditResultsLocal(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "job_id") val jobId: String,
    val kind: String,
    val generative: Int = 0,
    val seed: Int? = null,
    val rank: Int = 0,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "validation_json") val validationJson: String = "{}",
    @ColumnInfo(name = "ops_applied_json") val opsAppliedJson: String = "[]",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** feedback — profile-update input (§3.11). */
@Entity(tableName = "feedback")
data class Feedback(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    val choice: String,
    @ColumnInfo(name = "selected_result_id") val selectedResultId: String? = null,
    val saved: Int = 0,
    @ColumnInfo(name = "partial_json") val partialJson: String = "{}",
    @ColumnInfo(name = "applied_to_profile") val appliedToProfile: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** events — implicit-signal behavior log (§3.12). */
@Entity(
    tableName = "events",
    indices = [Index(name = "ix_events_type", value = ["event_type", "created_at"])],
)
data class Events(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "subject_id") val subjectId: String? = null,
    @ColumnInfo(name = "payload_json") val payloadJson: String = "{}",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** pending_requests — offline retry queue (§3.13). */
@Entity(tableName = "pending_requests")
data class PendingRequests(
    @PrimaryKey val id: String,
    val method: String,
    val endpoint: String,
    @ColumnInfo(name = "body_json") val bodyJson: String,
    @ColumnInfo(name = "file_path") val filePath: String? = null,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** cached_references — reference-analysis cache (server is stateless, §3.14). */
@Entity(tableName = "cached_references")
data class CachedReferences(
    @PrimaryKey @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "analysis_json") val analysisJson: String,
    @ColumnInfo(name = "target_json") val targetJson: String,
    @ColumnInfo(name = "palette_json") val paletteJson: String = "{}",
    @ColumnInfo(name = "analysis_v") val analysisV: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
