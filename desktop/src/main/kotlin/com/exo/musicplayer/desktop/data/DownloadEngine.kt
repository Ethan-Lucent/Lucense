package com.exo.musicplayer.desktop.data

import com.exo.musicplayer.data.download.DownloadFormat
import com.exo.musicplayer.data.download.DownloadQuality
import com.exo.musicplayer.desktop.download.DesktopYouTubeBackend
import com.exo.musicplayer.desktop.download.DownloadProgress
import com.exo.musicplayer.desktop.download.YtDlp
import com.exo.musicplayer.data.youtube.YtDlpFlatSearch
import java.io.File

/**
 * What the downloader runs: yt-dlp, spotdl and the playlist reading. Held
 * behind this so a test can drive every download path with files of its own,
 * with no program started and no network.
 */
internal interface DownloadEngine {

    suspend fun download(
        target: String,
        destination: File,
        format: DownloadFormat?,
        embedThumbnail: Boolean,
        wholePlaylist: Boolean,
        quality: DownloadQuality,
        cutNonMusic: Boolean = false,
        onProgress: (DownloadProgress) -> Unit
    ): Result<List<File>>

    suspend fun downloadSpotify(
        url: String,
        destination: File,
        format: DownloadFormat?,
        quality: DownloadQuality,
        onProgress: (DownloadProgress) -> Unit
    ): Result<List<File>>

    suspend fun playlist(url: String): Pair<YtDlpFlatSearch.Playlist?, String?>
}

/** The real thing. */
internal class YtDlpEngine(private val youtube: DesktopYouTubeBackend) : DownloadEngine {

    override suspend fun download(
        target: String,
        destination: File,
        format: DownloadFormat?,
        embedThumbnail: Boolean,
        wholePlaylist: Boolean,
        quality: DownloadQuality,
        cutNonMusic: Boolean,
        onProgress: (DownloadProgress) -> Unit
    ): Result<List<File>> = YtDlp.download(
        target = target,
        destination = destination,
        format = format,
        embedThumbnail = embedThumbnail,
        wholePlaylist = wholePlaylist,
        quality = quality,
        cutNonMusic = cutNonMusic,
        onProgress = onProgress
    )

    override suspend fun downloadSpotify(
        url: String,
        destination: File,
        format: DownloadFormat?,
        quality: DownloadQuality,
        onProgress: (DownloadProgress) -> Unit
    ): Result<List<File>> = YtDlp.downloadSpotify(
        url = url,
        destination = destination,
        format = format,
        quality = quality,
        onProgress = onProgress
    )

    override suspend fun playlist(url: String): Pair<YtDlpFlatSearch.Playlist?, String?> = youtube.playlist(url)
}
