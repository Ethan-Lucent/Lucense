package com.exo.musicplayer.desktop.data

import com.exo.musicplayer.desktop.ui.DesktopFxState

/** A song's effects as one line of text, for the database. */
internal fun encodeFx(fx: DesktopFxState): String =
    listOf(
        fx.speed,
        fx.pitchSemitones,
        if (fx.reverbEnabled) 1f else 0f,
        fx.reverbMix,
        fx.reverbDecay,
        if (fx.eqEnabled) 1f else 0f,
        fx.drive,
        fx.lofi,
        fx.tremolo,
        fx.rotate,
        fx.echo
    ).joinToString(",") + "|" + fx.eqGains.joinToString(",")

/**
 * Null for anything that isn't the numbers and ten bands written above: six
 * before distortion was added, seven since.
 */
internal fun decodeFx(text: String): DesktopFxState? {
    val halves = text.split('|')
    if (halves.size != 2) return null
    val head = halves[0].split(',').mapNotNull { it.toFloatOrNull() }
    val gains = halves[1].split(',').mapNotNull { it.toFloatOrNull() }
    // Six numbers before distortion, seven with it, eleven with lo-fi, tremolo, 8D and echo.
    if (head.size !in 6..11 || gains.size != 10) return null
    return DesktopFxState(
        speed = head[0],
        pitchSemitones = head[1],
        reverbEnabled = head[2] > 0.5f,
        reverbMix = head[3],
        reverbDecay = head[4],
        eqEnabled = head[5] > 0.5f,
        eqGains = gains,
        drive = head.getOrElse(6) { 0f }.coerceIn(0f, 1f),
        lofi = head.getOrElse(7) { 0f }.coerceIn(0f, 1f),
        tremolo = head.getOrElse(8) { 0f }.coerceIn(0f, 1f),
        rotate = head.getOrElse(9) { 0f }.coerceIn(0f, 1f),
        echo = head.getOrElse(10) { 0f }.coerceIn(0f, 1f)
    )
}
