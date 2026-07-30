package com.gamdo.app.ui.shoot

import java.io.File

/**
 * Turning a friend's photos into ordinary album photos, and surviving a failure
 * halfway through.
 *
 * ## Why this stage needs its own discipline
 *
 * `ShootSessionRepository.receiveAndClaim` downloads every photo **and then** claims,
 * and claiming *deletes the session on the server*. Everything before the claim is
 * retryable — a download that fails throws before the claim, the session stays alive,
 * and 다시 시도 fetches all of them again (`downloadThenClaim` pins that ordering).
 *
 * Everything after the claim is not. The import runs there: the bytes are in the cache
 * directory, the server no longer has them, and if writing a `captures` row fails
 * there is nowhere to go back to. So this stage is built to lose nothing rather than to
 * report a loss:
 *
 *  1. Each file is imported independently. One failure does not abandon the rest.
 *  2. **A cache file is deleted only when its import succeeded.** The bytes then live
 *     in app-private storage, which is the copy the `captures` row points at.
 *  3. Rule 2 makes "a file still in the received cache" mean exactly "a photo with no
 *     row yet" — no ledger, no extra column, no flag that can disagree with the disk.
 *     [pendingReceivedPhotos] reads that invariant back, and the screen retries on its
 *     next visit.
 *
 * The alternative was a 일부만 저장됐어요 message. It was rejected: it needs new copy,
 * and it tells the user about a situation they cannot act on. An irreversible claim
 * calls for automatic recovery, not a notification.
 */

/**
 * Writes one received photo into app-private storage as a `captures` row.
 *
 * A `fun interface` rather than a direct `CaptureRepository` call because that file
 * belongs to another agent: this seam is the whole coupling, it is satisfied by a
 * one-line lambda in `GamdoNavHost`, and it lets every rule above be tested without a
 * database, a `Context`, or a `Bitmap`.
 *
 * @return the new capture id — the same id an album tap carries, so a received photo
 *   opens through `Routes.result(captureId)` exactly as any other capture does.
 */
fun interface ReceivedPhotoImporter {
    suspend fun import(file: File): String
}

/**
 * What an import batch actually managed to do.
 *
 * [failed] is carried rather than inferred so a caller cannot mistake "some failed" for
 * "none arrived". Nothing puts it on screen: it exists for the log and for the tests,
 * and the files behind it are still on disk to be retried.
 */
data class ReceivedImportResult(
    /** Capture ids, in the order the photos were imported. */
    val captureIds: List<String>,
    val failed: Int,
) {
    val imported: Int get() = captureIds.size

    /** The photo to open, or null when nothing was imported. */
    val firstCaptureId: String? get() = captureIds.firstOrNull()

    /** True when every file in the batch failed — the caller has nothing to show. */
    val isTotalFailure: Boolean get() = captureIds.isEmpty() && failed > 0
}

/**
 * Imports [files] one at a time, deleting only what landed.
 *
 * Never throws: a partial batch is a normal outcome here, and an exception would abort
 * the photos that were still fine. A file whose import failed is left exactly where it
 * was, which is what [pendingReceivedPhotos] looks for later.
 */
suspend fun importReceivedPhotos(
    files: List<File>,
    importer: ReceivedPhotoImporter,
): ReceivedImportResult {
    val captureIds = mutableListOf<String>()
    var failed = 0
    for (file in files) {
        runCatching { importer.import(file) }.fold(
            onSuccess = { captureId ->
                captureIds += captureId
                // The row now points at the app-private copy, so this one is redundant.
                // Deleting it is what keeps the "leftover means unimported" invariant
                // true; a failure to delete would only cause a harmless re-import, which
                // is why it is not checked.
                file.delete()
            },
            onFailure = { failed += 1 },
        )
    }
    return ReceivedImportResult(captureIds = captureIds, failed = failed)
}

/**
 * Received photos that still have no `captures` row, oldest first.
 *
 * Reads the invariant [importReceivedPhotos] maintains: `receiveAndClaim` writes
 * `<root>/<sessionId>/<photoId>.png` and a successful import removes the file, so
 * whatever is left is unfinished business. A missing or empty root is the normal case
 * and yields an empty list.
 *
 * Ordered by modification time — the moment each was downloaded, which is the closest
 * thing to arrival order that survives a restart — with the name as a tie-break so the
 * result is deterministic when a filesystem reports equal timestamps.
 */
fun pendingReceivedPhotos(root: File): List<File> {
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .maxDepth(RECEIVED_TREE_DEPTH)
        .filter { it.isFile && it.length() > 0L && it.name.endsWith(RECEIVED_EXTENSION, ignoreCase = true) }
        .sortedWith(compareBy({ it.lastModified() }, { it.name }))
        .toList()
}

/** `<root>/<sessionId>/<photoId>.png` — two levels below the root. */
private const val RECEIVED_TREE_DEPTH = 2

/** What `ShootSessionRepository.receiveAndClaim` names the files it downloads. */
private const val RECEIVED_EXTENSION = ".png"
