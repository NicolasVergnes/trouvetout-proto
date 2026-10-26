package app.trouvetout.core.data.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** U8: the export must open without the app — checked against the JDK's own ZIP implementation, both ways. */
class ZipInteropTest {
    @Test
    fun should_produce_archives_that_a_standard_zip_reader_opens() {
        val photo = Random(3).nextBytes(4_000)
        val archive =
            zipOf(
                entry("manifest.json", """{"formatVersion":1}""".encodeToByteArray()),
                entry("events.jsonl", "line\n".repeat(2_000).encodeToByteArray()),
                entry("photos/01HX.jpg", photo, store = true),
                entry("empty.txt", ByteArray(0)),
            )

        val read = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e -> read[e.name] = zip.readBytes() }
        }

        assertEquals(listOf("manifest.json", "events.jsonl", "photos/01HX.jpg", "empty.txt"), read.keys.toList())
        assertContentEquals("""{"formatVersion":1}""".encodeToByteArray(), read["manifest.json"])
        assertContentEquals("line\n".repeat(2_000).encodeToByteArray(), read["events.jsonl"])
        assertContentEquals(photo, read["photos/01HX.jpg"])
        assertContentEquals(ByteArray(0), read["empty.txt"])
    }

    @Test
    fun should_read_archives_written_by_a_standard_zip_writer() {
        val photo = Random(4).nextBytes(4_000)
        val bytes =
            ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write("""{"formatVersion":1}""".encodeToByteArray())
                    zip.closeEntry()
                    zip.setMethod(ZipOutputStream.STORED)
                    val stored =
                        ZipEntry("photos/01HX.jpg").apply {
                            size = photo.size.toLong()
                            compressedSize = photo.size.toLong()
                            crc = Crc32.of(photo)
                        }
                    zip.putNextEntry(stored)
                    zip.write(photo)
                    zip.closeEntry()
                }
            }.toByteArray()

        val reader = ZipReader(MemorySource(bytes), maxEntries = 10)
        assertEquals(listOf("manifest.json", "photos/01HX.jpg"), reader.records.map { it.name })
        assertContentEquals("""{"formatVersion":1}""".encodeToByteArray(), reader.read(reader.records[0]))
        assertContentEquals(photo, reader.read(reader.records[1]))
    }
}
