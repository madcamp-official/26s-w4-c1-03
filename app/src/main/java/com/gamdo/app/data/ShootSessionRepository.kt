package com.gamdo.app.data

import com.gamdo.app.data.network.GamdoApiClient
import com.gamdo.app.data.network.ShootSessionCreated
import com.gamdo.app.data.network.ShootSessionStatus
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** App-owned side of the one-hour, account-free “나 찍어줘” hand-off. */
class ShootSessionRepository(
    private val api: GamdoApiClient,
    private val settings: SettingsRepository,
    private val cacheDir: File,
    private val json: Json,
) {
    @Serializable
    data class ActiveSession(
        val sessionId: String,
        val ownerToken: String,
        val shareUrl: String,
        val expiresAt: Long,
        val maxPhotos: Int,
    )

    /** UI-facing server snapshot. The app never needs to reach into [GamdoApiClient]. */
    data class SessionSnapshot(
        val session: ActiveSession? = null,
        val status: ShootSessionStatus? = null,
        val loading: Boolean = false,
        val error: String? = null,
    ) {
        val photoCount: Int get() = status?.photos?.size ?: 0
        val isExpired: Boolean get() = session?.expiresAt?.let { it <= System.currentTimeMillis() } ?: false
    }

    private val _snapshot = MutableStateFlow(SessionSnapshot())
    val snapshot: StateFlow<SessionSnapshot> = _snapshot.asStateFlow()

    suspend fun create(policy: JsonObject): ActiveSession {
        val created = api.createShootSession(policy)
        val session = created.toActive()
        settings.saveShootSession(json.encodeToString(session))
        _snapshot.value = SessionSnapshot(session = session)
        return session
    }

    /** Preferred V2 entry point; the raw JSON overload remains for V1 callers. */
    suspend fun create(policy: ShootPolicyV2): ActiveSession = create(policy.toJson(json))

    suspend fun active(): ActiveSession? = runCatching {
        settings.getShootSession()?.let { json.decodeFromString<ActiveSession>(it) }
            ?.takeIf { it.expiresAt > System.currentTimeMillis() }
    }.getOrNull()

    /** Fetches the currently saved session once; call this at a UI-controlled 2s cadence. */
    suspend fun refresh(session: ActiveSession? = null): SessionSnapshot {
        val resolvedSession = session ?: active()
        if (resolvedSession == null) {
            _snapshot.value = SessionSnapshot()
            return _snapshot.value
        }
        if (resolvedSession.expiresAt <= System.currentTimeMillis()) {
            settings.clearShootSession()
            _snapshot.value = SessionSnapshot()
            return _snapshot.value
        }
        _snapshot.value = _snapshot.value.copy(session = resolvedSession, loading = true, error = null)
        return runCatching { api.getShootSession(resolvedSession.sessionId, resolvedSession.ownerToken) }
            .fold(
                onSuccess = { status ->
                    SessionSnapshot(session = resolvedSession, status = status).also { _snapshot.value = it }
                },
                onFailure = { error ->
                    _snapshot.value = SessionSnapshot(session = resolvedSession, error = error.message ?: "session_refresh_failed")
                    _snapshot.value
                },
            )
    }

    /** Downloads every currently available photo then purges the temporary server session. */
    suspend fun receiveAndClaim(session: ActiveSession): List<File> {
        val status = api.getShootSession(session.sessionId, session.ownerToken)
        val destination = File(cacheDir, session.sessionId).apply { mkdirs() }
        val files = status.photos.map { photo ->
            File(destination, "${photo.photoId}.png").also { file ->
                api.downloadShootPhoto(session.sessionId, photo.photoId, session.ownerToken, file)
            }
        }
        api.claimShootSession(session.sessionId, session.ownerToken)
        settings.clearShootSession()
        _snapshot.value = SessionSnapshot()
        return files
    }

    suspend fun clear() {
        settings.clearShootSession()
        _snapshot.value = SessionSnapshot()
    }

    private fun ShootSessionCreated.toActive() = ActiveSession(sessionId, ownerToken, api.publicUrl(shareUrl), expiresAt, maxPhotos)
}
