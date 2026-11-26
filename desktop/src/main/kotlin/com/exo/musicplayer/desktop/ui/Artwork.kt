package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.Covers
import com.exo.musicplayer.desktop.data.Thumbnails
import com.exo.musicplayer.desktop.library.DesktopTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Cover art for one track.
 *
 * The synchronous path is deliberate: if the bitmap is already decoded, or the
 * track is known to have no art, the first composition renders the final result
 * with no coroutine and no flash of placeholder. Only a genuine cache miss
 * suspends — which is what keeps a fast-scrolling list from stuttering, the same
 * fix the Android build needed. With every row cover brought into memory at
 * startup ([Covers.warm]), a list's rows are nearly always that first case.
 */
@Composable
fun Artwork(
    track: DesktopTrack?,
    size: Dp,
    corner: Dp = 5.dp,
    modifier: Modifier = Modifier
) {
    // The cover made for the size it is drawn at: a row's own small one, or the larger.
    val edge = with(LocalDensity.current) { size.roundToPx() }
    val key = track?.file?.absolutePath
    val cached = track?.let { Covers.cached(it, edge) }
    // A larger cover not in yet has the row-sized one stand in, rather than nothing.
    val standIn = if (cached == null && edge > Covers.SMALL_EDGE) track?.let { Covers.cached(it, Covers.SMALL_EDGE) } else null
    var bitmap by remember(key, edge) { mutableStateOf(cached ?: standIn) }
    // A song with no cover at all, as against one still coming: only it shows the disc.
    var none by remember(key) { mutableStateOf(track != null && cached == null && standIn == null && Covers.knownMissing(track)) }
    // Already decoded, it is there at once; decoded later, it fades in over the
    // bare tile instead of popping into a row in the middle of a scroll.
    val shown = remember(key, edge) { Animatable(if (cached != null || standIn != null) 1f else 0f) }

    LaunchedEffect(key, edge) {
        if (track == null) return@LaunchedEffect
        if (cached == null && !Covers.knownMissing(track)) Covers.load(track, edge)?.let { bitmap = it }
        // A cover fetched while this is on screen still arrives. Watched here,
        // in this row's own effect: read during composition, every fetch used to
        // recompose every row on screen, the ones with covers included.
        if (bitmap == null) {
            none = Covers.knownMissing(track)
            snapshotFlow { Covers.revision }.drop(1).first {
                if (!Covers.knownMissing(track)) Covers.load(track, edge)?.let { bitmap = it }
                bitmap != null
            }
            none = false
        }
    }
    LaunchedEffect(bitmap != null) {
        if (bitmap != null) shown.animateTo(1f, Motion.change())
    }

    ArtworkFrame(bitmap, size, corner, modifier, disc = none && bitmap == null) { shown.value }
}

@Composable
private fun ArtworkFrame(
    bitmap: ImageBitmap?,
    size: Dp,
    corner: Dp,
    modifier: Modifier,
    /** Nothing to show, and nothing coming: the disc, in place of a cover. */
    disc: Boolean,
    /** How far the picture has faded in, read while drawing - no recomposition per frame. */
    shown: () -> Float = { 1f }
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Palette.Hover),
        contentAlignment = Alignment.Center
    ) {
        // A cover still coming has the bare tile, as Spotify's do, and fades in
        // over it. The disc used to sit there too, and showed through every
        // cover fading in - a list scrolled fast was all discs.
        if (disc) {
            Icon(
                Icons.Default.Album,
                null,
                Modifier.size(size * 0.42f),
                tint = Palette.TextFaint
            )
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // A cover is drawn smaller than it is kept: from mipmaps, it stays
                // smooth rather than grainy.
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown() }
            )
        }
    }
}

/**
 * A cover thumbnail fetched from a URL, for search results.
 *
 * Backed by a small process-wide cache so scrolling a result list, or searching
 * for the same thing twice, does not re-fetch every image.
 */
@Composable
fun RemoteArtwork(
    url: String?,
    size: Dp,
    corner: Dp = 5.dp,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(url) { mutableStateOf(url?.let(RemoteImages::cached)) }
    // No address, or nothing at it: the disc. Still loading: the bare tile.
    var none by remember(url) { mutableStateOf(url == null) }

    LaunchedEffect(url) {
        if (url != null && bitmap == null) {
            bitmap = RemoteImages.load(url)
            none = bitmap == null
        }
    }

    ArtworkFrame(bitmap, size, corner, modifier, disc = none)
}

/**
 * A remote image at whatever shape the caller's modifier gives it.
 *
 * [RemoteArtwork] is square because cover art is; a YouTube thumbnail is 16:9
 * and a channel avatar is a circle, so this takes the geometry as a modifier
 * instead of a single size. Same cache and same failure handling underneath.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 6.dp,
    placeholder: Boolean = true
) {
    var bitmap by remember(url) { mutableStateOf(url?.let(RemoteImages::cached)) }

    LaunchedEffect(url) {
        if (url != null && bitmap == null) bitmap = RemoteImages.load(url)
    }

    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(if (placeholder) Palette.Hover else androidx.compose.ui.graphics.Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private object RemoteImages {

    private val memory = Thumbnails.ByteBudgetCache(budget = 24L * 1024 * 1024)
    private val failed = Thumbnails.BoundedKeySet()

    fun cached(url: String): ImageBitmap? = memory[url]

    suspend fun load(url: String): ImageBitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            memory[url]?.let { return@withContext it }
            if (url in failed) return@withContext null

            val bytes = runCatching {
                val connection = (java.net.URL(url).openConnection()
                    as java.net.HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Lucense/1.0 (desktop)")
                }
                try {
                    // Cover Art Archive 404s for releases with no art, which is
                    // normal rather than an error worth surfacing.
                    if (connection.responseCode !in 200..299) return@runCatching null
                    connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

            // Search results are shown at 42dp, so full-size covers are pure
            // waste here even more than in the library.
            val decoded = bytes?.let { Thumbnails.decodeScaled(it) }
            if (decoded == null) failed += url else memory[url] = decoded
            decoded
        }
}
