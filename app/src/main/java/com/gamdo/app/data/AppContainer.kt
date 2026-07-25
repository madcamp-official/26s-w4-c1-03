package com.gamdo.app.data

import android.content.Context
import androidx.room.Room
import com.gamdo.app.BuildConfig
import com.gamdo.app.core.DeviceIdStore
import com.gamdo.app.data.local.GamdoDatabase
import com.gamdo.app.data.network.GamdoApiClient
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

    val apiClient: GamdoApiClient = GamdoApiClient(
        baseUrl = BuildConfig.GAMDO_API_BASE_URL,
        deviceIdStore = deviceIdStore,
        json = json,
    )

    val presetRepository: PresetRepository = PresetRepository(
        context = appContext,
        presetsDao = database.presetsDao(),
        json = json,
    )

    val settingsRepository: SettingsRepository = SettingsRepository(database.appSettingsDao())

    val captureRepository: CaptureRepository = CaptureRepository(
        context = appContext,
        capturesDao = database.capturesDao(),
        editStackDao = database.captureEditStackDao(),
        editResultsDao = database.editResultsLocalDao(),
    )
}
