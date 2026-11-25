package com.exo.musicplayer.desktop.data

/** Progress of one of the bulk tools. */
data class BulkJob(
    val label: String = "",
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val updated: Int = 0,
    val alreadyHad: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val current: String = "",
    val finishedNote: String? = null
) {
    val progress: Float get() = if (total > 0) done.toFloat() / total else 0f
}

/** What the progress panel calls a Fix run. */
const val FIX_LABEL = "Fix"
