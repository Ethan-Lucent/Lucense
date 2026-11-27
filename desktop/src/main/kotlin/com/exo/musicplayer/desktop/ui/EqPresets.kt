package com.exo.musicplayer.desktop.ui

import kotlin.math.abs

/**
 * One-click equaliser curves. Gains are dB for the ten bands, in the order of
 * `Equalizer.BANDS`: 31, 62, 125, 250, 500 Hz, 1, 2, 4, 8, 16 kHz.
 *
 * The biggest boost is +6 dB on purpose. The equaliser has no input headroom
 * of its own (only a soft ceiling above 0.9), so a bigger lift on loud music
 * would be squashed rather than just louder.
 */
enum class EqPreset(val label: String, val gains: List<Float>, val note: String) {
    Flat("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), "Every band back to 0 dB."),
    BassBoost("Bass boost", listOf(6f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f), "More weight in the low end."),
    TrebleBoost("Treble boost", listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 5f, 5f), "Brighter, with more sparkle on top."),
    Vocal("Vocal", listOf(-2f, -2f, -1f, 0f, 1f, 2f, 3f, 1f, 0f, 0f), "Pushes voices forward a little."),
    Smooth("Smooth", listOf(2f, 2f, 1f, 0f, 0f, 0f, -1f, -3f, -2f, -1f), "Softens harsh highs and adds some warmth."),
    Loudness("Loudness", listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f), "Fuller lows and highs, good at low volume.")
}

/** The preset whose curve the gains follow (within half a dB a band), or null for a custom curve. */
internal fun matchingEqPreset(gains: List<Float>): EqPreset? =
    EqPreset.entries.firstOrNull { preset ->
        gains.size == preset.gains.size && gains.indices.all { abs(gains[it] - preset.gains[it]) <= 0.5f }
    }

/**
 * Applies a preset through the same copy the graph's drags use, so audio,
 * "remember for this song" and saving all follow. Flat only zeroes the gains,
 * as the old Flat button did; every other preset also switches the equaliser on.
 */
internal fun DesktopFxState.applyingEq(preset: EqPreset): DesktopFxState =
    copy(eqEnabled = eqEnabled || preset != EqPreset.Flat, eqGains = preset.gains)
