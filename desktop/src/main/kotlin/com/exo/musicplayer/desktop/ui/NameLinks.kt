package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.exo.musicplayer.desktop.data.TrackGroups
import com.exo.musicplayer.desktop.library.DesktopTrack

/**
 * A line of text with names in it that go somewhere: [text] carries each name's
 * range as a string annotation (tag [TAG], the name as its value), and [open]
 * is called with the name clicked. Drawn by [LinkedText].
 */
class NameLinks(val text: AnnotatedString, val open: (name: String) -> Unit) {
    companion object {
        const val TAG = "lucense-name"

        /** Plain text, nothing to click. */
        fun plain(text: String) = NameLinks(AnnotatedString(text)) {}
    }
}

/**
 * A song's artist credit with each artist in it a link to their page, by card
 * key. It reads exactly as tagged: "A feat. B" keeps its "feat.", and only the
 * names take a click.
 */
fun artistLinks(track: DesktopTrack, open: (key: String) -> Unit): NameLinks =
    linkedNames(track.displayArtist, TrackGroups.splitArtists(track.artist)) { open(TrackGroups.artistGroupKey(it)) }

/** A song's album, a link to the album's page by card key; plain for a song with no album. */
fun albumLink(track: DesktopTrack, open: (key: String) -> Unit): NameLinks {
    val key = TrackGroups.albumGroupKey(track) ?: return NameLinks.plain(track.displayAlbum)
    return linkedNames(track.displayAlbum, listOf(track.displayAlbum)) { open(key) }
}

/** [text] with each of [names] in it marked, found in the order given; one not found is left plain. */
private fun linkedNames(text: String, names: List<String>, open: (name: String) -> Unit): NameLinks {
    val marked = buildAnnotatedString {
        append(text)
        var at = 0
        for (name in names) {
            val start = text.indexOf(name, at)
            if (start < 0) continue
            addStringAnnotation(NameLinks.TAG, name, start, start + name.length)
            at = start + name.length
        }
    }
    return NameLinks(marked, open)
}

/**
 * [links] on one line, cut short with an ellipsis when it doesn't fit. A name
 * is underlined and lit under the mouse and takes the click, so a row it sits
 * in doesn't also play its song; the rest of the line is the row's.
 *
 * Not Compose's own links (LinkAnnotation): on one line with an ellipsis, a
 * name in the cut-off part made Compose 1.7 ask for the outline of text that
 * isn't drawn, which Skia doesn't have - a NullPointerException in the middle
 * of a layout ("Unknown error"), after which every frame failed with "layout
 * state is not idle before measure starts" and half the window stayed white.
 * It happened as a narrow window cut names off - while resizing, mostly. Here
 * a position becomes an offset in the text and only what is drawn is a name.
 */
@Composable
fun LinkedText(
    links: NameLinks,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var hovered by remember(links.text) { mutableStateOf<AnnotatedString.Range<String>?>(null) }
    val open by rememberUpdatedState(links.open)

    /** The name drawn at [position], if any: never one in the part the ellipsis hides. */
    fun nameAt(position: Offset): AnnotatedString.Range<String>? {
        val shown = layout ?: return null
        if (shown.lineCount == 0) return null
        if (position.x < 0f || position.x > shown.getLineRight(0) || position.y < 0f || position.y > shown.size.height) return null
        val offset = shown.getOffsetForPosition(position)
        if (offset >= shown.getLineEnd(0, visibleEnd = true)) return null
        return links.text.getStringAnnotations(NameLinks.TAG, offset, offset + 1).firstOrNull()
    }

    val text = hovered?.let { name ->
        buildAnnotatedString {
            append(links.text)
            addStyle(SpanStyle(color = Palette.Text, textDecoration = TextDecoration.Underline), name.start, name.end)
        }
    } ?: links.text

    BasicText(
        text,
        modifier
            .pointerHoverIcon(if (hovered != null) PointerIcon.Hand else PointerIcon.Default)
            .pointerInput(links.text) {
                awaitPointerEventScope {
                    var pressed: AnnotatedString.Range<String>? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter -> hovered = nameAt(change.position)
                            PointerEventType.Exit -> hovered = null
                            PointerEventType.Press -> {
                                // Only the main button's press on a name is the name's; anything else is the row's.
                                pressed = if (event.buttons.isPrimaryPressed) nameAt(change.position) else null
                                if (pressed != null) change.consume()
                            }
                            PointerEventType.Release -> {
                                val name = pressed
                                pressed = null
                                if (name != null) {
                                    change.consume()
                                    if (nameAt(change.position) == name) open(name.item)
                                }
                            }
                        }
                    }
                }
            },
        style = style.merge(TextStyle(color = color)),
        onTextLayout = { layout = it },
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1
    )
}
