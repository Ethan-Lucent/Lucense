package com.exo.musicplayer.desktop.data

import com.exo.musicplayer.data.lyrics.LyricsFetch
import com.exo.musicplayer.data.lyrics.LyricsProviderChain
import com.exo.musicplayer.data.lyrics.LyricsRunMemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What one song came to in a bulk tool.
 *
 * The first three are answers, and an answer is what marks a song done.
 * [FAILED] is no answer at all (no connection, a service timing out, a file
 * that couldn't be written): the song is left unmarked so the next run tries it again.
 */
internal enum class BulkOutcome { UPDATED, ALREADY_HAD, NOTHING_FOUND, FAILED }

/**
 * A song the step couldn't get an answer for, and why, worded to follow a
 * count in the note: "35 couldn't be reached (check your connection)".
 *
 * [giveUp] is set when the same thing will happen to every song (no
 * connection, a missing tool): if most of the first songs fail with one, the
 * run stops instead of grinding through the library for nothing.
 */
internal class BulkFailure(val reason: String, val giveUp: GiveUp? = null) : Exception(reason)

/** Why a run stopped early, and when running it again will help. */
internal data class GiveUp(val headline: String, val retry: String = "when you're online")

internal data class BulkTally(
    val updated: Int,
    val alreadyHad: Int,
    val failed: Int,
    val nothing: Int = 0,
    /** How many failed for each reason, most common first. */
    val reasons: List<Pair<String, Int>> = emptyList(),
    /** Set when the run stopped early because most of its first songs couldn't be done. */
    val gaveUp: GiveUp? = null,
    /** Songs never got to because the run gave up. */
    val notTried: Int = 0
) {
    /** Songs that got an answer, and so were marked. */
    val answered: Int get() = updated + alreadyHad + nothing
}

/** A snapshot of how far [workThroughQueue] has got. */
internal data class BulkProgress(
    val done: Int,
    val updated: Int,
    val alreadyHad: Int,
    val failed: Int,
    val current: String,
    val nothing: Int = 0
)

/** How many of the first songs decide whether a run is hopeless. */
internal const val GIVE_UP_SAMPLE = 10

/**
 * Takes [queue] through [step] a few at a time, reporting through [onProgress].
 *
 * Lookups mostly wait on the network, so several go at once. Workers share one
 * thread between suspensions (the caller's), so the counters need no locking.
 *
 * What makes a song "done" is deliberate, because [afterEach] writes the mark
 * that makes the next run skip it:
 *  - Only an answer marks a song. A step that fails, throws, or reports
 *    [BulkOutcome.FAILED] is counted as failed, with its reason, and left
 *    unmarked: a first run made while offline used to mark the whole library
 *    as checked, and every later run then skipped it all and did nothing.
 *  - A song is only counted and marked when its step really came back while the
 *    run was still going. Stopping the run cancels the songs in flight, and
 *    plenty of code between here and the network turns that cancellation into
 *    "nothing found"; without the [ensureActive] below those songs were marked
 *    as checked and every later run skipped them without ever having looked.
 *  - A song that really finished is marked even if Stop lands that instant
 *    ([NonCancellable]), so what was done stays done.
 *  - One song failing, even its mark failing to save, never ends the run.
 *  - If more than half of the first [GIVE_UP_SAMPLE] songs fail with a
 *    [BulkFailure.giveUp], the rest are not tried ([BulkTally.gaveUp]).
 *
 * [paceMs] is a breath between one worker's songs, so the services asked are
 * not hammered by a library-sized burst.
 */
internal suspend fun <T> workThroughQueue(
    queue: List<T>,
    workers: Int,
    label: (T) -> String,
    onProgress: (BulkProgress) -> Unit,
    afterEach: suspend (T) -> Unit = {},
    paceMs: Long = 0,
    step: suspend (T) -> BulkOutcome
): BulkTally {
    var done = 0
    var updated = 0
    var alreadyHad = 0
    var nothing = 0
    var failed = 0
    val reasons = LinkedHashMap<String, Int>()
    var hopeless = 0
    var gaveUp: GiveUp? = null
    val inFlight = mutableListOf<String>()
    val work = Channel<T>(Channel.UNLIMITED)
    queue.forEach { work.trySend(it) }
    work.close()

    fun report() = onProgress(BulkProgress(done, updated, alreadyHad, failed, inFlight.joinToString("  ·  "), nothing))

    fun fail(reason: String) {
        failed++
        reasons[reason] = (reasons[reason] ?: 0) + 1
    }

    coroutineScope {
        // The workers in a job of their own, so giving up can stop them without stopping the caller.
        val crew = launch crew@{
            repeat(workers) {
                launch {
                    var first = true
                    for (track in work) {
                        if (!first && paceMs > 0) delay(paceMs)
                        first = false
                        inFlight += label(track)
                        report()
                        var failure: BulkFailure? = null
                        val outcome = try {
                            step(track)
                        } catch (cancelled: CancellationException) {
                            // A cancellation from inside the song (a timeout on one lookup) is
                            // just that song failing; only the run being stopped ends the run.
                            currentCoroutineContext().ensureActive()
                            failure = BulkFailure("timed out")
                            BulkOutcome.FAILED
                        } catch (known: BulkFailure) {
                            failure = known
                            BulkOutcome.FAILED
                        } catch (memory: OutOfMemoryError) {
                            throw memory
                        } catch (other: Throwable) {
                            failure = BulkFailure("failed (${other.message ?: other.javaClass.simpleName})")
                            BulkOutcome.FAILED
                        }
                        // Stopped while this song was out: whatever came back is not a real answer.
                        currentCoroutineContext().ensureActive()
                        when (outcome) {
                            BulkOutcome.UPDATED -> updated++
                            BulkOutcome.ALREADY_HAD -> alreadyHad++
                            BulkOutcome.NOTHING_FOUND -> nothing++
                            BulkOutcome.FAILED -> fail(failure?.reason ?: "failed")
                        }
                        if (outcome != BulkOutcome.FAILED) {
                            val saved = runCatching { withContext(NonCancellable) { afterEach(track) } }
                            if (saved.isFailure) {
                                // Not marked after all, so not done: it is tried again next time.
                                when (outcome) {
                                    BulkOutcome.UPDATED -> updated--
                                    BulkOutcome.ALREADY_HAD -> alreadyHad--
                                    else -> nothing--
                                }
                                fail("couldn't be recorded as done (${saved.exceptionOrNull()?.message})")
                            }
                        }
                        done++
                        inFlight -= label(track)
                        val stop = failure?.giveUp
                        if (stop != null && done <= GIVE_UP_SAMPLE) hopeless++
                        report()
                        if (stop != null && gaveUp == null && hopeless > GIVE_UP_SAMPLE / 2) {
                            gaveUp = stop
                            this@crew.cancel()
                        }
                    }
                }
            }
        }
        crew.join()
    }
    // A stop by the caller surfaces here, after the crew has wound down.
    currentCoroutineContext().ensureActive()
    return BulkTally(
        updated = updated,
        alreadyHad = alreadyHad,
        failed = failed,
        nothing = nothing,
        reasons = reasons.entries.sortedByDescending { it.value }.map { it.key to it.value },
        gaveUp = gaveUp,
        notTried = queue.size - done
    )
}

/** The songs a run still has to visit: everything, or what has no mark yet. */
internal fun <T> bulkQueue(all: List<T>, path: (T) -> String, marked: Set<String>, redo: Boolean): List<T> =
    if (redo) all else all.filter { path(it) !in marked }

/** What a lyrics lookup comes to in the Lyrics tool: an answer, or a failure that leaves the song unmarked. */
internal fun lyricsOutcome(result: LyricsFetch): BulkOutcome = when (result) {
    is LyricsFetch.Found -> BulkOutcome.UPDATED
    // A service saying "instrumental" is as much an answer as "not found".
    LyricsFetch.Instrumental, LyricsFetch.NotFound -> BulkOutcome.NOTHING_FOUND
    is LyricsFetch.Error -> throw lyricsFailure(result)
}

/**
 * One song through the Lyrics tool: what [DesktopController] runs, here so it
 * can be tested on a real store. Lyrics already kept for [path] (fetched while
 * playing, or pasted by hand) are left alone unless [keepStored] is off; found
 * lyrics are saved; a lookup with no answer throws [BulkFailure] and so is not marked.
 */
internal suspend fun lyricsStep(
    chain: LyricsProviderChain,
    store: DesktopStore,
    path: String,
    title: String,
    artist: String?,
    album: String?,
    durationMs: Long,
    keepStored: Boolean = true,
    /** The run's answers so far, so a song held twice is looked up once. */
    memo: LyricsRunMemo? = null
): BulkOutcome {
    if (keepStored && withContext(Dispatchers.IO) { store.lyricsFor(path) } != null) return BulkOutcome.ALREADY_HAD
    val (result, provider) = chain.fetch(title, artist, album, durationMs, memo)
    val outcome = lyricsOutcome(result)
    if (result is LyricsFetch.Found) {
        withContext(Dispatchers.IO) { store.saveLyrics(path, result.plain, result.synced, provider ?: "unknown") }
    }
    return outcome
}

internal fun lyricsFailure(error: LyricsFetch.Error): BulkFailure =
    if (error.offline) {
        BulkFailure(
            "couldn't be reached (check your connection)",
            GiveUp("Lyrics services can't be reached right now.")
        )
    } else {
        BulkFailure("weren't fully checked (a service didn't answer)")
    }

/** How each tool words its counts, as they follow a number. */
internal data class BulkWords(val updated: String, val alreadyHad: String, val nothing: String)

internal val BulkKind.words: BulkWords
    get() = when (this) {
        BulkKind.COVERS -> BulkWords("found", "already had art", "with nothing found")
        BulkKind.TAGS -> BulkWords("updated", "already right", "with nothing found")
        BulkKind.LYRICS -> BulkWords("found", "already had lyrics", "with nothing found")
        BulkKind.IDENTIFY -> BulkWords("identified", "already right", "not recognised")
        BulkKind.REPAIR -> BulkWords("repaired", "already fine", "with nothing to mend")
        BulkKind.LEVELS -> BulkWords("measured", "already measured", "with nothing to measure")
        BulkKind.FOLDERS -> BulkWords("filled in", "already filled in", "with nothing to fill in")
    }

/**
 * The note a finished run leaves: every song of the queue is in one of its
 * numbers, failures by reason, so "does nothing" always has an explanation.
 */
internal fun bulkNote(words: BulkWords, tally: BulkTally, skipped: Int): String {
    val parts = mutableListOf("${tally.updated} ${words.updated}")
    if (tally.alreadyHad > 0) parts += "${tally.alreadyHad} ${words.alreadyHad}"
    if (tally.nothing > 0) parts += "${tally.nothing} ${words.nothing}"
    tally.reasons.forEach { (reason, count) -> parts += "$count $reason" }
    if (tally.notTried > 0) parts += "${tally.notTried} not tried"
    if (skipped > 0) parts += "$skipped skipped as already done"
    val counts = parts.joinToString(", ") + "."
    val stop = tally.gaveUp ?: return counts
    val marked = if (tally.answered == 0) {
        "Nothing was marked, so run it again ${stop.retry}."
    } else {
        "Only the ${tally.answered} that got an answer were marked, so run it again ${stop.retry} for the rest."
    }
    return "${stop.headline} $marked $counts"
}

/** The counts under a running job's progress bar, in its tool's words. */
fun bulkRunningCounts(job: BulkJob): String {
    val words = BulkKind.entries.firstOrNull { it.label == job.label }?.words
        ?: BulkWords("updated", "already done", "with nothing found")
    return buildList {
        add("${job.updated} ${words.updated}")
        if (job.alreadyHad > 0) add("${job.alreadyHad} ${words.alreadyHad}")
        if (job.nothing > 0) add("${job.nothing} ${words.nothing}")
        if (job.failed > 0) add("${job.failed} failed")
    }.joinToString("  ·  ")
}

/** The note a stopped run leaves behind. */
internal fun stoppedNote(done: Int, total: Int): String = "Stopped after $done of $total."

/** What the status chip says: the running count, or the finished note. */
fun bulkChipText(job: BulkJob): String = when {
    job.running -> "${job.label} ${job.done} / ${job.total}"
    else -> job.finishedNote.orEmpty()
}
