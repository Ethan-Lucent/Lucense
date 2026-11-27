package com.exo.musicplayer.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.exo.musicplayer.data.recognition.MusicMatch

/**
 * One Identify tab's own box, results and word on how it went.
 *
 * The tabs used to share one box, one list and one status line, so a
 * lyrics search's results showed under Name, and a search that finished
 * after the tab was changed landed in whichever tab was open by then. Now
 * each tab has its own, and a search writes into the tab it was started
 * from.
 *
 * A tab only lives for as long as you stay on it: clicking another tab in the
 * tab bar clears the one you leave ([clear]), so coming back later shows it
 * fresh. (Moves the app makes itself, like "Search this" carrying text from
 * one tab to another, do not clear anything.)
 */
class IdentifyTab {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<MusicMatch>>(emptyList())
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var needsFfmpeg by mutableStateOf(false)

    /**
     * Counts how many times the tab has been cleared. A search notes the number
     * when it starts and only writes its answer if it is still the same, so an
     * answer that arrives after the tab was cleared is thrown away instead of
     * bringing the results back.
     */
    var generation: Int = 0
        private set

    /** Back to a fresh tab, and every search already under way becomes stale. */
    fun clear() {
        generation++
        query = ""
        results = emptyList()
        status = null
        busy = false
        needsFfmpeg = false
    }
}

/** True if a search that started at [generation] may still write into this tab. */
fun IdentifyTab.accepts(generation: Int): Boolean = this.generation == generation

/**
 * What leaving [from] by the tab bar clears: the tab's own state, then whatever
 * else that tab keeps elsewhere ([clearExtras]: the Name tab's artist and title
 * fields, the YouTube tab's separate results). Kept apart from the controller
 * so it can be tested without one.
 */
internal fun clearLeftIdentifyTab(
    from: IdentifyMode,
    tabs: Map<IdentifyMode, IdentifyTab>,
    clearExtras: (IdentifyMode) -> Unit
) {
    tabs.getValue(from).clear()
    clearExtras(from)
}
