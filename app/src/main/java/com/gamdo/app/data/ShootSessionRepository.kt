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
        /**
         * The hour ran out on the session this snapshot replaced.
         *
         * Additive, and load-bearing. [refresh] handles expiry by clearing the saved
         * session and publishing an empty snapshot, which is byte-identical to "no
         * session was ever created" — so without this flag the UI cannot tell 만료
         * from 시작 전, and P2's requirement that 만료·오류·도착 없음·수신 가능 stay
         * four distinguishable states is unsatisfiable from the snapshot alone.
         * `ui/shoot/ShootFlowDecisions.kt` also derives expiry locally from
         * [ActiveSession.expiresAt], so a dead server still yields 만료 rather than
         * 오류; this flag is the signal for the case where the repository noticed
         * first.
         *
         * No server call and no wire field changed to add it.
         */
        val expired: Boolean = false,
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
            // `expired = true`, not a bare SessionSnapshot(): the session is being
            // dropped here, and dropping it silently is what made 만료 indistinguishable
            // from 시작 전. See [SessionSnapshot.expired].
            _snapshot.value = SessionSnapshot(expired = true)
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

    /**
     * Downloads every currently available photo then purges the temporary server session.
     *
     * The ordering is the safety property, and it is delegated to [downloadThenClaim] so
     * it can be tested: the claim is what makes this irreversible, so it must not run
     * until every download has succeeded. A download that fails throws first, the server
     * session survives, and 다시 시도 fetches the whole set again.
     */
    suspend fun receiveAndClaim(session: ActiveSession): List<File> {
        val status = api.getShootSession(session.sessionId, session.ownerToken)
        val destination = File(cacheDir, session.sessionId).apply { mkdirs() }
        val files = downloadThenClaim(
            photoIds = status.photos.map { it.photoId },
            download = { photoId ->
                File(destination, "$photoId.png").also { file ->
                    api.downloadShootPhoto(session.sessionId, photoId, session.ownerToken, file)
                }
            },
            claim = { api.claimShootSession(session.sessionId, session.ownerToken) },
        )
        settings.clearShootSession()
        _snapshot.value = SessionSnapshot()
        return files
    }

    /**
     * Where [receiveAndClaim] puts what it downloaded.
     *
     * Exposed because a file still sitting in here means "downloaded but not yet turned
     * into a `captures` row" — see `ui/shoot/ReceivedPhotoImport.kt`, which both maintains
     * and reads that invariant.
     */
    val receivedPhotosRoot: File get() = cacheDir

    suspend fun clear() {
        settings.clearShootSession()
        _snapshot.value = SessionSnapshot()
    }

    private fun ShootSessionCreated.toActive() = ActiveSession(sessionId, ownerToken, api.publicUrl(shareUrl), expiresAt, maxPhotos)
}

/**
 * Downloads every photo, and only then claims.
 *
 * Extracted from [ShootSessionRepository.receiveAndClaim] purely so the ordering can be
 * driven by a test, because the ordering is the only thing standing between a flaky
 * network and lost photos. `claim` deletes the session **and its files** on the server;
 * once it has run there is no second chance. So a download that throws must take the
 * whole operation down with it, before the claim, leaving the session alive for a retry.
 *
 * Sequential on purpose. Parallel downloads would finish some and abandon others while
 * still refusing to claim, which is not faster in any way that matters here (at most five
 * photos) and makes the partial state harder to reason about.
 *
 * @param download must write the photo and return the file it wrote.
 * @param claim runs exactly once, after the last successful download, or never.
 */
internal suspend fun downloadThenClaim(
    photoIds: List<String>,
    download: suspend (String) -> File,
    claim: suspend () -> Unit,
): List<File> {
    val files = photoIds.map { photoId -> download(photoId) }
    claim()
    return files
}
