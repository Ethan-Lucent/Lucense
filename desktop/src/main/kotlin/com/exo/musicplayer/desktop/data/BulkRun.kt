package com.exo.musicplayer.desktop.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What one song came to in a bulk tool. */
internal enum class BulkOutcome { UPDATED, ALREADY_HAD, NOTHING_FOUND }

internal data class BulkTally(val updated: Int, val alreadyHad: Int, val failed: Int)

/** A snapshot of how far [workThroughQueue] has got. */
internal data class BulkProgress(
    val done: Int,
    val updated: Int,
    val alreadyHad: Int,
    val failed: Int,
    val current: String
)

/**
 * Takes [queue] through [step] a few at a time, reporting through [onProgress].
 *
 * Lookups mostly wait on the network, so several go at once. Workers share one
 * thread between suspensions (the caller's), so the counters need no locking.
 * A song that throws counts as nothing found rather than ending the run.
 *
 * What makes a song "done" is deliberate, because [afterEach] writes the mark
 * that makes the next run skip it:
 *  - A song is only counted and marked when its step really came back while the
 *    run was still going. Stopping the run cancels the songs in flight, and
 *    plenty of code between here and the network turns that cancellation into
 *    "nothing found"; without the [ensureActive] below those songs were marked
 *    as checked and every later run skipped them without ever having looked.
 *  - A song that really finished is marked even if Stop lands that instant
 *    ([NonCancellable]), so what was done stays done.
 */
internal suspend fun <T> workThroughQueue(
    queue: List<T>,
    workers: Int,
    label: (T) -> String,
    onProgress: (BulkProgress) -> Unit,
    afterEach: suspend (T) -> Unit = {},
    step: suspend (T) -> BulkOutcome
): BulkTally {
    var done = 0
    var updated = 0
    var alreadyHad = 0
    var failed = 0
    val inFlight = mutableListOf<String>()
    val work = Channel<T>(Channel.UNLIMITED)
    queue.forEach { work.trySend(it) }
    work.close()

    fun report() = onProgress(BulkProgress(done, updated, alreadyHad, failed, inFlight.joinToString("  ·  ")))

    coroutineScope {
        repeat(workers) {
            launch {
                for (track in work) {
                    inFlight += label(track)
                    report()
                    val outcome = try {
                        step(track)
                    } catch (cancelled: CancellationException) {
                        // A cancellation from inside the song (a timeout on one lookup) is
                        // just that song failing; only the run being stopped ends the run.
                        currentCoroutineContext().ensureActive()
                        BulkOutcome.NOTHING_FOUND
                    } catch (failure: Exception) {
                        BulkOutcome.NOTHING_FOUND
                    }
                    // Stopped while this song was out: whatever came back is not a real answer.
                    currentCoroutineContext().ensureActive()
                    when (outcome) {
                        BulkOutcome.UPDATED -> updated++
                        BulkOutcome.ALREADY_HAD -> alreadyHad++
                        BulkOutcome.NOTHING_FOUND -> failed++
                    }
                    withContext(NonCancellable) { afterEach(track) }
                    done++
                    inFlight -= label(track)
                    report()
                }
            }
        }
    }
    return BulkTally(updated, alreadyHad, failed)
}

/** The songs a run still has to visit: everything, or what has no mark yet. */
internal fun <T> bulkQueue(all: List<T>, path: (T) -> String, marked: Set<String>, redo: Boolean): List<T> =
    if (redo) all else all.filter { path(it) !in marked }

/** The note a stopped run leaves behind. */
internal fun stoppedNote(done: Int, total: Int): String = "Stopped after $done of $total."

/** What the status chip says: the running count, or the finished note. */
fun bulkChipText(job: BulkJob): String = when {
    job.running -> "${job.label} ${job.done} / ${job.total}"
    else -> job.finishedNote.orEmpty()
}
