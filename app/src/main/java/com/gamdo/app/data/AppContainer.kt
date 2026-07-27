package com.gamdo.app.data

import android.content.Context
import androidx.room.Room
import com.gamdo.app.BuildConfig
import com.gamdo.app.core.DeviceIdStore
import com.gamdo.app.data.local.GamdoDatabase
import com.gamdo.app.data.network.GamdoApiClient
import java.io.File
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
    )
        // Dev-phase only: B's schema (v2.x) is still evolving; without this any
        // entity change crashes existing installs. Replace with real migrations
        // before release.
        .fallbackToDestructiveMigration()
        .build()

    val deviceIdStore: DeviceIdStore = DeviceIdStore(appContext)

    val apiClient: GamdoApiClient = GamdoApiClient(
        baseUrl = BuildConfig.GAMDO_API_BASE_URL,
        deviceIdStore = deviceIdStore,
        json = json,
    )

    val cardRepository: CardRepository = CardRepository(
        context = appContext,
        json = json,
    )

    val presetRepository: PresetRepository = PresetRepository(
        context = appContext,
        presetsDao = database.presetsDao(),
        json = json,
    )

    // §5-1: content-hash cache + /references/analyze upload. Every upload this
    // client makes goes through ExifSanitizer inside ReferenceRepository (D8-5).
    val referenceRepository: ReferenceRepository = ReferenceRepository(
        cachedReferencesDao = database.cachedReferencesDao(),
        analysisClient = ReferenceAnalysisClient { file -> apiClient.analyzeReference(file) },
        json = json,
        cacheDir = File(appContext.cacheDir, "reference_uploads"),
    )

    val settingsRepository: SettingsRepository = SettingsRepository(database.appSettingsDao())

    // P2's preference engine persists the on-device profile only. The server never
    // receives this data (D4); camera and result defaults read its recommendation.
    val profileRepository: ProfileRepository = ProfileRepository(
        styleProfileDao = database.styleProfileDao(),
        cardSelectionsDao = database.cardSelectionsDao(),
        json = json,
    )

    // All three are wired on purpose. They are not alternatives: each feeds different
    // methods, and both sets of callers survived the main<-p1 merge. Dropping either
    // group still compiles and still passes every test — the parameters are nullable and
    // the repository null-checks them — so the failure would only show up as an empty
    // table after a demo. See .claude/TEAM.md.
    private val captureEditStackDao = database.captureEditStackDao()

    val captureRepository: CaptureRepository = CaptureRepository(
        context = appContext,
        capturesDao = database.capturesDao(),
        // main (device-verified): saveEditedCapture, recordDownloadedEditResult
        editStackDao = captureEditStackDao,
        editResultsDao = database.editResultsLocalDao(),
        // p1 (§4-1/§4-2): saveEditedResult, markSavedToGallery
        editStackRecorder = RoomEditStackRecorder(captureEditStackDao),
    )
}
