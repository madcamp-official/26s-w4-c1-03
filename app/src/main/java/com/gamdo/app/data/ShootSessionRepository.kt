package com.gamdo.app.data

import com.gamdo.app.data.network.GamdoApiClient
import com.gamdo.app.data.network.ShootSessionCreated
import java.io.File
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

    suspend fun create(policy: JsonObject): ActiveSession {
        val created = api.createShootSession(policy)
        val session = created.toActive()
        settings.saveShootSession(json.encodeToString(session))
        return session
    }

    suspend fun active(): ActiveSession? = runCatching {
        settings.getShootSession()?.let { json.decodeFromString<ActiveSession>(it) }
            ?.takeIf { it.expiresAt > System.currentTimeMillis() }
    }.getOrNull()

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
        return files
    }

    suspend fun clear() = settings.clearShootSession()

    private fun ShootSessionCreated.toActive() = ActiveSession(sessionId, ownerToken, shareUrl, expiresAt, maxPhotos)
}
