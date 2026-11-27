package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Desktop visual language.
 *
 * Deliberately not the phone's theme scaled up. Desktop wants a denser type
 * ramp, tighter corners and a layered surface stack (window / sidebar / content
 * / raised bar) rather than the phone's single flat background — that layering
 * is most of what separates a native-feeling desktop app from a stretched
 * mobile one.
 */
object Palette {

    /**
     * Every theme carries its own surfaces and its own text tones.
     *
     * They used to be generated: one hue, fixed lightness per layer, text at a
     * fixed brightness above it. That kept the layering consistent but said
     * nothing about whether the text could actually be read on it, and on the
     * brighter accents it could not. Each theme is now a published scheme whose
     * surface and text tones were chosen together, and the contrast of every
     * pairing is measured rather than assumed.
     */
    private val chosen = mutableStateOf(AccentChoice.DEFAULT)
    private val scheme = mutableStateOf(AccentChoice.DEFAULT.colors)
    private val customScheme = mutableStateOf(AccentChoice.CUSTOM.colors)

    /** The accent taken from the song's cover, laid over the theme; null for the theme's own. */
    private val coverAccent = mutableStateOf<Color?>(null)

    /** The theme with the cover's accent over it, when there is one: what every getter reads. */
    private val shown = mutableStateOf(AccentChoice.DEFAULT.colors)
    private val starsOverride = mutableStateOf<Color?>(null)
    private val lyricsActiveOverride = mutableStateOf<Color?>(null)
    private val lyricsInactiveOverride = mutableStateOf<Color?>(null)

    val choice: AccentChoice get() = chosen.value

    /** The colours in use right now. */
    val colors: ThemeColors get() = shown.value

    /** The chosen theme's own colours, without the cover's accent. */
    val themeColors: ThemeColors get() = scheme.value

    /** The Custom theme's colours, whether or not it is the one in use. */
    val custom: ThemeColors get() = customScheme.value

    val Base: Color get() = shown.value.base          // window, deepest layer
    val Sidebar: Color get() = shown.value.sidebar    // navigation rail
    val Content: Color get() = shown.value.content    // main surface
    val Raised: Color get() = shown.value.raised      // transport bar, headers
    val Hover: Color get() = shown.value.hover.let { if (Glass.on) it.copy(alpha = 0.55f) else it }
    val Line: Color get() = shown.value.line

    /** Whether the colours in use are a light theme: the page is pale and the text dark. */
    val isLight: Boolean get() = shown.value.content.luminance() > 0.5f

    /**
     * What dims the window behind a dialog. A near-black veil on a dark theme;
     * on a light one, a lighter veil of the theme's own ink, because an 80%
     * black sheet over a white window reads as the app going dark.
     */
    val Scrim: Color get() = if (isLight) shown.value.text.copy(alpha = 0.38f) else Color(0xCC07050C)

    val Text: Color get() = shown.value.text
    val TextDim: Color get() = shown.value.textDim
    val TextFaint: Color get() = shown.value.textFaint

    /**
     * An inset surface on a page - a field, a log, a meter, a card. The page
     * colour when solid; with glass, a darker (or on a light theme, paler)
     * tint that the pane still shows through.
     */
    val Well: Color
        get() = when {
            !Glass.on -> shown.value.content
            shown.value.content.luminance() > 0.5f -> Color.White.copy(alpha = 0.42f)
            else -> Color.Black.copy(alpha = 0.2f)
        }

    val Accent: Color get() = shown.value.accent
    val AccentSoft: Color get() = shown.value.soft
    val Selected: Color get() = shown.value.selected.let { if (Glass.on) it.copy(alpha = 0.62f) else it }

    /** Foreground for anything sitting on [Accent]. */
    val OnAccent: Color get() = shown.value.onAccent

    /** The resting fill of the main buttons. */
    val Button: Color get() = shown.value.button

    /** Foreground for anything sitting on [Button]. */
    val OnButton: Color
        get() {
            // The theme's own tone when it reads; on a mid-tone button neither may, and then plain ink.
            val own = ThemeColors.readableOn(shown.value.button, shown.value)
            return if (ThemeColors.contrast(own, shown.value.button) >= 4.5f) own else ThemeColors.contrastingWith(shown.value.button)
        }

    /** The background effect's colour: the user's own pick, or the accent. */
    val Stars: Color get() = starsOverride.value ?: shown.value.accent

    fun use(choice: AccentChoice) {
        chosen.value = choice
        scheme.value = if (choice == AccentChoice.CUSTOM) customScheme.value else choice.colors
        refresh()
    }

    fun setCustom(colors: ThemeColors) {
        customScheme.value = colors
        if (chosen.value == AccentChoice.CUSTOM) scheme.value = colors
        refresh()
    }

    /** Lays [color] over the theme as its accent, after Riff's colours from the cover; null takes it off. */
    fun setCoverAccent(color: Color?) {
        coverAccent.value = color
        refresh()
    }

    private fun refresh() {
        shown.value = coverAccent.value?.let { scheme.value.withAccent(it) } ?: scheme.value
    }

    fun setStars(color: Color?) {
        starsOverride.value = color
    }

    /** The line being sung: the user's own colour, or the accent. */
    val LyricsActive: Color get() = lyricsActiveOverride.value ?: shown.value.accent

    /** Every other line: the user's own colour, or the dimmed text colour. */
    val LyricsInactive: Color get() = lyricsInactiveOverride.value ?: TextDim

    fun setLyricsActive(color: Color?) {
        lyricsActiveOverride.value = color
    }

    fun setLyricsInactive(color: Color?) {
        lyricsInactiveOverride.value = color
    }
}

/** One theme's colours: what it is made of, and what every surface takes. */
data class ThemeColors(
    val accent: Color,
    val soft: Color,
    val selected: Color,
    val onAccent: Color,
    val button: Color,
    val base: Color,
    val sidebar: Color,
    val content: Color,
    val raised: Color,
    val hover: Color,
    val line: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color
) {
    /**
     * Primary, secondary, tertiary and button colours as ARGB hex, for the
     * settings file. Only those four: the rest of a custom theme is worked out
     * from the primary, so an old saved theme still loads.
     */
    fun encode(): String = listOf(accent, soft, selected, button).joinToString(",") { hex(it) }

    /**
     * These colours with another accent, mixed into the soft, selected and
     * button tones the way a published scheme mixes its own.
     */
    fun withAccent(accent: Color): ThemeColors = copy(
        accent = accent,
        soft = lerp(accent, content, 0.45f),
        selected = lerp(accent, content, 0.82f),
        onAccent = contrastingWith(accent),
        button = buttonFill(accent, content)
    )

    companion object {
        fun hex(color: Color): String = "%08X".format(color.toArgb())

        fun parse(text: String?): Color? {
            val digits = text?.trim()?.removePrefix("#") ?: return null
            val value = digits.toLongOrNull(16) ?: return null
            return when (digits.length) {
                6 -> Color(0xFF000000L or value)
                8 -> Color(value)
                else -> null
            }
        }

        fun decode(text: String): ThemeColors? {
            val parts = text.split(",").mapNotNull { parse(it) }
            return if (parts.size == 4) from(parts[0], parts[1], parts[2], parts[3]) else null
        }

        /**
         * A custom theme: the surfaces take the primary colour's hue, and the
         * text tones are set well above them, because a colour picked for its
         * looks says nothing about what can be read on it.
         */
        fun from(primary: Color, secondary: Color, tertiary: Color, button: Color): ThemeColors {
            val hsb = java.awt.Color.RGBtoHSB(
                (primary.red * 255).roundToInt(),
                (primary.green * 255).roundToInt(),
                (primary.blue * 255).roundToInt(),
                null
            )
            val hue = (hsb[0] * 360f) % 360f
            val tint = hsb[1].coerceIn(0.08f, 0.75f)

            fun surface(saturation: Float, lightness: Float): Color =
                Color.hsl(hue, (saturation * tint).coerceIn(0f, 1f), lightness)

            return ThemeColors(
                accent = primary,
                soft = secondary,
                selected = tertiary,
                onAccent = contrastingWith(primary),
                button = button,
                base = surface(0.30f, 0.061f),
                sidebar = surface(0.33f, 0.094f),
                content = surface(0.24f, 0.098f),
                raised = surface(0.24f, 0.129f),
                hover = surface(0.26f, 0.169f),
                line = surface(0.22f, 0.180f),
                text = surface(0.18f, 0.967f),
                textDim = surface(0.13f, 0.820f),
                textFaint = surface(0.10f, 0.660f)
            )
        }

        /**
         * A published scheme. Its surfaces and text are its own; the soft
         * accent, the selected row and the button fill are mixed from its
         * accent towards its own content colour, which is how these schemes are
         * used in the editors they come from.
         */
        fun fromScheme(
            accent: Color,
            base: Color,
            sidebar: Color,
            content: Color,
            raised: Color,
            hover: Color,
            line: Color,
            text: Color,
            textDim: Color,
            textFaint: Color
        ): ThemeColors = ThemeColors(
            accent = accent,
            soft = lerp(accent, content, 0.45f),
            selected = lerp(accent, content, 0.82f),
            onAccent = contrastingWith(accent),
            button = buttonFill(accent, content),
            base = base,
            sidebar = sidebar,
            content = content,
            raised = raised,
            hover = hover,
            line = line,
            text = text,
            textDim = textDim,
            textFaint = textFaint
        )

        /**
         * Near-black or near-white, whichever stands out more on [colour]. Measured
         * rather than a fixed brightness cut-off: the old cut-off put white on
         * mid-tone accents where black reads better.
         */
        fun contrastingWith(colour: Color): Color {
            val dark = Color(0xFF10101A)
            val light = Color(0xFFF6F6FB)
            return if (contrast(dark, colour) >= contrast(light, colour)) dark else light
        }

        /**
         * A main button's resting fill: the accent mixed 38% of the way to the
         * page, unless that lands on a mid tone no label reads on (black and
         * white both under 4.5:1) - then it stays nearer the accent, which
         * always carries its own label.
         */
        fun buttonFill(accent: Color, content: Color): Color {
            var towardPage = 0.38f
            var fill = lerp(accent, content, towardPage)
            while (towardPage > 0f && contrast(contrastingWith(fill), fill) < 4.5f) {
                towardPage -= 0.02f
                fill = lerp(accent, content, towardPage.coerceAtLeast(0f))
            }
            return fill
        }

        /** The WCAG contrast ratio of two colours, 1 to 21. */
        fun contrast(a: Color, b: Color): Float {
            val la = a.luminance()
            val lb = b.luminance()
            return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
        }

        /**
         * The theme's base or its text tone, whichever reads better on [colour].
         * Measured, because on a light theme the base is the pale one.
         */
        fun readableOn(colour: Color, scheme: ThemeColors): Color =
            if (contrast(scheme.base, colour) > contrast(scheme.text, colour)) scheme.base else scheme.text
    }
}

/**
 * The colour themes.
 *
 * Every theme is one variant of a family: each family has a dark and a light
 * one, so the mode can change without the look changing character (Custom is
 * a family of one). Most are published schemes, mapped role by role from the
 * scheme's own surface and text hierarchy; Black, Slate, Ocean and Sunset are
 * built on Radix Colors' 12-step scales. Where a published tone missed a
 * readability or balance rule it was moved the least it takes, and every such
 * move is listed in docs/THEMES.md; ThemeBalanceTest measures them all.
 *
 * The enum names are what is saved in the settings, so they never change: an
 * old saved name still finds its theme (or, if it never was one, Mocha, which
 * is what such a name has always shown).
 */
enum class AccentChoice(
    val label: String,
    /** The family this is a variant of; it names the swatch. */
    val family: String,
    /** The light variant of its family, rather than the dark one. */
    val light: Boolean,
    /** Where the scheme comes from, shown under the swatches. */
    val credit: String,
    val accent: Color,
    private val base: Color,
    private val sidebar: Color,
    private val content: Color,
    private val raised: Color,
    private val hover: Color,
    private val line: Color,
    private val text: Color,
    private val textDim: Color,
    private val textFaint: Color
) {
    BLACK(
        "Black", "Black", false, "Lucense Black: Radix gray and violet · radix-ui.com/colors",
        Color(0xFFBAA7FF),
        Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFF111111), Color(0xFF191919),
        Color(0xFF222222), Color(0xFF3A3A3A),
        Color(0xFFEEEEEE), Color(0xFFB4B4B4), Color(0xFF7D7D7D)
    ),
    WHITE(
        "White", "Black", true, "Lucense White: Radix gray and violet · radix-ui.com/colors",
        Color(0xFF6550B9),
        Color(0xFFF0F0F0), Color(0xFFF9F9F9), Color(0xFFFFFFFF), Color(0xFFF0F0F0),
        Color(0xFFE8E8E8), Color(0xFFD9D9D9),
        Color(0xFF202020), Color(0xFF626262), Color(0xFF757575)
    ),
    SLATE(
        "Slate", "Slate", false, "Radix slate and blue · radix-ui.com/colors",
        Color(0xFF70B8FF),
        Color(0xFF111113), Color(0xFF18191B), Color(0xFF18191B), Color(0xFF212225),
        Color(0xFF272A2D), Color(0xFF363A3F),
        Color(0xFFEDEEF0), Color(0xFFB0B4BA), Color(0xFF7E828B)
    ),
    SLATE_LIGHT(
        "Slate Light", "Slate", true, "Radix slate and blue · radix-ui.com/colors",
        Color(0xFF0772CB),
        Color(0xFFF0F0F3), Color(0xFFF9F9FB), Color(0xFFFCFCFD), Color(0xFFF0F0F3),
        Color(0xFFE8E8EC), Color(0xFFD9D9E0),
        Color(0xFF1C2024), Color(0xFF5D6169), Color(0xFF71747D)
    ),
    OCEAN(
        "Ocean", "Ocean", false, "Radix cyan over a slate ladder · radix-ui.com/colors",
        Color(0xFF4CCCE6),
        Color(0xFF0B161A), Color(0xFF101B20), Color(0xFF101B20), Color(0xFF17242B),
        Color(0xFF1E2C32), Color(0xFF2E3C43),
        Color(0xFFE0F1FA), Color(0xFFA6B7BF), Color(0xFF74858C)
    ),
    OCEAN_LIGHT(
        "Ocean Light", "Ocean", true, "Radix cyan over a slate ladder · radix-ui.com/colors",
        Color(0xFF087A95),
        Color(0xFFE6F3F5), Color(0xFFF2FAFB), Color(0xFFFAFDFE), Color(0xFFE6F3F5),
        Color(0xFFDEEBED), Color(0xFFD0DDDE),
        Color(0xFF0D3C48), Color(0xFF586365), Color(0xFF6A7677)
    ),
    SUNSET(
        "Sunset", "Sunset", false, "Radix orange over a sand ladder · radix-ui.com/colors",
        Color(0xFFFFA057),
        Color(0xFF17120E), Color(0xFF1E160F), Color(0xFF1E160F), Color(0xFF28201A),
        Color(0xFF302822), Color(0xFF403832),
        Color(0xFFF6ECE3), Color(0xFFBBB1A9), Color(0xFF897F77)
    ),
    SUNSET_LIGHT(
        "Sunset Light", "Sunset", true, "Radix orange over a sand ladder · radix-ui.com/colors",
        Color(0xFFC14A03),
        Color(0xFFF7EFE5), Color(0xFFFFF7ED), Color(0xFFFEFCFB), Color(0xFFF7EFE5),
        Color(0xFFEFE7DD), Color(0xFFE0D8CE),
        Color(0xFF251F18), Color(0xFF676058), Color(0xFF7B736A)
    ),
    MOCHA(
        "Mocha", "Catppuccin", false, "Catppuccin Mocha · github.com/catppuccin/palette",
        Color(0xFFCBA6F7),
        Color(0xFF11111B), Color(0xFF181825), Color(0xFF1E1E2E), Color(0xFF303042),
        Color(0xFF424456), Color(0xFF54576B),
        Color(0xFFCDD6F4), Color(0xFFBAC2DE), Color(0xFFA6ADC8)
    ),
    LATTE(
        "Latte", "Catppuccin", true, "Catppuccin Latte · github.com/catppuccin/palette",
        Color(0xFF8250D4),
        Color(0xFFDCE0E8), Color(0xFFE6E9EF), Color(0xFFEFF1F5), Color(0xFFE6E9EF),
        Color(0xFFDCE0E8), Color(0xFFCCD0DA),
        Color(0xFF42455F), Color(0xFF565971), Color(0xFF696C82)
    ),
    DRACULA(
        "Dracula", "Dracula", false, "Dracula · draculatheme.com/spec",
        Color(0xFFBD93F9),
        Color(0xFF191A21), Color(0xFF21222C), Color(0xFF282A36), Color(0xFF343746),
        Color(0xFF424450), Color(0xFF44475A),
        Color(0xFFF8F8F2), Color(0xFFCBD0DB), Color(0xFF7F91C5)
    ),
    ALUCARD(
        "Alucard", "Dracula", true, "Alucard, Dracula's light · draculatheme.com/spec",
        Color(0xFF644AC9),
        Color(0xFFDEDCCF), Color(0xFFECE9DF), Color(0xFFFFFBEB), Color(0xFFEFEDDC),
        Color(0xFFDEDCCF), Color(0xFFCECCC0),
        Color(0xFF1F1F1F), Color(0xFF4D4A39), Color(0xFF6C664B)
    ),
    TOKYO(
        "Tokyo Night", "Tokyo Night", false, "Tokyo Night · github.com/folke/tokyonight.nvim",
        Color(0xFF7AA2F7),
        Color(0xFF0C0E14), Color(0xFF16161E), Color(0xFF1A1B26), Color(0xFF282D40),
        Color(0xFF3A4056), Color(0xFF434960),
        Color(0xFFC0CAF5), Color(0xFFA9B1D6), Color(0xFF7B82AA)
    ),
    TOKYO_DAY(
        "Tokyo Day", "Tokyo Night", true, "Tokyo Night Light · github.com/enkia/tokyo-night-vscode-theme",
        Color(0xFF2959AA),
        Color(0xFFD6D8DF), Color(0xFFD6D8DF), Color(0xFFE6E7ED), Color(0xFFE0E0E6),
        Color(0xFFD6D8DF), Color(0xFFC1C2C7),
        Color(0xFF343B59), Color(0xFF4F5369), Color(0xFF646674)
    ),
    ROSE(
        "Rosé Pine", "Rosé Pine", false, "Rosé Pine · rosepinetheme.com/palette",
        Color(0xFFC4A7E7),
        Color(0xFF191724), Color(0xFF1F1D2E), Color(0xFF1F1D2E), Color(0xFF262339),
        Color(0xFF39364A), Color(0xFF524F67),
        Color(0xFFE0DEF4), Color(0xFF9E9AB9), Color(0xFF8884A1)
    ),
    DAWN(
        "Rosé Pine Dawn", "Rosé Pine", true, "Rosé Pine Dawn · rosepinetheme.com/palette",
        Color(0xFF907AA9),
        Color(0xFFF2E9E1), Color(0xFFFAF4ED), Color(0xFFFFFAF3), Color(0xFFF4EDE8),
        Color(0xFFDFDAD9), Color(0xFFCECACD),
        Color(0xFF464261), Color(0xFF605C79), Color(0xFF757182)
    ),
    NORD(
        "Nord", "Nord", false, "Nord: Polar Night and Frost · nordtheme.com",
        Color(0xFF88C0D0),
        Color(0xFF282E39), Color(0xFF2E3440), Color(0xFF2E3440), Color(0xFF3B4252),
        Color(0xFF434C5E), Color(0xFF4C566A),
        Color(0xFFECEFF4), Color(0xFFD8DEE9), Color(0xFF8F9DB8)
    ),
    SNOW(
        "Snow Storm", "Nord", true, "Nord: Snow Storm and Frost · nordtheme.com",
        Color(0xFF5C7FAA),
        Color(0xFFD8DEE9), Color(0xFFE5E9F0), Color(0xFFECEFF4), Color(0xFFE5E8F0),
        Color(0xFFD8DEE9), Color(0xFFCED3DE),
        Color(0xFF2E3440), Color(0xFF4C566A), Color(0xFF5F6C86)
    ),
    GRUVBOX(
        "Gruvbox", "Gruvbox", false, "Gruvbox dark · github.com/morhetz/gruvbox",
        Color(0xFFFABD2F),
        Color(0xFF1D2021), Color(0xFF282828), Color(0xFF282828), Color(0xFF32302F),
        Color(0xFF3C3836), Color(0xFF504945),
        Color(0xFFFBF1C7), Color(0xFFEBDBB2), Color(0xFFBDAE93)
    ),
    GRUVBOX_LIGHT(
        "Gruvbox Light", "Gruvbox", true, "Gruvbox light · github.com/morhetz/gruvbox",
        Color(0xFFB07108),
        Color(0xFFE6DBC0), Color(0xFFEEE5C9), Color(0xFFF8F1D4), Color(0xFFEEE5C9),
        Color(0xFFE6DBC0), Color(0xFFD1C4AA),
        Color(0xFF3C3836), Color(0xFF504945), Color(0xFF786B60)
    ),
    FOREST(
        "Everforest", "Everforest", false, "Everforest dark, hard · github.com/sainnhe/everforest",
        Color(0xFFA7C080),
        Color(0xFF1E2326), Color(0xFF272E33), Color(0xFF272E33), Color(0xFF2E383C),
        Color(0xFF374145), Color(0xFF495156),
        Color(0xFFD3C6AA), Color(0xFFA3AFA6), Color(0xFF8B988F)
    ),
    FOREST_LIGHT(
        "Everforest Light", "Everforest", true, "Everforest light, hard · github.com/sainnhe/everforest",
        Color(0xFF7B8D07),
        Color(0xFFF2EFDF), Color(0xFFF8F5E4), Color(0xFFFFFBEF), Color(0xFFF8F5E4),
        Color(0xFFEDEADA), Color(0xFFBEC5B2),
        Color(0xFF445159), Color(0xFF566455), Color(0xFF6B7669)
    ),
    SOLAR(
        "Solarized", "Solarized", false, "Solarized dark · ethanschoonover.com/solarized",
        Color(0xFF268BD2),
        Color(0xFF05252D), Color(0xFF0B2A33), Color(0xFF0B2A33), Color(0xFF17353E),
        Color(0xFF1D3B44), Color(0xFF4B6167),
        Color(0xFFEEE8D5), Color(0xFF99A7A7), Color(0xFF839496)
    ),
    DAYLIGHT(
        "Solarized Light", "Solarized", true, "Solarized light · ethanschoonover.com/solarized",
        Color(0xFF2187CE),
        Color(0xFFEEE8D5), Color(0xFFEEE8D5), Color(0xFFFDF6E3), Color(0xFFEEE8D5),
        Color(0xFFE7E2CF), Color(0xFFD2CCB9),
        Color(0xFF073642), Color(0xFF4C6168), Color(0xFF5E747C)
    ),
    ONEDARK(
        "One Dark", "One", false, "Atom One Dark · github.com/atom/atom (one-dark-syntax)",
        Color(0xFF61AFEF),
        Color(0xFF21252B), Color(0xFF21252B), Color(0xFF282C34), Color(0xFF2C323C),
        Color(0xFF3E4452), Color(0xFF4B5263),
        Color(0xFFB2B9C6), Color(0xFFA3ABB9), Color(0xFF8C94A2)
    ),
    ONELIGHT(
        "One Light", "One", true, "Atom One Light · github.com/atom/atom (one-light-syntax)",
        Color(0xFF4078F2),
        Color(0xFFEAEAEB), Color(0xFFEAEAEB), Color(0xFFFAFAFA), Color(0xFFF0F0F1),
        Color(0xFFE5E5E6), Color(0xFFDBDBDC),
        Color(0xFF383A42), Color(0xFF5D5F6A), Color(0xFF717278)
    ),
    AYU(
        "Ayu Mirage", "Ayu", false, "Ayu Mirage · github.com/ayu-theme/ayu-colors",
        Color(0xFFFFCC66),
        Color(0xFF181C26), Color(0xFF1F2430), Color(0xFF242936), Color(0xFF282E3B),
        Color(0xFF343B4C), Color(0xFF3A4150),
        Color(0xFFCCCAC2), Color(0xFF9CA8BD), Color(0xFF8691A3)
    ),
    AYU_LIGHT(
        "Ayu Light", "Ayu", true, "Ayu Light · github.com/ayu-theme/ayu-colors",
        Color(0xFFBF7507),
        Color(0xFFEBEEF0), Color(0xFFF8F9FA), Color(0xFFFCFCFC), Color(0xFFF6F5F6),
        Color(0xFFEDEFF1), Color(0xFFDCDFE3),
        Color(0xFF4A4F54), Color(0xFF536175), Color(0xFF6A7585)
    ),
    CUSTOM(
        "Custom", "Custom", false, "Yours",
        Color(0xFFCBA6F7),
        Color(0xFF11111B), Color(0xFF181825), Color(0xFF1E1E2E), Color(0xFF303042),
        Color(0xFF424456), Color(0xFF54576B),
        Color(0xFFCDD6F4), Color(0xFFBAC2DE), Color(0xFFA6ADC8)
    );

    val colors: ThemeColors
        get() = ThemeColors.fromScheme(
            accent, base, sidebar, content, raised, hover, line, text, textDim, textFaint
        )

    /** Foreground for anything sitting on this theme's accent. */
    val onAccent: Color get() = ThemeColors.contrastingWith(accent)

    /** The same family's dark or light variant; Custom has only the one. */
    fun variant(light: Boolean): AccentChoice =
        if (this.light == light) this else entries.firstOrNull { it.family == family && it.light == light } ?: this

    /** The family this belongs to. */
    val themeFamily: ThemeFamily get() = families.first { family == it.name }

    companion object {
        /** What a fresh install starts on. */
        val DEFAULT = BLACK

        /**
         * What a name that is not a theme has always shown - the old default
         * "Amethyst" among them - so that nobody's window changes colour on an
         * update just because their saved name was never a real one.
         */
        val SHOWN_FOR_UNKNOWN = MOCHA

        /** Every family, in the order of the swatches. */
        val families: List<ThemeFamily> by lazy {
            entries.groupBy { it.family }.map { (name, members) ->
                ThemeFamily(name, members.first { !it.light }, members.firstOrNull { it.light } ?: members.first())
            }
        }

        fun fromName(name: String?): AccentChoice =
            entries.firstOrNull { it.name == name } ?: SHOWN_FOR_UNKNOWN
    }
}

/** A family of themes: its name and its dark and light variants (the same one for Custom). */
data class ThemeFamily(val name: String, val dark: AccentChoice, val light: AccentChoice) {
    /** Whether there is a light and a dark to choose between. */
    val hasModes: Boolean get() = dark != light
}

@Composable
// Material's own defaults for the roles not set here (menus, errors) follow the mode too.
private fun desktopColors() = if (Palette.isLight) lightScheme() else darkScheme()

@Composable
private fun lightScheme() = lightColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentSoft,
    onPrimaryContainer = Palette.Text,
    background = Palette.Base,
    onBackground = Palette.Text,
    surface = Palette.Content,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Raised,
    onSurfaceVariant = Palette.TextDim,
    outline = Palette.Line
)

@Composable
private fun darkScheme() = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentSoft,
    onPrimaryContainer = Palette.Text,
    background = Palette.Base,
    onBackground = Palette.Text,
    surface = Palette.Content,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Raised,
    onSurfaceVariant = Palette.TextDim,
    outline = Palette.Line
)

/** Smaller than mobile Material: desktop reads at arm's length, not 30cm. */
private val DesktopTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.5.sp,
        letterSpacing = 0.4.sp
    )
)

private val DesktopShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp)
)

@Composable
fun LucenseDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = desktopColors(),
        typography = DesktopTypography,
        shapes = DesktopShapes,
        content = content
    )
}
