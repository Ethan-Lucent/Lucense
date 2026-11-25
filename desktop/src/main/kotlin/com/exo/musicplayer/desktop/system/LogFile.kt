package com.exo.musicplayer.desktop.system

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Everything Lucense says on stdout and stderr, kept in a file as well.
 *
 * Started from a menu or the Start menu, the app has no terminal: its "Lucense:"
 * notes, a background job's stack trace, an audio underrun, all went nowhere,
 * and a problem could only be looked into by starting it again from a terminal
 * and hoping it happened twice. Now they are also in `logs/lucense.log` in the
 * data folder - Settings, About, Open logs - while still going wherever stdout
 * and stderr go.
 *
 * Each run starts with a line saying when and which version. A log grown past
 * [ROTATE_BYTES] is moved aside at the next start (`lucense.1.log`, then `.2`),
 * so there are never more than three; within a run, writing stops at
 * [CAP_BYTES], so a fault that repeats in a loop can't fill the disk. Threads
 * that die of an uncaught exception say so here too.
 */
object LogFile {

    @Volatile var folder: File? = null
        private set

    /** Starts copying stdout and stderr into [dir]/lucense.log. Safe to call once, early. */
    fun install(dir: File, version: String) {
        if (folder != null) return
        runCatching {
            dir.mkdirs()
            rotate(dir)
            val sink = CappedStream(FileOutputStream(File(dir, NAME), true), CAP_BYTES)
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            sink.write("\n---- Lucense $version, started $stamp, ${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}\n".toByteArray())
            System.setOut(PrintStream(Tee(System.out, sink), true, Charsets.UTF_8))
            System.setErr(PrintStream(Tee(System.err, sink), true, Charsets.UTF_8))
            folder = dir
        }.onFailure {
            System.err.println("Lucense: no log file in $dir (${it.message}); carrying on without one")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            System.err.println("Lucense: thread ${thread.name} died: ${error.stackTraceToString()}")
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun rotate(dir: File) {
        val current = File(dir, NAME)
        if (!current.isFile || current.length() < ROTATE_BYTES) return
        for (n in KEPT - 1 downTo 1) {
            val older = File(dir, "lucense.$n.log")
            if (older.isFile) {
                val next = File(dir, "lucense.${n + 1}.log")
                if (n + 1 >= KEPT) older.delete() else older.renameTo(next)
            }
        }
        current.renameTo(File(dir, "lucense.1.log"))
    }

    /** Writes to both; the file failing never stops the original stream. */
    private class Tee(private val original: OutputStream, private val file: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            original.write(b)
            runCatching { file.write(b) }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            original.write(b, off, len)
            runCatching { file.write(b, off, len) }
        }

        override fun flush() {
            original.flush()
            runCatching { file.flush() }
        }
    }

    /** A file that stops taking writes past [cap] bytes, once saying so. */
    internal class CappedStream(private val out: OutputStream, private val cap: Long) : OutputStream() {
        private var written = 0L
        private var full = false

        @Synchronized override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
            if (full) return
            if (written + len > cap) {
                full = true
                out.write("\n---- log full ($cap bytes); nothing more is written this run\n".toByteArray())
                out.flush()
                return
            }
            out.write(b, off, len)
            written += len
        }

        @Synchronized override fun flush() = out.flush()
    }

    private const val NAME = "lucense.log"
    /** Moved aside at the next start once past this. */
    private const val ROTATE_BYTES = 1_000_000L
    /** The most one run writes. */
    private const val CAP_BYTES = 8_000_000L
    /** The current log and the ones before it. */
    private const val KEPT = 3
}
