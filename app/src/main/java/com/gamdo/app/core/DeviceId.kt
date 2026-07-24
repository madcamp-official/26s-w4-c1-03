package com.gamdo.app.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

// One DataStore for device-scoped identity. Survives app restarts; cleared only
// on uninstall — which is exactly "앱 삭제 = 데이터 소실" (D4).
private val Context.deviceDataStore by preferencesDataStore(name = "device")

/**
 * The device UUID — the app's only identity (no login, D4). Generated once on
 * first launch and persisted; every later read returns the same value.
 */
class DeviceIdStore(context: Context) {

    private val appContext = context.applicationContext

    private val keyDeviceUuid = stringPreferencesKey("device_uuid")

    /** Emits the stored UUID, or null until one has been created. */
    val deviceId = appContext.deviceDataStore.data.map { prefs -> prefs[keyDeviceUuid] }

    /** Returns the persisted UUID, generating and storing one on first call. */
    suspend fun getOrCreate(): String {
        appContext.deviceDataStore.data.first()[keyDeviceUuid]?.let { return it }
        val generated = UUID.randomUUID().toString()
        // edit() is atomic — concurrent callers converge on a single stored value.
        var stored = generated
        appContext.deviceDataStore.edit { prefs ->
            val existing = prefs[keyDeviceUuid]
            if (existing != null) {
                stored = existing
            } else {
                prefs[keyDeviceUuid] = generated
            }
        }
        return stored
    }
}
