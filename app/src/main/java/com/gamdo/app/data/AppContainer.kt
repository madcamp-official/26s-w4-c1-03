package com.gamdo.app.data

import android.content.Context
import androidx.room.Room
import com.gamdo.app.core.DeviceIdStore
import com.gamdo.app.data.local.GamdoDatabase
import kotlinx.serialization.json.Json

/**
 * Manual DI container — one instance held by the Application. Keeps Day 1 free of
 * a DI framework; can be swapped for Hilt later without touching call sites.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    val database: GamdoDatabase = Room.databaseBuilder(
        appContext,
        GamdoDatabase::class.java,
        GamdoDatabase.NAME,
    ).build()

    val deviceIdStore: DeviceIdStore = DeviceIdStore(appContext)

    val presetRepository: PresetRepository = PresetRepository(
        context = appContext,
        presetsDao = database.presetsDao(),
        json = json,
    )
}
