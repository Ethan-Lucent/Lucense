package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.data.weather.WeatherCondition

/**
 * Drawn icons for the weather buckets. The shared enum carries emoji (Android
 * shows those fine), but on Linux Skia often has no colour-emoji font and the
 * glyphs come out as tofu or misaligned monochrome, so the desktop draws vectors.
 * `when` without `else` so a new bucket fails to compile here instead of going blank.
 */
internal fun WeatherCondition.icon(): ImageVector = when (this) {
    WeatherCondition.CLEAR -> Icons.Filled.WbSunny
    WeatherCondition.CLOUDY -> Icons.Filled.Cloud
    WeatherCondition.FOG -> Icons.Filled.Dehaze
    WeatherCondition.RAIN -> Icons.Filled.WaterDrop
    WeatherCondition.SNOW -> Icons.Filled.AcUnit
    WeatherCondition.THUNDERSTORM -> Icons.Filled.Thunderstorm
}

/**
 * The icon in a fixed square so labels beside it never shift from one condition
 * to the next (the glyphs have different natural widths). A null condition shows nothing
 * but still holds the space.
 */
@Composable
internal fun WeatherGlyph(
    condition: WeatherCondition?,
    tint: Color,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (condition != null) {
            Icon(condition.icon(), contentDescription = condition.label, tint = tint, modifier = Modifier.size(size))
        }
    }
}
