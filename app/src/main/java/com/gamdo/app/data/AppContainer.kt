package com.gamdo.app.data

import android.content.Context
import androidx.room.Room
import com.gamdo.app.BuildConfig
import com.gamdo.app.core.DeviceIdStore
import com.gamdo.app.core.AndroidReferenceImagePreprocessor
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

    /**
     * The local database. **Deliberately built with no migration fallback.**
     *
     * This app has no server-side copy of the user's data — DB 스키마 v2.0 §6:
     * "로컬이 원천이므로 앱 삭제 = 데이터 전체 소실". So the two candidate
     * behaviours on a schema mismatch are:
     *
     *  - `fallbackToDestructiveMigration()` — drop and recreate all 14 tables.
     *    No crash, no log, no prompt. The profile, every capture row, the whole
     *    edit stack and the session KPI evidence are gone before anyone can look.
     *  - no fallback (what we do) — Room throws on open.
     *
     * A crash is the better failure. It is loud, it happens on the developer's
     * machine on the very next run, and nothing has been lost yet.
     *
     * **If Room starts throwing here, the fix is a `Migration`, not this call.**
     * `version` lives in [GamdoDatabase]; bump it and add the object in the same
     * change. DDL §9-4 requires migrations to be additive, and AGENTS.md §7 규칙 2
     * only permits additive edits, so writing one is short.
     *
     * `DatabaseMigrationPolicyTest` fails if the destructive fallback comes back.
     */
    val database: GamdoDatabase = Room.databaseBuilder(
        appContext,
        GamdoDatabase::class.java,
        GamdoDatabase.NAME,
    )
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

    /** AI 2 reference analysis: stateless server response + local content-hash cache. */
    val referenceRepository: ReferenceRepository = ReferenceRepository(
        cachedReferencesDao = database.cachedReferencesDao(),
        analysisClient = ReferenceAnalysisClient { file -> apiClient.analyzeReference(file) },
        json = json,
        cacheDir = File(appContext.cacheDir, "reference-analysis"),
        preprocessor = AndroidReferenceImagePreprocessor(),
    )

    val settingsRepository: SettingsRepository = SettingsRepository(database.appSettingsDao())

    // §3-3 KPI. Referenced by CaptureRepository's KDoc since Day 1 but never built,
    // which is why `sessions` and `session_guides` both read 0 rows on device.
    val guideKpiRepository: GuideKpiRepository = GuideKpiRepository(
        sessionsDao = database.sessionsDao(),
        sessionKpiDao = database.sessionKpiDao(),
        sessionGuidesDao = database.sessionGuidesDao(),
    )

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
