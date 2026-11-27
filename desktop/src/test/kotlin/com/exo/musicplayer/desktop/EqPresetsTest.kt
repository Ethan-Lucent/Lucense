package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.audio.Equalizer
import com.exo.musicplayer.desktop.ui.DesktopFxState
import com.exo.musicplayer.desktop.ui.EqPreset
import com.exo.musicplayer.desktop.ui.applyingEq
import com.exo.musicplayer.desktop.ui.fineTuneOpen
import com.exo.musicplayer.desktop.ui.matchingEqPreset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqPresetsTest {

    @Test
    fun sixPresetsWithTenGainsEachInsideTheGraphRange() {
        assertEquals(6, EqPreset.entries.size)
        assertEquals(10, Equalizer.BANDS.size)
        EqPreset.entries.forEach { p ->
            assertEquals(10, p.gains.size, p.label)
            assertTrue(p.gains.all { abs(it) <= 12f }, p.label)
            assertTrue(p.note.isNotBlank(), p.label)
        }
    }

    @Test
    fun flatIsAllZerosAndNothingBoostsPastAboutSixDb() {
        assertTrue(EqPreset.Flat.gains.all { it == 0f })
        assertTrue(EqPreset.entries.flatMap { it.gains }.max() <= 6.5f)
    }

    @Test
    fun matchingFindsEachPresetFromItsOwnGainsAndToleratesSmallJitter() {
        EqPreset.entries.forEach { p ->
            assertEquals(p, matchingEqPreset(p.gains))
            val jittered = p.gains.mapIndexed { i, g -> g + if (i % 2 == 0) 0.4f else -0.4f }
            assertEquals(p, matchingEqPreset(jittered), p.label)
        }
    }

    @Test
    fun aCustomCurveMatchesNothing() {
        assertNull(matchingEqPreset(EqPreset.BassBoost.gains.toMutableList().also { it[9] = 3f }))
        assertNull(matchingEqPreset(List(10) { 1f }))
        assertNull(matchingEqPreset(listOf(0f, 0f)))
    }

    @Test
    fun applyingAPresetSwitchesTheEqOnAndFineTuneOpens() {
        val fx = DesktopFxState().applyingEq(EqPreset.Loudness)
        assertTrue(fx.eqEnabled)
        assertEquals(EqPreset.Loudness.gains, fx.eqGains)
        assertTrue(fineTuneOpen(fx, advanced = false))
    }

    @Test
    fun flatZeroesTheGainsAndKeepsTheSwitchAsItWas() {
        val on = DesktopFxState(eqEnabled = true, eqGains = EqPreset.BassBoost.gains).applyingEq(EqPreset.Flat)
        assertTrue(on.eqEnabled)
        assertEquals(EqPreset.Flat.gains, on.eqGains)
        assertFalse(DesktopFxState().applyingEq(EqPreset.Flat).eqEnabled)
    }
}
