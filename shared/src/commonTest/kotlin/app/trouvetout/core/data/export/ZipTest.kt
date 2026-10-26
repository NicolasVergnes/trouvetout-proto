package app.trouvetout.core.data.export

import app.trouvetout.core.platform.deflateRaw
import app.trouvetout.core.platform.inflateRaw
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipTest {
    @Test
    fun should_compute_the_reference_crc32() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0L, Crc32.of(ByteArray(0)))
    }

    @Test
    fun should_round_trip_raw_deflate_including_empty_and_random_data() {
        val samples = listOf(ByteArray(0), "hello hello hello hello".encodeToByteArray(), Random(1).nextBytes(100_000))
        for (sample in samples) {
            assertContentEquals(sample, inflateRaw(deflateRaw(sample), sample.size))
        }
        val text = "a".repeat(50_000).encodeToByteArray()
        assertTrue(deflateRaw(text).size < 1_000, "repetitive text compresses")
    }

    @Test
    fun should_reject_a_declared_size_that_does_not_match_the_stream() {
        val packed = deflateRaw("twelve bytes".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { inflateRaw(packed, 5) }
        assertFailsWith<IllegalArgumentException> { inflateRaw(packed, 40) }
        assertFailsWith<IllegalArgumentException> { inflateRaw(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), 3) }
    }

    @Test
    fun should_write_an_archive_that_lists_and_reads_back_identically() {
        val photo = Random(2).nextBytes(5_000)
        val archive =
            zipOf(
                entry("manifest.json", """{"formatVersion":1}""".encodeToByteArray()),
                entry("events.jsonl", "x".repeat(10_000).encodeToByteArray()),
                entry("photos/01HX.jpg", photo, store = true),
                entry("empty.txt", ByteArray(0)),
            )

        val reader = ZipReader(MemorySource(archive), maxEntries = 10)
        val records = reader.records
        assertEquals(listOf("manifest.json", "events.jsonl", "photos/01HX.jpg", "empty.txt"), records.map { it.name })
        assertEquals(Zip.METHOD_STORED, records[2].method)
        assertEquals(Zip.METHOD_DEFLATE, records[1].method)
        assertTrue(records[1].compressedSize < records[1].size)
        assertContentEquals("""{"formatVersion":1}""".encodeToByteArray(), reader.read(records[0]))
        assertContentEquals(photo, reader.read(records[2]))
        assertContentEquals(ByteArray(0), reader.read(records[3]))
        // Signature of a real ZIP, readable by any archiver.
        assertEquals(listOf(0x50, 0x4B, 0x03, 0x04), archive.take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun should_refuse_garbage_truncated_and_oversized_archives() {
        assertFailsWith<IllegalArgumentException> {
            ZipReader(MemorySource("not a zip at all, really".encodeToByteArray()), 10)
        }
        assertFailsWith<IllegalArgumentException> { ZipReader(MemorySource(ByteArray(0)), 10) }
        val archive = zipOf(entry("a.txt", "A".encodeToByteArray()), entry("b.txt", "B".encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> { ZipReader(MemorySource(archive), maxEntries = 1) }
        assertFailsWith<IllegalArgumentException> { ZipReader(MemorySource(archive.copyOf(archive.size - 5)), 10) }
    }

    @Test
    fun should_detect_a_corrupted_entry() {
        val archive = zipOf(entry("data.bin", Random(3).nextBytes(2_000), store = true))
        val reader = ZipReader(MemorySource(archive), 10)
        val record = reader.records.single()
        val damaged = archive.copyOf()
        val index = record.localHeaderOffset.toInt() + 40
        damaged[index] = (damaged[index] + 1).toByte()
        assertFailsWith<IllegalArgumentException> { ZipReader(MemorySource(damaged), 10).read(record) }
    }

    @Test
    fun should_refuse_adding_after_finish() {
        val sink = MemorySink()
        val writer = ZipWriter(sink)
        writer.finish()
        assertFailsWith<IllegalStateException> { writer.add("late.txt", ByteArray(1)) }
    }
}
