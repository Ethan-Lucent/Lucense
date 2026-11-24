package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.system.LogFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The log file: three at most, and a run that can't fill the disk. */
class LogFileTest {

    @Test
    fun aBigLogIsMovedAsideAndTheOldestGoes() {
        val dir = Files.createTempDirectory("lucense-logs").toFile()
        File(dir, "lucense.log").writeBytes(ByteArray(1_200_000) { 'a'.code.toByte() })
        File(dir, "lucense.1.log").writeText("one before")
        File(dir, "lucense.2.log").writeText("two before")
        LogFile.rotate(dir)
        assertFalse(File(dir, "lucense.log").exists())
        assertEquals(1_200_000L, File(dir, "lucense.1.log").length())
        assertEquals("one before", File(dir, "lucense.2.log").readText())
        assertFalse(File(dir, "lucense.3.log").exists(), "never more than three")
    }

    @Test
    fun aSmallLogIsLeftToGrow() {
        val dir = Files.createTempDirectory("lucense-logs").toFile()
        File(dir, "lucense.log").writeText("short")
        LogFile.rotate(dir)
        assertEquals("short", File(dir, "lucense.log").readText())
    }

    @Test
    fun aRunStopsWritingAtTheCapAndSaysSo() {
        val sink = ByteArrayOutputStream()
        val capped = LogFile.CappedStream(sink, 100)
        repeat(20) { capped.write("0123456789".toByteArray()) }
        val text = sink.toString()
        assertTrue(text.startsWith("0123456789".repeat(10)))
        assertTrue(text.contains("log full"))
        assertEquals(1, Regex("log full").findAll(text).count(), "said once")
    }
}
