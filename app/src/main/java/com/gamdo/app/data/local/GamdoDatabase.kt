package com.gamdo.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.gamdo.app.data.local.entity.AppSettings
import com.gamdo.app.data.local.entity.CachedReferences
import com.gamdo.app.data.local.entity.CaptureEditStack
import com.gamdo.app.data.local.entity.Captures
import com.gamdo.app.data.local.entity.CardSelections
import com.gamdo.app.data.local.entity.Consents
import com.gamdo.app.data.local.entity.EditResultsLocal
import com.gamdo.app.data.local.entity.Events
import com.gamdo.app.data.local.entity.Feedback
import com.gamdo.app.data.local.entity.PendingRequests
import com.gamdo.app.data.local.entity.Presets
import com.gamdo.app.data.local.entity.SessionGuides
import com.gamdo.app.data.local.entity.Sessions
import com.gamdo.app.data.local.entity.StyleProfile

/**
 * Local database = source of truth (D4). All 14 tables from DB schema v2.0 §3
 * are registered, and every table now has a DAO.
 *
 * **This file is frozen.** DAO accessors for all 14 tables are registered below
 * in one pass; further queries are added inside the DAO interfaces
 * (`Daos.kt` / `GuideDaos.kt` / `EditDaos.kt` / `NetworkDaos.kt` /
 * `ProfileDaos.kt`), which never requires touching this file again.
 *
 * `version` stays at 1: adding DAOs is not a schema change. Any future bump must
 * ship an accompanying Migration (AGENTS.md §7-2).
 */
@Database(
    entities = [
        AppSettings::class,
        Consents::class,
        StyleProfile::class,
        CardSelections::class,
        Presets::class,
        Sessions::class,
        SessionGuides::class,
        Captures::class,
        CaptureEditStack::class,
        EditResultsLocal::class,
        Feedback::class,
        Events::class,
        PendingRequests::class,
        CachedReferences::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class GamdoDatabase : RoomDatabase() {
    // Daos.kt — app_settings, presets, sessions, captures
    //
    // styleProfileDao / cardSelectionsDao are NOT here: they belong to the
    // ProfileDaos.kt group below and were declared in both places, which Room
    // rejects ("A database can use a DAO only once"). It survived in HEAD because
    // KSP's incremental cache kept serving the last good result — a clean build,
    // or any change that invalidates the processor, fails immediately.
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun presetsDao(): PresetsDao
    abstract fun sessionsDao(): SessionsDao
    abstract fun capturesDao(): CapturesDao

    // GuideDaos.kt — session_guides + the sessions KPI write path
    abstract fun sessionGuidesDao(): SessionGuidesDao
    abstract fun sessionKpiDao(): SessionKpiDao

    // EditDaos.kt — capture_edit_stack, edit_results_local
    abstract fun captureEditStackDao(): CaptureEditStackDao
    abstract fun editResultsLocalDao(): EditResultsLocalDao

    // NetworkDaos.kt — cached_references, pending_requests
    abstract fun cachedReferencesDao(): CachedReferencesDao
    abstract fun pendingRequestsDao(): PendingRequestsDao

    // ProfileDaos.kt — card_selections, style_profile, feedback, consents, events
    abstract fun cardSelectionsDao(): CardSelectionsDao
    abstract fun styleProfileDao(): StyleProfileDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun consentsDao(): ConsentsDao
    abstract fun eventsDao(): EventsDao

    companion object {
        const val NAME = "gamdo.db"
    }
}
