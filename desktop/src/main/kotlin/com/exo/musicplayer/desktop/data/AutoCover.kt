package com.exo.musicplayer.desktop.data

import com.exo.musicplayer.desktop.library.DesktopTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Getting a cover for a song that has just been downloaded, without anyone
 * pressing Identify.
 *
 * yt-dlp embeds the video's thumbnail when it can, but it can't always (a
 * container that has no room for one, a thumbnail that failed to convert, the
 * setting off for a catalogue match whose own picture is better). A song that
 * lands without art of its own gets the same six-service lookup the Covers bulk
 * tool uses, once, quietly, one song at a time.
 */
internal enum class CoverOutcome(val note: String?) {
    /** It already carries a cover, from yt-dlp or from the source. */
    HAD_ART("Cover added"),

    /** Looked up and written. */
    ADDED("Cover added"),

    /** The services answered and none has a cover for it. Marked, so it is not asked again. */
    NOT_FOUND("No cover found"),

    /** The network failed on the way, so the answer means nothing. Not marked: tried again later. */
    RETRY_LATER("Cover lookup will be tried again later"),

    /** Not looked for: switched off, or tried before. */
    SKIPPED(null)
}

/** What one search for a cover came to. */
internal enum class CoverSearch { FOUND, MISSING, NETWORK }

/** What a download already knows about its song, to look for the right cover. */
data class CoverHint(
    /** "Artist Title" as the catalogue has it, when that is better than what the file's tags say. */
    val query: String? = null,
    /** The catalogue's own picture of it, tried before searching. */
    val artworkUrl: String? = null,
    /** Whose picture that is, so one the user has turned off isn't used. */
    val provider: String? = null
)

/** The search itself: the six services, and the writing of what they find. */
internal interface CoverSearcher {
    suspend fun search(track: DesktopTrack, hint: CoverHint?): CoverSearch
}

/** What is known about a song's cover, and the record of having looked. */
internal interface CoverLedger {
    suspend fun hasArt(track: DesktopTrack): Boolean
    suspend fun tried(track: DesktopTrack): Boolean
    suspend fun markTried(track: DesktopTrack)
}

/** What the downloader calls when files have landed. */
internal fun interface CoverFinishing {
    suspend fun finish(files: List<File>, hint: CoverHint?): List<CoverOutcome>
}

internal object AutoCoverRule {

    /** A song is looked up when it has no cover of its own and has never been looked up. */
    fun needsLookup(hasArt: Boolean, alreadyTried: Boolean): Boolean = !hasArt && !alreadyTried

    /**
     * What a search that found nothing means.
     *
     * Every service turns a failed request into "no results", so a dead
     * network looks exactly like a song nobody has a cover for. [troubleBefore]
     * and [troubleAfter] are the count of requests that got no answer (see
     * `Http.troubleCount`); if it went up, or a service was still silent when
     * the search gave up ([timedOut]), the miss is not definitive.
     */
    fun judge(found: Boolean, troubleBefore: Int, troubleAfter: Int, timedOut: Boolean): CoverSearch = when {
        found -> CoverSearch.FOUND
        troubleAfter > troubleBefore || timedOut -> CoverSearch.NETWORK
        else -> CoverSearch.MISSING
    }
}

internal class AutoCover(
    private val searcher: CoverSearcher,
    private val ledger: CoverLedger,
    /** The songs behind these files, as the library reads them. */
    private val readTracks: suspend (List<File>) -> List<DesktopTrack>,
    /** Whether new downloads are to get covers at all (the "Embed cover art" choice). */
    private val enabled: () -> Boolean,
    /** Told which files gained a cover, so the library row is read again. */
    private val changed: (List<File>) -> Unit = {}
) : CoverFinishing {

    /** One song at a time, however many downloads land together. */
    private val oneAtATime = Mutex()

    /** Songs whose lookup failed on the network, to be tried with the next download. */
    private val later = LinkedHashSet<File>()

    override suspend fun finish(files: List<File>, hint: CoverHint?): List<CoverOutcome> = ensure(files, hint)

    /**
     * Makes sure each of [files] has a cover, in order, and says how each went.
     * [force] is for a person having asked (applying a match): switched off or
     * tried before doesn't stop it.
     */
    suspend fun ensure(files: List<File>, hint: CoverHint? = null, force: Boolean = false): List<CoverOutcome> {
        if (files.isEmpty()) return emptyList()
        return oneAtATime.withLock {
            val tracks = readTracks(files).associateBy { it.file.absolutePath }
            val outcomes = files.map { file ->
                val track = tracks[file.absolutePath] ?: return@map CoverOutcome.SKIPPED
                lookUp(track, hint, force).also { if (it == CoverOutcome.RETRY_LATER) remember(file) }
            }
            val gained = files.filterIndexed { i, _ -> outcomes[i] == CoverOutcome.ADDED }
            // Earlier songs the network let down, now that it may be back.
            val retried = if (force) emptyList() else retryLater(files.toSet())
            (gained + retried).takeIf { it.isNotEmpty() }?.let(changed)
            outcomes
        }
    }

    private suspend fun lookUp(track: DesktopTrack, hint: CoverHint?, force: Boolean): CoverOutcome {
        if (ledger.hasArt(track)) return CoverOutcome.HAD_ART
        if (!force && !enabled()) return CoverOutcome.SKIPPED
        if (!AutoCoverRule.needsLookup(hasArt = false, alreadyTried = !force && ledger.tried(track))) {
            return CoverOutcome.SKIPPED
        }
        return when (searcher.search(track, hint)) {
            CoverSearch.FOUND -> CoverOutcome.ADDED.also { ledger.markTried(track) }
            CoverSearch.MISSING -> CoverOutcome.NOT_FOUND.also { ledger.markTried(track) }
            // Not marked: this says nothing about the song.
            CoverSearch.NETWORK -> CoverOutcome.RETRY_LATER
        }
    }

    private fun remember(file: File) {
        later.remove(file)
        later.add(file)
        // A long offline spell must not grow this without end.
        while (later.size > MAX_LATER) later.remove(later.first())
    }

    private suspend fun retryLater(except: Set<File>): List<File> {
        // A few at a time: with the network still down each costs a long wait.
        val waiting = later.filter { it !in except }.take(RETRIES_PER_CALL)
        if (waiting.isEmpty()) return emptyList()
        later.removeAll(waiting.toSet())
        val tracks = readTracks(waiting)
        val gained = ArrayList<File>()
        for (track in tracks) {
            when (lookUp(track, hint = null, force = false)) {
                CoverOutcome.ADDED -> gained += track.file
                CoverOutcome.RETRY_LATER -> remember(track.file)
                else -> {}
            }
        }
        return gained
    }

    private companion object {
        const val MAX_LATER = 50
        const val RETRIES_PER_CALL = 5
    }
}

/**
 * The cover line of one queue entry: one song says it plainly, a playlist
 * counts as its songs land.
 */
internal class CoverTally {
    private var with = 0
    private var without = 0
    private var later = 0

    fun add(outcomes: List<CoverOutcome>) {
        for (outcome in outcomes) when (outcome) {
            CoverOutcome.HAD_ART, CoverOutcome.ADDED -> with++
            CoverOutcome.NOT_FOUND -> without++
            CoverOutcome.RETRY_LATER -> later++
            CoverOutcome.SKIPPED -> {}
        }
    }

    fun note(): String? {
        val total = with + without + later
        return when {
            total == 0 -> null
            total == 1 -> when {
                with == 1 -> CoverOutcome.ADDED.note
                without == 1 -> CoverOutcome.NOT_FOUND.note
                else -> CoverOutcome.RETRY_LATER.note
            }
            else -> buildString {
                append("Covers: $with of $total added")
                if (without > 0) append(", $without not found")
                if (later > 0) append(", $later to be tried again later")
            }
        }
    }
}
