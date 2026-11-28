package com.exo.musicplayer.desktop.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.exo.musicplayer.data.update.Updates

/**
 * Whether the launch notice may be shown now. The one place the rules are
 * written: the setting is on, the update can be installed here in place (it
 * carries this system's build and the install allows it, the sizes are known),
 * the user hasn't skipped this version or a later one, it hasn't been shown
 * this launch, nothing modal is open, and this is the main window.
 */
internal fun shouldAnnounce(
    settingOn: Boolean,
    skippedVersion: String,
    state: UpdateState,
    alreadyShownThisLaunch: Boolean,
    modalOpen: Boolean,
    isMainWindow: Boolean
): Boolean {
    if (!settingOn || alreadyShownThisLaunch || modalOpen || !isMainWindow) return false
    val version = announceableVersion(state) ?: return false
    return !isSkipped(version, skippedVersion)
}

/** The version a state offers in place, or null: no in-place build for this system, a copy that can't update itself, sizes still being worked out. */
internal fun announceableVersion(state: UpdateState): String? = when (state) {
    is UpdateState.Available -> state.release.version.takeIf { state.canDelta && state.summary != null }
    // Already downloaded earlier (and not put off this session): one click installs it.
    is UpdateState.Ready -> state.version.takeIf { !state.deferred && state.note == null }
    else -> null
}

/** Skipping a version silences it and anything older; a newer one is announced again, and a stored value that isn't a version silences nothing. */
internal fun isSkipped(version: String, skipped: String): Boolean =
    UpdateManifest.isVersion(skipped) && !Updates.isNewer(version, skipped)

/** What the notice card shows right now. */
sealed interface NoticeView {
    data object Hidden : NoticeView

    /** "Lucense X is ready", with what it costs; [downloaded]: the files are already staged. */
    data class Offer(val version: String, val size: String, val needsAdmin: Boolean, val downloaded: Boolean) : NoticeView

    data class Progress(val text: String, val fraction: Float?) : NoticeView

    data object Installing : NoticeView

    /** The password or UAC prompt was dismissed; the app is still running. */
    data object Cancelled : NoticeView

    data class Failed(val message: String, val canRetry: Boolean = true) : NoticeView

    /** What the last update came to, said once after the restart. */
    data class Result(val message: String, val ok: Boolean) : NoticeView
}

/** The second line of the offer. */
internal fun offerLine(offer: NoticeView.Offer): String {
    val first = if (offer.downloaded) "Already downloaded." else "A small update: ${offer.size}."
    val password = if (offer.needsAdmin) " You'll be asked for your password once." else ""
    return "$first It installs itself and Lucense restarts.$password"
}

/**
 * The launch notice's own state, on top of the updater's: whether the launch
 * lookup is still being waited on, whether it has been announced (once per
 * launch), put off, or answered with "Update now".
 */
class UpdateNotice(
    private val updater: UpdateController,
    private val settingOn: () -> Boolean,
    private val skippedVersion: () -> String,
    private val saveSkipped: (String) -> Unit
) {
    /** The launch lookup is out and its answer not yet announced or dropped. */
    var armed by mutableStateOf(false)
        private set

    /** The version announced this launch; null until then. */
    var announced by mutableStateOf<String?>(null)
        private set

    private var hidden by mutableStateOf(false)
    private var engaged by mutableStateOf(false)
    private var result by mutableStateOf<NoticeView.Result?>(null)

    /**
     * The look-up at launch (nothing at all when the setting is off). Only
     * what this one finds is announced: a later manual "Check now" shows its
     * answer in Settings and doesn't pop a card up.
     */
    fun launchLookup(): Boolean {
        armed = settingOn()
        val looked = updater.startupCheck(armed) { if (!updater.newer) armed = false }
        if (!looked) armed = false
        return looked
    }

    /** Called whenever the state, the setting or what is open changes. */
    fun evaluate(modalOpen: Boolean, isMainWindow: Boolean = true) {
        if (!armed) return
        val state = updater.state
        // Someone is already doing it from Settings: the card would only repeat it.
        if (state is UpdateState.Downloading || state is UpdateState.Applying || state is UpdateState.Failed) {
            armed = false
            return
        }
        if (shouldAnnounce(settingOn(), skippedVersion(), state, announced != null, modalOpen, isMainWindow)) {
            announced = announceableVersion(state)
            armed = false
        }
    }

    fun view(): NoticeView {
        result?.let { return it }
        val version = announced ?: return NoticeView.Hidden
        if (hidden) return NoticeView.Hidden
        val state = updater.state
        if (engaged) {
            when (state) {
                is UpdateState.Downloading -> return NoticeView.Progress(
                    UpdateText.downloading(state.done, state.total, state.bytesDone, state.bytesTotal),
                    if (state.bytesTotal > 0) state.bytesDone.toFloat() / state.bytesTotal else null
                )
                is UpdateState.Ready -> return if (state.note != null) NoticeView.Cancelled else NoticeView.Installing
                UpdateState.Applying -> return NoticeView.Installing
                is UpdateState.Failed -> return NoticeView.Failed(state.message, state.canRetry)
                else -> Unit
            }
        }
        // Turned off while the offer is up: it goes quiet. Work already begun is not hidden.
        if (!settingOn()) return NoticeView.Hidden
        val size = when (state) {
            is UpdateState.Available -> state.summary?.let { UpdateText.transfer(it.transferBytes, it.transferExact) }
            else -> ""
        } ?: return NoticeView.Hidden
        return when {
            state is UpdateState.Available && state.canDelta && state.release.version == version ->
                NoticeView.Offer(version, size, updater.needsAdmin, downloaded = false)
            state is UpdateState.Ready && !state.deferred && state.note == null && state.version == version ->
                NoticeView.Offer(version, size, updater.needsAdmin, downloaded = true)
            else -> NoticeView.Hidden
        }
    }

    /** "Update now": download, verify, stage, apply and restart, with no second click. */
    fun updateNow() {
        engaged = true
        updater.updateNow()
    }

    /** "Later": nothing more this launch. */
    fun later() {
        hidden = true
    }

    /** "Skip this version": silent until a newer one comes out. */
    fun skip() {
        announced?.let(saveSkipped)
        hidden = true
    }

    /** Cancel while downloading: back to the offer; what has arrived stays staged. */
    fun cancel() {
        engaged = false
        updater.cancel()
    }

    /** "Not now" after a dismissed password prompt: the update stays staged, nothing more is said this launch. */
    fun notNow() {
        updater.later()
        hidden = true
    }

    fun showResult(message: String, ok: Boolean) {
        result = NoticeView.Result(message, ok)
    }

    fun dismissResult() {
        result = null
    }
}
