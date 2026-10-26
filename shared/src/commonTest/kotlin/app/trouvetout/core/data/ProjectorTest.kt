package app.trouvetout.core.data

import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.db.toItem
import app.trouvetout.core.data.db.toLocationState
import app.trouvetout.core.data.db.toPlace
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.model.ReminderKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectorTest {
    private val db = openTestDatabase()
    private val store = db.testEventStore()
    private val fx = EventFixtures()

    private suspend fun apply(vararg payloads: EventPayload) {
        store.append(payloads.map { fx.event(it) })
    }

    private fun location(itemId: String) = db.locationStateQueries.selectByItem(itemId).executeAsOne().toLocationState()

    @Test
    fun should_create_an_item_with_a_location_row_even_without_place() = runTest {
        apply(EventPayload.ItemCreated("i1", "passeport", tags = listOf("papiers"), sensitive = true))

        val item = db.itemQueries.selectById("i1").executeAsOne().toItem()
        assertEquals("passeport", item.name)
        assertEquals(listOf("papiers"), item.tags)
        assertTrue(item.sensitive)
        val loc = location("i1")
        assertEquals(LocationStatus.STORED, loc.status)
        assertNull(loc.placeId)
        assertNull(loc.containerId)
    }

    @Test
    fun should_follow_store_take_return_move_lend_lifecycle() = runTest {
        apply(
            EventPayload.PlaceCreated("garage", "garage", kind = PlaceKind.ROOT),
            EventPayload.PlaceCreated("atelier", "atelier", kind = PlaceKind.ROOT),
            EventPayload.ItemCreated("drill", "perceuse"),
            EventPayload.ItemStored("drill", placeId = "garage"),
        )
        assertEquals("garage", location("drill").placeId)

        apply(EventPayload.ItemTaken("drill"))
        assertEquals(LocationStatus.TAKEN, location("drill").status)
        assertEquals("garage", location("drill").placeId, "a taken item keeps its last known place")

        apply(EventPayload.ItemReturned("drill"))
        assertEquals(LocationStatus.STORED, location("drill").status)

        apply(EventPayload.ItemMovedTo("drill", placeId = "atelier"))
        assertEquals("atelier", location("drill").placeId)

        apply(EventPayload.ItemLent("drill", to = "Marc"))
        val lent = location("drill")
        assertEquals(LocationStatus.LENT, lent.status)
        assertEquals("Marc", lent.lentTo)

        apply(EventPayload.ItemReturned("drill"))
        val back = location("drill")
        assertEquals(LocationStatus.STORED, back.status)
        assertNull(back.lentTo)
        assertEquals("atelier", back.placeId)
    }

    @Test
    fun should_put_an_item_in_a_container_and_clear_the_place() = runTest {
        apply(
            EventPayload.PlaceCreated("cave", "cave", kind = PlaceKind.ROOT),
            EventPayload.ContainerCreated("box7", "Carton 7", placeId = "cave", number = 7),
            EventPayload.ItemCreated("lights", "guirlandes"),
            EventPayload.ItemStored("lights", placeId = "cave"),
            EventPayload.ItemStored("lights", containerId = "box7"),
        )
        val loc = location("lights")
        assertEquals("box7", loc.containerId)
        assertNull(loc.placeId, "the place is derived from the container")
    }

    @Test
    fun should_move_a_container_with_everything_inside_in_one_event() = runTest {
        apply(
            EventPayload.PlaceCreated("cave", "cave", kind = PlaceKind.ROOT),
            EventPayload.PlaceCreated("garage", "garage", kind = PlaceKind.ROOT),
            EventPayload.ContainerCreated("box7", "Carton 7", placeId = "cave"),
            EventPayload.ItemCreated("a", "a"),
            EventPayload.ItemCreated("b", "b"),
            EventPayload.ItemStored("a", containerId = "box7"),
            EventPayload.ItemStored("b", containerId = "box7"),
        )
        val before = store.count()

        apply(EventPayload.ContainerMoved("box7", placeId = "garage"))

        assertEquals(before + 1, store.count())
        assertEquals("garage", db.containerQueries.selectById("box7").executeAsOne().place_id)
        assertEquals(listOf("a", "b"), db.locationStateQueries.itemsInContainer("box7").executeAsList().sorted())
    }

    @Test
    fun should_patch_only_the_given_item_fields() = runTest {
        apply(
            EventPayload.ItemCreated("i1", "câble", description = "blanc", quantity = 2.0, unit = "m"),
            EventPayload.ItemUpdated("i1", name = "câble USB-C", pinned = true),
        )
        var item = db.itemQueries.selectById("i1").executeAsOne().toItem()
        assertEquals("câble USB-C", item.name)
        assertEquals("blanc", item.description)
        assertEquals(2.0, item.quantity)
        assertTrue(item.pinned)

        apply(EventPayload.ItemUpdated("i1", clearDescription = true, clearQuantity = true))
        item = db.itemQueries.selectById("i1").executeAsOne().toItem()
        assertNull(item.description)
        assertNull(item.quantity)
        assertNull(item.unit)
    }

    @Test
    fun should_archive_delete_and_restore_without_losing_the_location() = runTest {
        apply(
            EventPayload.PlaceCreated("cave", "cave", kind = PlaceKind.ROOT),
            EventPayload.ItemCreated("i1", "skis"),
            EventPayload.ItemStored("i1", placeId = "cave"),
            EventPayload.ItemLent("i1", to = "Léa"),
            EventPayload.ItemArchived("i1"),
        )
        assertTrue(db.itemQueries.selectById("i1").executeAsOne().archived)
        assertEquals(LocationStatus.LENT, location("i1").status, "archiving keeps the loan in progress")
        assertEquals("Léa", location("i1").lentTo)

        apply(EventPayload.ItemArchived("i1", archived = false))
        assertFalse(db.itemQueries.selectById("i1").executeAsOne().archived)
        assertEquals(LocationStatus.LENT, location("i1").status)

        apply(EventPayload.ItemDeleted("i1"))
        assertNotNull(db.itemQueries.selectById("i1").executeAsOne().deleted_at)
        assertEquals(0L, db.itemQueries.countActive().executeAsOne())
        assertEquals("cave", location("i1").placeId)

        apply(EventPayload.ItemRestored("i1"))
        assertNull(db.itemQueries.selectById("i1").executeAsOne().deleted_at)
        assertEquals(1L, db.itemQueries.countActive().executeAsOne())
    }

    @Test
    fun should_ignore_merges_into_a_descendant_or_of_retired_places() = runTest {
        apply(
            EventPayload.PlaceCreated("house", "maison", kind = PlaceKind.ROOT),
            EventPayload.PlaceCreated("cellar", "cave", parentId = "house", kind = PlaceKind.ROOM),
            EventPayload.PlaceCreated("shelf", "étagère", parentId = "cellar", kind = PlaceKind.FURNITURE),
            EventPayload.PlaceMerged("house", intoPlaceId = "shelf"),
        )
        assertNull(db.placeQueries.selectById("house").executeAsOne().merged_into, "merge into own descendant ignored")
        assertEquals("house", db.placeQueries.selectById("cellar").executeAsOne().parent_id)

        apply(
            EventPayload.PlaceMerged("cellar", intoPlaceId = "house"),
            EventPayload.PlaceMerged("house", intoPlaceId = "cellar"),
        )
        assertEquals("house", db.placeQueries.selectById("cellar").executeAsOne().merged_into)
        assertNull(db.placeQueries.selectById("house").executeAsOne().merged_into, "merge into a retired place ignored")
        assertNull(db.placeQueries.selectById("house").executeAsOne().deleted_at)
        assertEquals("house", db.placeQueries.selectById("shelf").executeAsOne().parent_id)
    }

    @Test
    fun should_keep_unknown_payloads_in_the_journal_and_ignore_them() = runTest {
        apply(EventPayload.Unknown("ItemTeleported", """{"type":"ItemTeleported","itemId":"i9"}"""))
        assertEquals(1L, store.count())
        assertEquals("ItemTeleported", store.all().single().type)
        assertEquals(0L, db.itemQueries.countActive().executeAsOne())
    }

    @Test
    fun should_merge_places_moving_children_containers_items_and_aliases() = runTest {
        apply(
            EventPayload.PlaceCreated("house", "maison", kind = PlaceKind.ROOT),
            EventPayload.PlaceCreated(
                "cellar",
                "cave",
                parentId = "house",
                kind = PlaceKind.ROOM,
                aliases = listOf("sous-sol"),
            ),
            EventPayload.PlaceCreated("basement", "cellier", parentId = "house", kind = PlaceKind.ROOM),
            EventPayload.PlaceCreated("shelf", "étagère", parentId = "cellar", kind = PlaceKind.FURNITURE),
            EventPayload.ContainerCreated("box", "Carton", placeId = "cellar"),
            EventPayload.ItemCreated("i1", "skis"),
            EventPayload.ItemStored("i1", placeId = "cellar"),
            EventPayload.PlaceMerged("cellar", intoPlaceId = "basement"),
        )
        val source = db.placeQueries.selectById("cellar").executeAsOne().toPlace()
        val target = db.placeQueries.selectById("basement").executeAsOne().toPlace()
        assertEquals("basement", source.mergedInto)
        assertNotNull(source.deletedAt)
        assertEquals(listOf("cave", "sous-sol"), target.aliases)
        assertEquals("basement", db.placeQueries.selectById("shelf").executeAsOne().parent_id)
        assertEquals("basement", db.containerQueries.selectById("box").executeAsOne().place_id)
        assertEquals("basement", location("i1").placeId)
        assertEquals(3L, db.placeQueries.countActive().executeAsOne())
    }

    @Test
    fun should_rename_a_place_and_keep_its_aliases() = runTest {
        apply(
            EventPayload.PlaceCreated("p", "commode", kind = PlaceKind.FURNITURE),
            EventPayload.PlaceRenamed("p", "commode de la chambre", aliases = listOf("commode")),
        )
        val place = db.placeQueries.selectById("p").executeAsOne().toPlace()
        assertEquals("commode de la chambre", place.name)
        assertEquals(listOf("commode"), place.aliases)
    }

    @Test
    fun should_attach_and_detach_photos() = runTest {
        apply(
            EventPayload.ItemCreated("i1", "tente"),
            EventPayload.PhotoAttached(
                "ph1",
                PhotoOwnerType.ITEM,
                "i1",
                "ph1.jpg",
                "ph1_t.jpg",
                2048,
                1536,
                "00ff",
            ),
        )
        assertEquals(1, db.photoQueries.selectByOwner("ITEM", "i1").executeAsList().size)
        apply(EventPayload.PhotoDetached("ph1"))
        assertEquals(0, db.photoQueries.selectByOwner("ITEM", "i1").executeAsList().size)
        assertNotNull(db.photoQueries.selectById("ph1").executeAsOne().detached_at)
    }

    @Test
    fun should_track_reminders() = runTest {
        apply(
            EventPayload.ItemCreated("i1", "passeport"),
            EventPayload.ReminderSet("r1", "i1", fireAtEpochMillis = 1_800_000_000_000L, kind = ReminderKind.DATE),
        )
        assertEquals(1, db.reminderQueries.selectPending().executeAsList().size)
        apply(EventPayload.ReminderDone("r1"))
        assertEquals(0, db.reminderQueries.selectPending().executeAsList().size)
        assertTrue(db.reminderQueries.selectById("r1").executeAsOne().done)
    }

    @Test
    fun should_tolerate_events_about_unknown_records() = runTest {
        apply(
            EventPayload.ItemUpdated("ghost", name = "x"),
            EventPayload.ItemTaken("ghost"),
            EventPayload.ContainerMoved("nobox", placeId = "nowhere"),
            EventPayload.PlaceMerged("a", "b"),
        )
        assertEquals(4L, store.count())
        assertFalse(db.itemQueries.selectById("ghost").executeAsOneOrNull() != null)
        assertEquals(LocationStatus.TAKEN, location("ghost").status, "a status event creates the state row")
    }
}
