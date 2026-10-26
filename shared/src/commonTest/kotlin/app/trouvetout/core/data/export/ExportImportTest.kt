package app.trouvetout.core.data.export

import app.trouvetout.core.data.EventFixtures
import app.trouvetout.core.data.ProjectionSnapshot
import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.photo.FakeImageCodec
import app.trouvetout.core.data.photo.InMemoryFileStore
import app.trouvetout.core.data.photo.PhotoStore
import app.trouvetout.core.data.snapshot
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.model.EventJson
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.PlaceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExportImportTest {
    /** One device: database, file store, photo store, export and import services. */
    private class Device(
        deviceId: String,
    ) {
        val db: TrouveToutDb = openTestDatabase()
        val store = db.testEventStore()
        val files = InMemoryFileStore()
        private var photoCounter = 0
        val photos = PhotoStore(files, FakeImageCodec(), {
            "01HPH0T0" + "0".repeat(16) +
                (photoCounter++).toString().padStart(2, '0')
        }, Dispatchers.Unconfined)
        val clock = TickingClock()
        val fx = EventFixtures(deviceId = deviceId, clock = clock, seed = deviceId.hashCode())
        val identity = ExportIdentity({ deviceId }, "0.1.0-test")
        val exporter = ExportService(files, db, store, photos, clock, identity, Dispatchers.Unconfined)
        val importer = ImportService(files, store, photos, Dispatchers.Unconfined)

        fun snapshot(): ProjectionSnapshot = db.snapshot()
    }

    private suspend fun populate(device: Device) {
        val photo = device.photos.store("3000x1500".encodeToByteArray())!!
        device.store.append(
            listOf(
                device.fx.event(EventPayload.PlaceCreated("house", "maison", kind = PlaceKind.ROOT)),
                device.fx.event(
                    EventPayload.PlaceCreated(
                        "cellar",
                        "cave",
                        parentId = "house",
                        kind = PlaceKind.ROOM,
                        aliases = listOf("sous-sol"),
                    ),
                ),
                device.fx.event(EventPayload.ContainerCreated("box7", "carton 7", placeId = "cellar", number = 7)),
                device.fx.event(
                    EventPayload.ItemCreated("skis", "skis", tags = listOf("hiver"), quantity = 2.0, unit = "paires"),
                ),
                device.fx.event(EventPayload.ItemStored("skis", containerId = "box7")),
                device.fx.event(EventPayload.ItemCreated("tent", "tente"), rawInput = "tente dans la cave"),
                device.fx.event(EventPayload.ItemStored("tent", placeId = "cellar")),
                device.fx.event(EventPayload.ItemLent("tent", to = "Marc")),
                device.fx.event(
                    EventPayload.PhotoAttached(
                        "ph1",
                        PhotoOwnerType.ITEM,
                        "tent",
                        photo.fileName,
                        photo.thumbName,
                        photo.width,
                        photo.height,
                        photo.sha256,
                    ),
                ),
                device.fx.event(EventPayload.ItemCreated("old", "vieux truc")),
                device.fx.event(EventPayload.ItemDeleted("old")),
            ),
        )
    }

    /** Copies an export file from one device's store to another's import directory. */
    private fun transfer(
        from: Device,
        relativePath: String,
        to: Device,
    ): String {
        val target = "imports/incoming.zip"
        to.files.write(target, from.files.read(relativePath)!!)
        return target
    }

    @Test
    fun should_export_then_import_into_an_empty_device_and_reach_the_same_state() = runTest {
        val a = Device("device-A")
        populate(a)

        val export = a.exporter.export()
        assertTrue(export.relativePath.startsWith("exports/trouvetout-export-"), export.relativePath)
        assertEquals(ExportCounts(events = 11, items = 2, places = 2, containers = 1, photos = 1), export.counts)

        val b = Device("device-B")
        val result = b.importer.import(transfer(a, export.relativePath, b))

        val report = assertIs<ImportResult.Done>(result).report
        assertEquals(11, report.eventsRead)
        assertEquals(11, report.eventsAppended)
        assertEquals(0, report.eventsSkipped)
        assertEquals(0, report.invalidLines)
        assertEquals(1, report.photosImported)
        assertEquals(a.snapshot(), b.snapshot(), "projection identical after export → import (N9)")
        assertEquals(a.store.all(), b.store.all(), "journal identical, raw input included")
        assertEquals(
            listOf("01HPH0T0000000000000000000.jpg", "01HPH0T0000000000000000000_t.jpg"),
            b.photos.listFiles(),
        )
    }

    @Test
    fun should_merge_an_import_into_a_non_empty_device_without_duplicates() = runTest {
        val a = Device("device-A")
        populate(a)
        val export = a.exporter.export()
        val b = Device("device-B")
        b.importer.import(transfer(a, export.relativePath, b))
        b.clock.millis = a.clock.millis + 60_000 // device B acts after the imported history
        b.store.append(listOf(b.fx.event(EventPayload.ItemTaken("skis"))))

        val again = assertIs<ImportResult.Done>(b.importer.import(transfer(a, export.relativePath, b))).report

        assertEquals(0, again.eventsAppended)
        assertEquals(11, again.eventsSkipped)
        assertEquals(1, again.photosSkipped)
        assertEquals(12L, b.store.count())
        assertEquals("TAKEN", b.db.locationStateQueries.selectByItem("skis").executeAsOne().state)
    }

    @Test
    fun should_tolerate_a_newer_minor_export_with_unknown_fields_and_types() = runTest {
        val b = Device("device-B")
        val manifest =
            """{"formatVersion":1,"appVersion":"9.9","schemaVersion":7,"exportedAt":1,"deviceId":"x",""" +
                """"counts":{"events":2,"items":0,"places":0,"containers":0,"photos":0},"futureField":true}"""
        val events =
            listOf(
                """{"id":"01HF7YAT0036JB21DGC2GBCCVD","at":1700000001000,"deviceId":"dev","actor":"me",""" +
                    """"type":"ItemCreated",""" +
                    """"payload":{"type":"ItemCreated","itemId":"i1","name":"clés","futureFlag":1},""" +
                    """"source":"TEXT","extra":"ignored"}""",
                """{"id":"01HF7YAT0036JB21DGC2GBCCVE","at":1700000002000,"deviceId":"dev","actor":"me",""" +
                    """"type":"ItemTeleported","payload":{"type":"ItemTeleported","itemId":"i1","to":"Mars"},""" +
                    """"source":"HOLOGRAM"}""",
                "this line is not json",
            ).joinToString("\n")
        b.files.write(
            "imports/newer.zip",
            zipOf(
                entry(ExportEntries.MANIFEST, manifest.encodeToByteArray()),
                entry(ExportEntries.EVENTS, events.encodeToByteArray()),
                entry("future/notes.txt", "x".encodeToByteArray()),
            ),
        )

        val report = assertIs<ImportResult.Done>(b.importer.import("imports/newer.zip")).report

        assertEquals(3, report.eventsRead)
        assertEquals(2, report.eventsAppended)
        assertEquals(1, report.unknownPayloads)
        assertEquals(1, report.invalidLines)
        assertEquals(1, report.ignoredEntries)
        assertEquals(1L, b.db.itemQueries.countActive().executeAsOne())
        val kept = b.store.all().last()
        assertEquals("ItemTeleported", kept.type)
        // Re-exported untouched: the unknown event keeps its original JSON payload.
        assertEquals(
            """{"type":"ItemTeleported","itemId":"i1","to":"Mars"}""",
            EventJson.encodePayload(kept.payload),
        )
    }

    @Test
    fun should_reject_archives_that_cannot_be_trusted() = runTest {
        val b = Device("device-B")
        assertEquals(
            ImportResult.Rejected(ImportRejection.FILE_NOT_FOUND),
            b.importer.import("imports/missing.zip"),
        )

        b.files.write("imports/garbage.zip", "definitely not a zip file".encodeToByteArray())
        assertEquals(
            ImportResult.Rejected(ImportRejection.NOT_AN_ARCHIVE),
            b.importer.import("imports/garbage.zip"),
        )

        b.files.write("imports/nomanifest.zip", zipOf(entry(ExportEntries.EVENTS, ByteArray(0))))
        assertEquals(
            ImportResult.Rejected(ImportRejection.MANIFEST_MISSING),
            b.importer.import("imports/nomanifest.zip"),
        )

        val tooNew =
            """{"formatVersion":2,"appVersion":"x","schemaVersion":1,"exportedAt":1,"deviceId":"x",""" +
                """"counts":{"events":0,"items":0,"places":0,"containers":0,"photos":0}}"""
        b.files.write(
            "imports/toonew.zip",
            zipOf(
                entry(ExportEntries.MANIFEST, tooNew.encodeToByteArray()),
                entry(ExportEntries.EVENTS, ByteArray(0)),
            ),
        )
        assertEquals(ImportResult.Rejected(ImportRejection.FORMAT_TOO_NEW), b.importer.import("imports/toonew.zip"))

        b.files.write(
            "imports/badmanifest.zip",
            zipOf(entry(ExportEntries.MANIFEST, "{".encodeToByteArray()), entry(ExportEntries.EVENTS, ByteArray(0))),
        )
        assertEquals(
            ImportResult.Rejected(ImportRejection.MANIFEST_INVALID),
            b.importer.import("imports/badmanifest.zip"),
        )
        assertEquals(0L, b.store.count())
    }

    @Test
    fun should_never_write_a_photo_outside_the_store_or_without_re_encoding() = runTest {
        val b = Device("device-B")
        val manifest =
            """{"formatVersion":1,"appVersion":"x","schemaVersion":1,"exportedAt":1,"deviceId":"x",""" +
                """"counts":{"events":0,"items":0,"places":0,"containers":0,"photos":3}}"""
        b.files.write(
            "imports/photos.zip",
            zipOf(
                entry(ExportEntries.MANIFEST, manifest.encodeToByteArray()),
                entry(ExportEntries.EVENTS, ByteArray(0)),
                entry("photos/../../etc/passwd.jpg", "640x480".encodeToByteArray(), store = true),
                entry("photos/01HPH0T0000000000000000001.jpg", "not an image".encodeToByteArray(), store = true),
                entry("photos/01HPH0T0000000000000000002.jpg", "5000x5000".encodeToByteArray(), store = true),
                entry("photos/01HPH0T0000000000000000002_t.jpg", "666x666".encodeToByteArray(), store = true),
            ),
        )

        val report = assertIs<ImportResult.Done>(b.importer.import("imports/photos.zip")).report

        assertEquals(1, report.photosImported)
        assertEquals(2, report.photosRejected)
        assertEquals(
            listOf("01HPH0T0000000000000000002.jpg", "01HPH0T0000000000000000002_t.jpg"),
            b.photos.listFiles(),
        )
        assertEquals(
            "jpeg:2048x2048:q85",
            b.files.read("photos/01HPH0T0000000000000000002.jpg")?.decodeToString(),
            "re-encoded and bounded",
        )
        assertEquals(
            "jpeg:256x256:q80",
            b.files.read("photos/01HPH0T0000000000000000002_t.jpg")?.decodeToString(),
            "thumbnail regenerated, not copied",
        )
        assertTrue(b.files.files.keys.none { "passwd" in it })
    }

    @Test
    fun should_name_exports_with_a_utc_timestamp() {
        assertEquals("19700101-000000", utcTimestamp(0))
        assertEquals("20261024-153000", utcTimestamp(1_792_855_800_000L))
        assertEquals("20000229-235959", utcTimestamp(951_868_799_000L))
    }

    @Test
    fun should_reject_a_corrupt_events_entry_without_throwing() = runTest {
        val b = Device("dev-corrupt")
        val archive =
            zipOf(
                entry(
                    "manifest.json",
                    (
                        """{"formatVersion":1,"appVersion":"x","schemaVersion":1,"exportedAt":1,"deviceId":"x",""" +
                            """"counts":{"events":1,"items":0,"places":0,"containers":0,"photos":0}}"""
                    ).encodeToByteArray(),
                ),
                entry("events.jsonl", "{}".encodeToByteArray()),
            )
        // Flip one byte of the (deflated) events payload: the CRC no longer matches.
        val eventsOffset = archive.indexOfSubArray("events.jsonl".encodeToByteArray()) + "events.jsonl".length
        archive[eventsOffset] = (archive[eventsOffset].toInt() xor 0x55).toByte()
        b.files.files["imports/corrupt.zip"] = archive

        val result = b.importer.import("imports/corrupt.zip")

        assertEquals(ImportResult.Rejected(ImportRejection.EVENTS_CORRUPT), result)
    }

    @Test
    fun should_abort_a_failed_export_without_leaving_a_file() = runTest {
        val a = Device("dev-fail")
        a.store.append(listOf(a.fx.event(EventPayload.ItemCreated("i1", "skis"))))
        a.files.failWritesContaining = "exports/"

        val failure = runCatching { a.exporter.export() }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)

        assertTrue(a.files.files.keys.none { it.startsWith("exports/") }, "no half-written archive")
    }

    private fun ByteArray.indexOfSubArray(needle: ByteArray): Int =
        (0..size - needle.size).first { start -> needle.indices.all { this[start + it] == needle[it] } }
}
