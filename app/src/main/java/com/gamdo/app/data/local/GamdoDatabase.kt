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
 * are registered; DAOs exist only for the 4 used on Day 1 (§1-3).
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
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun presetsDao(): PresetsDao
    abstract fun sessionsDao(): SessionsDao
    abstract fun capturesDao(): CapturesDao

    companion object {
        const val NAME = "gamdo.db"
    }
}
