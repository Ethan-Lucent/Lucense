package com.exo.musicplayer.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.coroutines.coroutineContext

/** One file in a remote zip, from its central directory. */
data class RemoteZipEntry(
    val name: String,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
    val localExtraGuess: Int
)

/**
 * Reads single files out of a zip on a web server without downloading the
 * rest, with HTTP Range requests: the end of the file first (the
 * end-of-central-directory record and the zip comment, at most 64 KB), then
 * the central directory, then for each file wanted its local header and its
 * compressed bytes. Each file is inflated as it arrives and checked against
 * its CRC-32 and the SHA-256 the caller expects.
 */
class ZipOverHttp(private val remote: UpdateHttp.Ranged) {

    var entries: Map<String, RemoteZipEntry> = emptyMap()
        private set

    /** The zip's length, once [open] has run. */
    val length: Long get() = remote.length

    /** Bytes received so far over all requests. */
    val received: Long get() = remote.received

    /** Reads the central directory. */
    suspend fun open() = withContext(Dispatchers.IO) {
        // One byte first: the answer's Content-Range says how long the file is,
        // without relying on the suffix form ("bytes=-N") every server may not take.
        remote.bytes(0, 0)
        val total = remote.length
        if (total < EOCD_SIZE) throw UpdateException("The update zip is too short to be a zip.")
        val tailStart = maxOf(0L, total - (EOCD_SIZE + 0xFFFF))
        val tail = remote.bytes(tailStart, total - 1)
        val at = findEocd(tail) ?: throw UpdateException("The update zip has no central directory (cut short?).")
        val eocd = ByteBuffer.wrap(tail, at, EOCD_SIZE).slice().order(ByteOrder.LITTLE_ENDIAN)
        val disk = eocd.getShort(4).toInt() and 0xFFFF
        val cdDisk = eocd.getShort(6).toInt() and 0xFFFF
        var count = (eocd.getShort(10).toInt() and 0xFFFF).toLong()
        var cdSize = eocd.getInt(12).toLong() and 0xFFFFFFFFL
        var cdOffset = eocd.getInt(16).toLong() and 0xFFFFFFFFL
        if (count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            // ZIP64: the real numbers are in a record found through the locator just before.
            val locatorAt = tailStart + at - ZIP64_LOCATOR_SIZE
            if (locatorAt < 0) throw UpdateException("The update zip's ZIP64 record is missing.")
            val locator = bytesAt(tail, tailStart, locatorAt, ZIP64_LOCATOR_SIZE)
                ?: remote.bytes(locatorAt, locatorAt + ZIP64_LOCATOR_SIZE - 1)
            val loc = ByteBuffer.wrap(locator).order(ByteOrder.LITTLE_ENDIAN)
            if (loc.getInt(0) != ZIP64_LOCATOR_SIG) throw UpdateException("The update zip's ZIP64 record is missing.")
            val recordAt = loc.getLong(8)
            if (loc.getInt(16) > 1 || recordAt < 0 || recordAt + ZIP64_EOCD_SIZE > total) {
                throw UpdateException("The update zip spans several disks, which isn't supported.")
            }
            val record = bytesAt(tail, tailStart, recordAt, ZIP64_EOCD_SIZE)
                ?: remote.bytes(recordAt, recordAt + ZIP64_EOCD_SIZE - 1)
            val rec = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
            if (rec.getInt(0) != ZIP64_EOCD_SIG) throw UpdateException("The update zip's ZIP64 record is broken.")
            count = rec.getLong(32)
            cdSize = rec.getLong(40)
            cdOffset = rec.getLong(48)
        } else if (disk != 0 || cdDisk != 0) {
            throw UpdateException("The update zip spans several disks, which isn't supported.")
        }
        // Bounds that keep a broken or hostile zip from asking for silly amounts of memory.
        if (count < 0 || count > MAX_ENTRIES || cdSize < 0 || cdSize > MAX_CENTRAL || cdOffset < 0 || cdOffset + cdSize > total) {
            throw UpdateException("The update zip's central directory doesn't add up.")
        }
        val central = bytesAt(tail, tailStart, cdOffset, cdSize.toInt())
            ?: if (cdSize == 0L) ByteArray(0) else remote.bytes(cdOffset, cdOffset + cdSize - 1)
        entries = parseCentral(central, count.toInt(), total)
    }

    /** [size] bytes at [offset] if the tail already read holds them. */
    private fun bytesAt(tail: ByteArray, tailStart: Long, offset: Long, size: Int): ByteArray? {
        if (offset < tailStart || offset + size > tailStart + tail.size) return null
        val from = (offset - tailStart).toInt()
        return tail.copyOfRange(from, from + size)
    }

    private fun findEocd(tail: ByteArray): Int? {
        val buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var at = tail.size - EOCD_SIZE
        while (at >= 0) {
            // The record ends the file exactly, its comment included.
            if (buffer.getInt(at) == EOCD_SIG && at + EOCD_SIZE + (buffer.getShort(at + 20).toInt() and 0xFFFF) == tail.size) {
                return at
            }
            at--
        }
        return null
    }

    private fun parseCentral(bytes: ByteArray, count: Int, total: Long): Map<String, RemoteZipEntry> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val found = LinkedHashMap<String, RemoteZipEntry>()
        var at = 0
        repeat(count) {
            if (at + CENTRAL_SIZE > bytes.size || buffer.getInt(at) != CENTRAL_SIG) {
                throw UpdateException("The update zip's central directory is broken.")
            }
            val flags = buffer.getShort(at + 8).toInt() and 0xFFFF
            val method = buffer.getShort(at + 10).toInt() and 0xFFFF
            val crc = buffer.getInt(at + 16).toLong() and 0xFFFFFFFFL
            var compressed = buffer.getInt(at + 20).toLong() and 0xFFFFFFFFL
            var size = buffer.getInt(at + 24).toLong() and 0xFFFFFFFFL
            val nameLength = buffer.getShort(at + 28).toInt() and 0xFFFF
            val extraLength = buffer.getShort(at + 30).toInt() and 0xFFFF
            val commentLength = buffer.getShort(at + 32).toInt() and 0xFFFF
            var offset = buffer.getInt(at + 42).toLong() and 0xFFFFFFFFL
            val end = at + CENTRAL_SIZE + nameLength + extraLength + commentLength
            if (end > bytes.size) throw UpdateException("The update zip's central directory is broken.")
            val name = String(bytes, at + CENTRAL_SIZE, nameLength, Charsets.UTF_8)
            // ZIP64 extra field: the 8-byte values replace the ones marked 0xFFFFFFFF, in this order.
            var extraAt = at + CENTRAL_SIZE + nameLength
            val extraEnd = extraAt + extraLength
            while (extraAt + 4 <= extraEnd) {
                val id = buffer.getShort(extraAt).toInt() and 0xFFFF
                val length = buffer.getShort(extraAt + 2).toInt() and 0xFFFF
                if (id == 0x0001) {
                    var field = extraAt + 4
                    if (size == 0xFFFFFFFFL && field + 8 <= extraEnd) { size = buffer.getLong(field); field += 8 }
                    if (compressed == 0xFFFFFFFFL && field + 8 <= extraEnd) { compressed = buffer.getLong(field); field += 8 }
                    if (offset == 0xFFFFFFFFL && field + 8 <= extraEnd) { offset = buffer.getLong(field) }
                }
                extraAt += 4 + length
            }
            at = end
            if (name.endsWith("/")) return@repeat
            if (flags and 1 != 0) throw UpdateException("The update zip is encrypted.")
            if (compressed < 0 || size < 0 || offset < 0 || offset + LOCAL_SIZE + compressed > total) {
                throw UpdateException("The update zip's entry for $name doesn't add up.")
            }
            found[name] = RemoteZipEntry(name, method, crc, compressed, size, offset, extraLength)
        }
        return found
    }

    /**
     * Writes [entry]'s contents to [out] and returns their SHA-256 (lower-case
     * hex). Fails, having written possibly part of it, when the CRC-32 or the
     * length is wrong or [expectedSha256] doesn't match; the caller writes to a
     * temporary file for that reason. [onBytes] gets each count of bytes written.
     */
    suspend fun read(
        entry: RemoteZipEntry,
        out: OutputStream,
        expectedSha256: String? = null,
        onBytes: (Long) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        if (entry.method != STORED && entry.method != DEFLATED) {
            throw UpdateException("The update zip packs ${entry.name} in a way this can't unpack (method ${entry.method}).")
        }
        // The local header first: its extra field may differ from the central
        // one, so where the data starts is only known from it. A little slack
        // covers the usual difference in one small request.
        val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
        val guessEnd = minOf(entry.localHeaderOffset + LOCAL_SIZE + nameBytes.size + entry.localExtraGuess + 64, remote.length) - 1
        var header = remote.bytes(entry.localHeaderOffset, guessEnd)
        val local = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (header.size < LOCAL_SIZE || local.getInt(0) != LOCAL_SIG) {
            throw UpdateException("The update zip's entry for ${entry.name} is broken.")
        }
        val nameLength = local.getShort(26).toInt() and 0xFFFF
        val extraLength = local.getShort(28).toInt() and 0xFFFF
        if (LOCAL_SIZE + nameLength > header.size) {
            header = remote.bytes(entry.localHeaderOffset, entry.localHeaderOffset + LOCAL_SIZE + nameLength - 1)
        }
        val name = header.copyOfRange(LOCAL_SIZE, LOCAL_SIZE + nameLength)
        if (!name.contentEquals(nameBytes)) throw UpdateException("The update zip's entry for ${entry.name} names another file.")
        val dataStart = entry.localHeaderOffset + LOCAL_SIZE + nameLength + extraLength
        if (dataStart + entry.compressedSize > remote.length) throw UpdateException("The update zip's entry for ${entry.name} runs past its end.")
        streamData(entry, dataStart, out, expectedSha256, onBytes)
    }

    private suspend fun streamData(
        entry: RemoteZipEntry,
        dataStart: Long,
        out: OutputStream,
        expectedSha256: String?,
        onBytes: (Long) -> Unit
    ): String {
        val unpacker = Unpacker(entry, out, onBytes)
        if (entry.compressedSize > 0) {
            remote.read(dataStart, dataStart + entry.compressedSize - 1, resumable = true) { input, resumeFrom ->
                // A retry carries on from the byte the last attempt reached: the
                // inflater, the CRC and the hash have taken everything before it.
                if (resumeFrom != unpacker.fed) throw UpdateException("The download lost its place.")
                unpacker.feedAll(input, entry.compressedSize - resumeFrom)
            }
        }
        return unpacker.finish(expectedSha256)
    }

    /** Inflates (or copies) the data as it comes, keeping the CRC-32 and SHA-256 of what comes out. */
    private class Unpacker(
        private val entry: RemoteZipEntry,
        private val out: OutputStream,
        private val onBytes: (Long) -> Unit
    ) {
        private val inflater = if (entry.method == DEFLATED) Inflater(true) else null
        private val crc = CRC32()
        private val sha = MessageDigest.getInstance("SHA-256")
        private val inBuffer = ByteArray(64 * 1024)
        private val outBuffer = ByteArray(64 * 1024)
        var fed = 0L
            private set
        private var written = 0L

        private fun emit(bytes: ByteArray, count: Int) {
            if (count <= 0) return
            written += count
            if (written > entry.size) throw UpdateException("${entry.name} came out larger than the zip says.")
            crc.update(bytes, 0, count)
            sha.update(bytes, 0, count)
            // A disk that won't take the bytes is not a network hiccup: no retry,
            // which would also feed the inflater the same bytes twice.
            try {
                out.write(bytes, 0, count)
            } catch (e: java.io.IOException) {
                throw UpdateException("Couldn't write the update to disk: ${e.message}")
            }
            onBytes(count.toLong())
        }

        suspend fun feedAll(input: InputStream, count: Long) {
            var left = count
            while (left > 0) {
                coroutineContext.ensureActive()
                val read = input.read(inBuffer, 0, minOf(inBuffer.size.toLong(), left).toInt())
                if (read < 0) throw java.io.EOFException("The download ended early.")
                left -= read
                fed += read
                if (inflater == null) {
                    emit(inBuffer, read)
                } else {
                    inflater.setInput(inBuffer, 0, read)
                    drain()
                }
            }
        }

        private fun drain() {
            val inflater = inflater ?: return
            try {
                while (!inflater.finished()) {
                    val made = inflater.inflate(outBuffer)
                    if (made == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        emit(outBuffer, made)
                    }
                }
            } catch (e: DataFormatException) {
                throw UpdateException("${entry.name} is damaged in the update zip.")
            }
        }

        fun finish(expectedSha256: String?): String {
            if (inflater != null) {
                if (!inflater.finished()) {
                    // Raw inflate can want one byte more than the stream holds to notice its end.
                    inflater.setInput(ByteArray(1))
                    drain()
                }
                val done = inflater.finished()
                inflater.end()
                if (!done) throw UpdateException("${entry.name} is cut short in the update zip.")
            }
            if (written != entry.size) throw UpdateException("${entry.name} came out the wrong size.")
            if (crc.value != entry.crc) throw UpdateException("${entry.name} failed its CRC check.")
            val hash = UpdatePlanner.hex(sha.digest())
            if (expectedSha256 != null && hash != expectedSha256) {
                throw UpdateException("${entry.name} doesn't match the checksum in the release.")
            }
            return hash
        }
    }

    companion object {
        private const val EOCD_SIG = 0x06054b50
        private const val CENTRAL_SIG = 0x02014b50
        private const val LOCAL_SIG = 0x04034b50
        private const val ZIP64_LOCATOR_SIG = 0x07064b50
        private const val ZIP64_EOCD_SIG = 0x06064b50
        private const val EOCD_SIZE = 22
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_EOCD_SIZE = 56
        private const val CENTRAL_SIZE = 46
        private const val LOCAL_SIZE = 30
        private const val STORED = 0
        private const val DEFLATED = 8
        private const val MAX_ENTRIES = 200_000L
        private const val MAX_CENTRAL = 64L * 1024 * 1024
    }
}
