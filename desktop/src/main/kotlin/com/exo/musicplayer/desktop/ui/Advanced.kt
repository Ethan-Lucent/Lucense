package com.exo.musicplayer.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the extra controls are shown (Settings > Appearance > Show advanced
 * options, or Ctrl+K). Hiding is only visual: nothing is removed or switched
 * off, and the palette finds everything either way. To tuck a control away,
 * wrap it: `if (Advanced.on) { ... }`. A hidden feature that is doing
 * something must still show that it is.
 *
 * Set from the saved setting when the controller starts, and by the checkbox.
 */
object Advanced {
    var on by mutableStateOf(false)
}
