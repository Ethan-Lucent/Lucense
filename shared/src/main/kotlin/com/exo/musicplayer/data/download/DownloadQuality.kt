package com.exo.musicplayer.data.download

/**
 * How good the downloaded audio should be.
 *
 * The ladder is deliberately short. Streaming sites do not offer a continuous
 * range of bitrates — they serve a handful of fixed renditions — so a slider
 * would imply precision that does not exist on the other end.
 *
 * Medium is the default because it is transparent to most listeners on most
 * equipment while being roughly a third the size of the top rung, and because
 * the source is usually a lossy stream already: re-encoding it at 320 kbps
 * cannot recover detail the site never sent, it only preserves the artefacts
 * more faithfully.
 *
 * [formatSelector] narrows what yt-dlp fetches in the first place rather than
 * only what it re-encodes to, so a lower setting saves bandwidth as well as
 * disk. The fallbacks after each `/` matter: plenty of sources publish a single
 * rendition, and a filter with no alternative would fail the download outright
 * instead of taking what is there.
 */
enum class DownloadQuality(
    val label: String,
    val description: String,
    /** yt-dlp `--audio-quality`, used when converting to MP3. */
    val audioQuality: String,
    /** yt-dlp `-f`, choosing what to pull down. */
    val formatSelector: String,
    /** spotdl `--bitrate`. */
    val spotdlBitrate: String
) {
    HIGH(
        label = "High",
        description = "320 kbps · biggest files, no audible loss from the source",
        audioQuality = "320K",
        formatSelector = "bestaudio/best",
        spotdlBitrate = "320k"
    ),
    MEDIUM(
        label = "Medium",
        description = "192 kbps · transparent for most listening, a third the size",
        audioQuality = "192K",
        formatSelector = "bestaudio[abr<=192]/bestaudio/best",
        spotdlBitrate = "192k"
    ),
    LOW(
        label = "Low",
        description = "128 kbps · smallest, fine for phones and background listening",
        audioQuality = "128K",
        formatSelector = "bestaudio[abr<=128]/bestaudio/best",
        spotdlBitrate = "128k"
    );

    companion object {
        val DEFAULT = MEDIUM

        fun fromName(name: String?): DownloadQuality =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        /**
         * What a preview plays: a small audio stream served as one plain file
         * (https), up to about 130 kbps - enough to tell whether it is the
         * right song. yt-dlp's own order then picks Opus, which ffmpeg opens
         * in one request (measured: m4a needed more bytes read before the
         * first sound). Never the segmented kinds (DASH, HLS), which ffmpeg
         * has to fetch a playlist and piece by piece, and which are what made
         * a preview slower than a download. The last two alternatives only
         * matter for a video with nothing else. Downloads don't use it: they
         * choose from every format yt-dlp read, by [formatSelector].
         */
        const val PREVIEW_FORMAT_SELECTOR =
            "bestaudio[protocol=https][abr<=160]/bestaudio[protocol=https]/bestaudio/best"
    }
}
