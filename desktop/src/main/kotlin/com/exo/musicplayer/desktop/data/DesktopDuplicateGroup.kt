package com.exo.musicplayer.desktop.data

import com.exo.musicplayer.desktop.library.DesktopTrack

/** A duplicate set as the desktop sees it. */
data class DesktopDuplicateGroup(val keep: DesktopTrack, val remove: List<DesktopTrack>)
