// ZIP field widths and bit masks below are the file-format specification itself, not tunables.
@file:Suppress("MagicNumber")

package app.trouvetout.core.data.export

import app.trouvetout.core.platform.ByteSink
import app.trouvetout.core.platform.RandomAccessSource
import app.trouvetout.core.platform.deflateRaw
import app.trouvetout.core.platform.inflateRaw

/**
 * Minimal ZIP container (PKWARE APPNOTE): local headers, central directory, end record; methods STORED (0)
 * and DEFLATE (8); no ZIP64, no encryption, no data descriptors. Enough for `trouvetout-export-*.zip`, which
 * any archiver opens, and strict enough for a safe import (every size and CRC is checked; entry names are
 * validated by the caller). The archive is streamed entry by entry: only one entry is in memory at a time.
 */
public object Zip {
    public const val METHOD_STORED: Int = 0
    public const val METHOD_DEFLATE: Int = 8
    internal const val SIG_LOCAL = 0x04034b50L
    internal const val SIG_CENTRAL = 0x02014b50L
    internal const val SIG_END = 0x06054b50L
    internal const val VERSION_NEEDED = 20
    internal const val VERSION_MADE_BY = 20
    internal const val FLAG_UTF8 = 0x0800
    internal const val FLAG_ENCRYPTED = 0x0001
    internal const val FLAG_DATA_DESCRIPTOR = 0x0008
    internal const val DOS_TIME = 0
    internal const val DOS_DATE = 0x21 // 1980-01-01: exports carry their date in manifest.json, not in DOS fields
    internal const val END_RECORD_SIZE = 22
    internal const val MAX_COMMENT = 0xFFFF
    internal const val MAX_NAME = 0xFFFF
    internal const val MAX_ARCHIVE = 0xFFFF_FFFFL
}

/** Writes entries one by one to a [ByteSink]; [finish] appends the central directory. */
public class ZipWriter(
    private val sink: ByteSink,
) {
    private val central = ByteWriter()
    private var count = 0
    private var finished = false

    /** [store] keeps already-compressed data (photos) as is: deflating a JPEG again only costs time. */
    public fun add(
        name: String,
        bytes: ByteArray,
        store: Boolean = false,
    ) {
        check(!finished) { "Archive finished" }
        val nameBytes = name.encodeToByteArray()
        require(nameBytes.size <= Zip.MAX_NAME) { "Entry name too long" }
        val crc = Crc32.of(bytes)
        val method = if (store) Zip.METHOD_STORED else Zip.METHOD_DEFLATE
        val data = if (store) bytes else deflateRaw(bytes)
        val offset = sink.position
        require(offset + data.size < Zip.MAX_ARCHIVE) { "Archive too large" }
        val local = ByteWriter()
        local.u32(Zip.SIG_LOCAL)
        local.u16(Zip.VERSION_NEEDED)
        local.u16(Zip.FLAG_UTF8)
        local.u16(method)
        local.u16(Zip.DOS_TIME)
        local.u16(Zip.DOS_DATE)
        local.u32(crc)
        local.u32(data.size.toLong())
        local.u32(bytes.size.toLong())
        local.u16(nameBytes.size)
        local.u16(0)
        local.bytes(nameBytes)
        sink.write(local.toByteArray())
        sink.write(data)
        central.u32(Zip.SIG_CENTRAL)
        central.u16(Zip.VERSION_MADE_BY)
        central.u16(Zip.VERSION_NEEDED)
        central.u16(Zip.FLAG_UTF8)
        central.u16(method)
        central.u16(Zip.DOS_TIME)
        central.u16(Zip.DOS_DATE)
        central.u32(crc)
        central.u32(data.size.toLong())
        central.u32(bytes.size.toLong())
        central.u16(nameBytes.size)
        central.u16(0)
        central.u16(0)
        central.u16(0)
        central.u16(0)
        central.u32(0)
        central.u32(offset)
        central.bytes(nameBytes)
        count++
    }

    /** Writes the central directory and the end record; the sink is left open for the caller to close. */
    public fun finish() {
        check(!finished) { "Archive finished" }
        finished = true
        val centralOffset = sink.position
        sink.write(central.toByteArray())
        val end = ByteWriter()
        end.u32(Zip.SIG_END)
        end.u16(0)
        end.u16(0)
        end.u16(count)
        end.u16(count)
        end.u32(central.size.toLong())
        end.u32(centralOffset)
        end.u16(0)
        sink.write(end.toByteArray())
    }
}

/** Directory listing of an archive: names, sizes and offsets, without inflating anything. */
public class ZipRecord(
    public val name: String,
    public val method: Int,
    public val crc: Long,
    public val compressedSize: Int,
    public val size: Int,
    public val localHeaderOffset: Long,
)

/** Reads an archive from a [RandomAccessSource]. Every malformed structure is an [IllegalArgumentException]. */
public class ZipReader(
    private val source: RandomAccessSource,
    maxEntries: Int,
) {
    public val records: List<ZipRecord>

    init {
        val eocdOffset = findEndRecord()
        val eocd = ByteReader(source.read(eocdOffset, Zip.END_RECORD_SIZE), 0)
        require(eocd.u32() == Zip.SIG_END) { "Not a ZIP archive" }
        eocd.skip(2 + 2) // disk numbers
        val entriesOnDisk = eocd.u16()
        val entries = eocd.u16()
        val centralSize = eocd.u32()
        val centralOffset = eocd.u32()
        require(entries == entriesOnDisk && entries <= maxEntries) { "Too many entries" }
        require(centralOffset + centralSize == eocdOffset && centralSize <= MAX_CENTRAL) { "Bad central directory" }
        val central = ByteReader(source.read(centralOffset, centralSize.toInt()), 0)
        records =
            List(entries) {
                require(central.u32() == Zip.SIG_CENTRAL) { "Bad central header" }
                central.skip(2 + 2) // version made by, version needed
                val flags = central.u16()
                val method = central.u16()
                central.skip(2 + 2) // time, date
                val crc = central.u32()
                val compressedSize = central.u32()
                val size = central.u32()
                val nameLength = central.u16()
                val extraLength = central.u16()
                val commentLength = central.u16()
                central.skip(2 + 2 + 4) // disk start, internal attrs, external attrs
                val localOffset = central.u32()
                val name = central.bytes(nameLength).decodeToString()
                central.skip(extraLength + commentLength)
                // Sizes and CRC come from the central directory, so streaming writers that set the data-descriptor
                // flag (java.util.zip, many apps) are fine; only encryption is refused.
                require(flags and Zip.FLAG_ENCRYPTED == 0) { "Unsupported ZIP feature" }
                require(method == Zip.METHOD_STORED || method == Zip.METHOD_DEFLATE) {
                    "Unsupported compression method"
                }
                require(compressedSize <= Int.MAX_VALUE && size <= Int.MAX_VALUE && localOffset < centralOffset) {
                    "Bad entry sizes"
                }
                ZipRecord(name, method, crc, compressedSize.toInt(), size.toInt(), localOffset)
            }
        require(central.position == centralSize.toInt()) { "Bad central directory" }
    }

    /** Extracts one entry, verifying its size and CRC. */
    public fun read(record: ZipRecord): ByteArray {
        val header = ByteReader(source.read(record.localHeaderOffset, LOCAL_HEADER_SIZE), 0)
        require(header.u32() == Zip.SIG_LOCAL) { "Bad local header" }
        header.skip(2 + 2 + 2 + 2 + 2 + 4 + 4 + 4) // version, flags, method, time, date, crc, sizes
        val nameLength = header.u16()
        val extraLength = header.u16()
        val dataOffset = record.localHeaderOffset + LOCAL_HEADER_SIZE + nameLength + extraLength
        val data = source.read(dataOffset, record.compressedSize)
        val bytes = if (record.method == Zip.METHOD_STORED) data else inflateRaw(data, record.size)
        require(bytes.size == record.size && Crc32.of(bytes) == record.crc) { "Entry corrupt" }
        return bytes
    }

    private fun findEndRecord(): Long {
        require(source.size >= Zip.END_RECORD_SIZE) { "Not a ZIP archive" }
        val tailLength = minOf(source.size, (Zip.END_RECORD_SIZE + Zip.MAX_COMMENT).toLong()).toInt()
        val tailStart = source.size - tailLength
        val tail = source.read(tailStart, tailLength)
        for (i in tailLength - Zip.END_RECORD_SIZE downTo 0) {
            if (ByteReader(tail, i).u32() == Zip.SIG_END) return tailStart + i
        }
        throw IllegalArgumentException("Not a ZIP archive")
    }

    private companion object {
        const val LOCAL_HEADER_SIZE = 30
        const val MAX_CENTRAL = 64L * 1024 * 1024
    }
}

/** Little-endian growable buffer. */
internal class ByteWriter {
    private var buffer = ByteArray(INITIAL)
    var size: Int = 0
        private set

    fun u16(value: Int) {
        ensure(2)
        buffer[size++] = (value and 0xFF).toByte()
        buffer[size++] = ((value ushr 8) and 0xFF).toByte()
    }

    fun u32(value: Long) {
        ensure(4)
        for (shift in 0 until 32 step 8) buffer[size++] = ((value ushr shift) and 0xFF).toByte()
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    private companion object {
        const val INITIAL = 1024
    }
}

/** Little-endian bounds-checked cursor; every overrun is an [IllegalArgumentException]. */
internal class ByteReader(
    private val data: ByteArray,
    start: Int,
) {
    var position: Int = start
        private set

    init {
        require(start in 0..data.size) { "Offset out of range" }
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun u32(): Long {
        var value = 0L
        for (shift in 0 until 32 step 8) value = value or (u8().toLong() shl shift)
        return value
    }

    fun bytes(length: Int): ByteArray {
        require(length >= 0 && position + length <= data.size) { "Truncated archive" }
        return data.copyOfRange(position, position + length).also { position += length }
    }

    fun skip(length: Int) {
        require(length >= 0 && position + length <= data.size) { "Truncated archive" }
        position += length
    }

    private fun u8(): Int {
        require(position < data.size) { "Truncated archive" }
        return data[position++].toInt() and 0xFF
    }
}
