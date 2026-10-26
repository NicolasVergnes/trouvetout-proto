package app.trouvetout.core.data.photo

import app.trouvetout.core.data.EventFixtures
import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.usecase.PhotoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class BinServiceTest {
    private val db = openTestDatabase()
    private val store = db.testEventStore()
    private val files = InMemoryFileStore()
    private var counter = 0
    private val photos = PhotoStore(files, FakeImageCodec(), {
        "01HTEST" + "0".repeat(17) +
            (counter++).toString().padStart(2, '0')
    }, Dispatchers.Unconfined)
    private val clock = TickingClock()
    private val fx = EventFixtures(clock = clock)
    private val bin = BinService(db, photos, clock, Dispatchers.Unconfined)

    private fun attached(
        photoId: String,
        itemId: String,
        stored: PhotoFile,
    ) = fx.event(
        EventPayload.PhotoAttached(
            photoId,
            PhotoOwnerType.ITEM,
            itemId,
            stored.fileName,
            stored.thumbName,
            1,
            1,
            stored.sha256,
        ),
    )

    @Test
    fun should_delete_photo_files_of_items_deleted_more_than_30_days_ago_and_keep_the_rest() = runTest {
        val old = photos.store("100x100".encodeToByteArray())!!
        val recent = photos.store("100x100".encodeToByteArray())!!
        val kept = photos.store("100x100".encodeToByteArray())!!
        store.append(
            listOf(
                fx.event(EventPayload.ItemCreated("old", "vieux")),
                attached("p1", "old", old),
                fx.event(EventPayload.ItemDeleted("old")),
                fx.event(EventPayload.ItemCreated("alive", "vivant")),
                attached("p3", "alive", kept),
            ),
        )
        clock.millis += 31.days.inWholeMilliseconds
        store.append(
            listOf(
                fx.event(EventPayload.ItemCreated("recent", "récent")),
                attached("p2", "recent", recent),
                fx.event(EventPayload.ItemDeleted("recent")),
            ),
        )

        val report = bin.purgeExpired()

        assertEquals(PurgeReport(itemsPurged = 1, photoFilesDeleted = 2), report)
        assertFalse(photos.exists(old.fileName))
        assertFalse(photos.exists(old.thumbName))
        assertTrue(photos.exists(recent.fileName), "deleted 0 days ago: kept")
        assertTrue(photos.exists(kept.fileName))
        assertEquals(
            3L,
            db.itemQueries.selectAll().executeAsList().size.toLong(),
            "rows and events are never purged",
        )
        assertEquals(PurgeReport(0, 0), bin.purgeExpired(), "idempotent")
    }

    @Test
    fun should_delete_files_of_photos_detached_long_ago() = runTest {
        val photo = photos.store("100x100".encodeToByteArray())!!
        store.append(
            listOf(
                fx.event(EventPayload.ItemCreated("i", "i")),
                fx.event(
                    EventPayload.PhotoAttached(
                        "p",
                        PhotoOwnerType.ITEM,
                        "i",
                        photo.fileName,
                        photo.thumbName,
                        1,
                        1,
                        photo.sha256,
                    ),
                ),
                fx.event(EventPayload.PhotoDetached("p")),
            ),
        )
        assertEquals(0, bin.purgeExpired().photoFilesDeleted)
        clock.millis += 31.days.inWholeMilliseconds
        assertEquals(2, bin.purgeExpired().photoFilesDeleted)
    }

    @Test
    fun should_keep_a_file_still_referenced_by_a_live_photo_row() = runTest {
        val shared = photos.store("100x100".encodeToByteArray())!!
        store.append(
            listOf(
                fx.event(EventPayload.ItemCreated("gone", "parti")),
                attached("p1", "gone", shared),
                fx.event(EventPayload.ItemDeleted("gone")),
                fx.event(EventPayload.ItemCreated("alive", "vivant")),
                // An imported journal may point two rows at the same file.
                attached("p2", "alive", shared),
            ),
        )
        clock.millis += 31.days.inWholeMilliseconds

        assertEquals(PurgeReport(itemsPurged = 0, photoFilesDeleted = 0), bin.purgeExpired())
        assertTrue(photos.exists(shared.fileName))
    }

    @Test
    fun should_skip_rows_with_foreign_file_names_and_still_purge_the_rest() = runTest {
        val good = photos.store("100x100".encodeToByteArray())!!
        store.append(
            listOf(
                fx.event(EventPayload.ItemCreated("bad", "mauvais")),
                fx.event(
                    EventPayload.PhotoAttached(
                        "p1",
                        PhotoOwnerType.ITEM,
                        "bad",
                        "../../etc/passwd",
                        "../x",
                        1,
                        1,
                        "sha",
                    ),
                ),
                fx.event(EventPayload.ItemDeleted("bad")),
                fx.event(EventPayload.ItemCreated("old", "vieux")),
                attached("p2", "old", good),
                fx.event(EventPayload.ItemDeleted("old")),
            ),
        )
        clock.millis += 31.days.inWholeMilliseconds

        assertEquals(PurgeReport(itemsPurged = 1, photoFilesDeleted = 2), bin.purgeExpired())
        assertFalse(photos.exists(good.fileName))
    }
}
