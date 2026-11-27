package com.exo.musicplayer.desktop

import androidx.compose.ui.graphics.Color
import com.exo.musicplayer.desktop.ThemeMeasure.chroma
import com.exo.musicplayer.desktop.ThemeMeasure.contrast
import com.exo.musicplayer.desktop.ThemeMeasure.hex
import com.exo.musicplayer.desktop.ThemeMeasure.hue
import com.exo.musicplayer.desktop.ThemeMeasure.hueGap
import com.exo.musicplayer.desktop.ThemeMeasure.lstar
import com.exo.musicplayer.desktop.ThemeMeasure.surfaces
import com.exo.musicplayer.desktop.data.DesktopSettings
import com.exo.musicplayer.desktop.data.resolveTheme
import com.exo.musicplayer.desktop.data.themeDefault
import com.exo.musicplayer.desktop.ui.AccentChoice
import com.exo.musicplayer.desktop.ui.ThemeColors
import com.exo.musicplayer.desktop.ui.themeSwitchTitle
import java.util.prefs.Preferences
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules that keep every theme balanced, measured on every entry (the
 * numbers and the reasoning are in docs/THEMES.md):
 *
 * - Readable: text 10:1 on the base and the page (7:1 where a published
 *   scheme's own text tone is lower, listed in [PUBLISHED_TEXT]), dim text 6:1,
 *   faint text 4.5:1, the accent 3.5:1 on the page, and whatever sits on the
 *   accent or a main button 4.5:1.
 * - Layered: dark themes get lighter as a surface comes forward (base, then
 *   sidebar and page, then raised, hover and line). Light themes keep the
 *   panes lighter than the base, so the page reads as paper on a tray, and
 *   raised, hover and line step darker from the page, as Material 3's surface
 *   containers do. Each raised and hover step is 2 to 9 L*: visible, not
 *   stripy. A line is 1.25:1 to 2.4:1 against the page: there but quiet.
 * - Calm: surfaces are low-chroma tints (OKLCH chroma 0.04 or less) of one
 *   hue, Black's are neutral, and an accent is not neon (chroma 0.17 or less
 *   on a dark theme, 0.20 on a light one, where it has to be darker to read).
 */
class ThemeBalanceTest {

    /** Themes whose published text tone sits below 10:1, held to 7:1 instead. */
    private val PUBLISHED_TEXT = setOf(
        AccentChoice.LATTE, AccentChoice.TOKYO_DAY, AccentChoice.DAWN, AccentChoice.SNOW,
        AccentChoice.GRUVBOX_LIGHT, AccentChoice.FOREST, AccentChoice.FOREST_LIGHT,
        AccentChoice.ONEDARK, AccentChoice.ONELIGHT, AccentChoice.AYU, AccentChoice.AYU_LIGHT
    )

    private val themes = AccentChoice.entries.filter { it != AccentChoice.CUSTOM }

    private fun atLeast(what: String, choice: AccentChoice, value: Double, floor: Double) =
        assertTrue(value >= floor, "${choice.name}: $what is %.2f, wants %.2f".format(value, floor))

    private fun within(what: String, choice: AccentChoice, value: Double, low: Double, high: Double) =
        assertTrue(value in low..high, "${choice.name}: $what is %.2f, wants %.2f to %.2f".format(value, low, high))

    @Test
    fun everyThemeIsReadable() {
        themes.forEach { choice ->
            val t = choice.colors
            val textFloor = if (choice in PUBLISHED_TEXT) 7.0 else 10.0
            atLeast("text on base", choice, contrast(t.text, t.base), textFloor)
            atLeast("text on page", choice, contrast(t.text, t.content), textFloor)
            atLeast("text on sidebar", choice, contrast(t.text, t.sidebar), textFloor)
            atLeast("dim text", choice, contrast(t.textDim, t.content), 6.0)
            atLeast("faint text", choice, contrast(t.textFaint, t.content), 4.5)
            atLeast("accent on page", choice, contrast(t.accent, t.content), 3.5)
            atLeast("on accent", choice, contrast(t.onAccent, t.accent), 4.5)
            atLeast("on button", choice, contrast(ThemeMeasure.onButton(choice), t.button), 4.5)
            atLeast("text on a selected row", choice, contrast(t.text, t.selected), 4.5)
            atLeast("text on a hovered row", choice, contrast(t.text, t.hover), 4.5)
        }
    }

    @Test
    fun theExceptionsReallyAreThePublishedTones() {
        // Nothing gets the 7:1 floor that could have had 10:1 without it being asked for.
        PUBLISHED_TEXT.forEach { choice ->
            val t = choice.colors
            assertTrue(minOf(contrast(t.text, t.base), contrast(t.text, t.content)) < 10.0, choice.name)
        }
    }

    @Test
    fun layersStepEvenly() {
        themes.forEach { choice ->
            val t = choice.colors
            val base = lstar(t.base)
            val sidebar = lstar(t.sidebar)
            val content = lstar(t.content)
            val raised = lstar(t.raised)
            val hover = lstar(t.hover)
            val line = lstar(t.line)
            // Both modes: the window is the back, the panes sit on it.
            assertTrue(base <= sidebar + 0.01 && sidebar <= content + 0.01 && base < content, "${choice.name}: base, sidebar, page out of order")
            if (choice.light) {
                assertTrue(raised < content && hover < raised && line < hover, "${choice.name}: light layers should step darker from the page")
            } else {
                assertTrue(raised > content && hover > raised && line > hover, "${choice.name}: dark layers should step lighter from the page")
            }
            within("raised step (L*)", choice, abs(raised - content), 2.0, 9.0)
            within("hover step (L*)", choice, abs(hover - raised), 2.0, 9.0)
            within("line contrast", choice, contrast(t.line, t.content), 1.25, 2.4)
        }
    }

    @Test
    fun coloursStayCalm() {
        themes.forEach { choice ->
            val t = choice.colors
            val cap = if (choice.family == "Black") 0.006 else 0.0405
            t.surfaces().forEach { s ->
                assertTrue(chroma(s) <= cap, "${choice.name}: surface ${hex(s)} chroma %.3f".format(chroma(s)))
            }
            // One hue family: the tinted surfaces all lean the same way.
            val tinted = t.surfaces().filter { chroma(it) > 0.015 }.map { hue(it) }
            tinted.forEach { a -> tinted.forEach { b -> assertTrue(hueGap(a, b) <= 40.0, "${choice.name}: surfaces of different hues") } }
            val accentCap = if (choice.light) 0.202 else 0.172
            assertTrue(chroma(t.accent) <= accentCap, "${choice.name}: accent chroma %.3f".format(chroma(t.accent)))
        }
    }

    @Test
    fun aFamilysTwoVariantsShareTheirAccent() {
        AccentChoice.families.filter { it.hasModes }.forEach { family ->
            val gap = hueGap(hue(family.dark.accent), hue(family.light.accent))
            assertTrue(gap <= 45.0, "${family.name}: dark and light accents are %.0f degrees apart".format(gap))
        }
    }

    @Test
    fun everyThemeHasItsOtherMode() {
        themes.forEach { choice ->
            val other = choice.variant(!choice.light)
            assertTrue(other != choice && other.light != choice.light && other.family == choice.family, choice.name)
            assertEquals(choice, other.variant(choice.light))
            assertEquals(choice, choice.variant(choice.light))
        }
        assertEquals(AccentChoice.CUSTOM, AccentChoice.CUSTOM.variant(true))
        assertFalse(AccentChoice.CUSTOM.themeFamily.hasModes)
        // Four new families, the ten published ones, and Custom.
        assertEquals(15, AccentChoice.families.size)
        assertEquals(AccentChoice.families.size, AccentChoice.families.map { it.name }.toSet().size)
        AccentChoice.families.filter { it.hasModes }.forEach { assertTrue(!it.dark.light && it.light.light, it.name) }
        assertEquals("Black", AccentChoice.families.first().name)
    }

    @Test
    fun namesAndLabelsAreUniqueAndEverySavedNameResolves() {
        assertEquals(AccentChoice.entries.size, AccentChoice.entries.map { it.label }.toSet().size)
        AccentChoice.entries.forEach { assertEquals(it, AccentChoice.fromName(it.name)) }
        // The names people have stored from older versions all still mean themselves.
        listOf("MOCHA", "DRACULA", "TOKYO", "ROSE", "NORD", "GRUVBOX", "FOREST", "SOLAR", "ONEDARK", "AYU", "LATTE", "DAYLIGHT", "CUSTOM")
            .forEach { assertEquals(it, AccentChoice.fromName(it).name) }
        // What an unknown name, or the old default that never was a theme, always showed.
        assertEquals(AccentChoice.MOCHA, AccentChoice.fromName("garbage"))
        assertEquals(AccentChoice.MOCHA, AccentChoice.fromName("Amethyst"))
        assertEquals(AccentChoice.MOCHA, AccentChoice.fromName(null))
    }

    @Test
    fun aFreshInstallStartsOnBlackDarkAndAnUpdateChangesNothing() {
        val fresh = AccentChoice.fromName(themeDefault(hasLibrary = false, shownToday = "MOCHA"))
        assertEquals(AccentChoice.BLACK, fresh)
        assertFalse(fresh.light)
        assertEquals(AccentChoice.DEFAULT, fresh)
        // Someone updating, who never chose: what they saw, kept.
        assertEquals("MOCHA", themeDefault(hasLibrary = true, shownToday = "MOCHA"))

        var saved: String? = null
        assertEquals("BLACK", resolveTheme(null, { false }, { saved = it }))
        assertEquals("BLACK", saved)
        var asked = false
        assertEquals("NORD", resolveTheme("NORD", { asked = true; true }, { error("a stored name is not saved again") }))
        assertFalse(asked)
        assertEquals("MOCHA", resolveTheme(null, { true }, { saved = it }))
        assertEquals("MOCHA", saved)
    }

    @Test
    fun aStoredThemeSurvivesTheSettingsStartingUp() {
        val node = Preferences.userRoot().node("com/exo/musicplayer-themetest")
        // "accent" is the key DesktopSettings stores the theme under.
        try {
            node.put("accent", "SOLAR")
            assertEquals("SOLAR", DesktopSettings(node).accentName)
            node.put("accent", "Amethyst")
            assertEquals("Amethyst", DesktopSettings(node).accentName)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun theCustomStartingColoursAreReadable() {
        val t = AccentChoice.CUSTOM.colors
        assertTrue(contrast(t.text, t.content) >= 7.0)
        assertTrue(contrast(t.textDim, t.content) >= 4.5)
        assertTrue(contrast(t.onAccent, t.accent) >= 4.5)
        // And what the editor makes from any colour keeps its text readable.
        listOf(Color(0xFFE5484D), Color(0xFF30A46C), Color(0xFFFFE629), Color(0xFF0090FF)).forEach { pick ->
            val made = ThemeColors.from(pick, pick, pick, pick)
            assertTrue(contrast(made.text, made.content) >= 7.0, hex(pick))
        }
    }

    @Test
    fun textOnAnAccentIsWhicheverInkReadsBetter() {
        listOf(Color(0xFF0090FF), Color(0xFF6E56CF), Color(0xFFFFC53D), Color(0xFF12A594), Color(0xFF808080)).forEach { a ->
            val ink = ThemeColors.contrastingWith(a)
            val other = if (ink == Color(0xFF10101A)) Color(0xFFF6F6FB) else Color(0xFF10101A)
            assertTrue(contrast(ink, a) >= contrast(other, a), hex(a))
        }
    }

    @Test
    fun theCommandPaletteOffersTheOtherMode() {
        assertEquals("Switch to light theme", themeSwitchTitle(AccentChoice.BLACK))
        assertEquals("Switch to dark theme", themeSwitchTitle(AccentChoice.LATTE))
        assertNull(themeSwitchTitle(AccentChoice.CUSTOM))
    }
}
