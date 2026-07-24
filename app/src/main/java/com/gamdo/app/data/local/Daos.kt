package com.gamdo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gamdo.app.data.local.entity.AppSettings
import com.gamdo.app.data.local.entity.Captures
import com.gamdo.app.data.local.entity.Presets
import com.gamdo.app.data.local.entity.Sessions

/** DAOs for the 4 tables actively used on Day 1 (§1-3). */

@Dao
interface AppSettingsDao {
    @Query("SELECT value FROM app_settings WHERE key = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettings)
}

@Dao
interface PresetsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<Presets>)

    @Query("SELECT COUNT(*) FROM presets WHERE source = 'system'")
    suspend fun systemCount(): Int

    @Query("SELECT COUNT(*) FROM presets")
    suspend fun count(): Int

    @Query("SELECT * FROM presets WHERE active = 1 ORDER BY updated_at")
    suspend fun getAll(): List<Presets>
}

@Dao
interface SessionsDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: Sessions)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): Sessions?
}

@Dao
interface CapturesDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(capture: Captures)

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun get(id: String): Captures?
}
