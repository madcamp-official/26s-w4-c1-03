package com.gamdo.app

import android.app.Application
import com.gamdo.app.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Holds the [AppContainer] and, on first launch, ensures
 * the device UUID exists and the bundled presets are seeded (§1-3).
 */
class GamdoApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            container.deviceIdStore.getOrCreate()
            container.presetRepository.seedFromAssetsIfEmpty()
        }
    }
}
