package app.trouvetout.core.domain.usecase

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.PlaceKind
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class CommandsTest {
    private val db = openTestDatabase()
    private val store = db.testEventStore()
    private val clock = TickingClock()
    private val ctx = CommandContext(store, MonotonicUlidGenerator(clock, Random(1)), clock, "device-T")
    private val items = ItemCommands(ctx)
    private val places = PlaceCommands(ctx)
    private val containers = ContainerCommands(ctx)
    private val attachments = AttachmentCommands(ctx)

    private fun CommandResult.events() = assertIs<CommandResult.Done>(this).events

    private fun CommandResult.reason() = assertIs<CommandResult.Rejected>(this).reason

    private suspend fun place(
        name: String,
        parentId: String? = null,
    ): String {
        val created = places.create(name, parentId, PlaceKind.ROOM, source = EventSource.TEXT).events().single()
        return (created.payload as EventPayload.PlaceCreated).placeId
    }

    private suspend fun item(
        name: String,
        location: LocationRef? = null,
    ): String {
        val created = items.create(ItemDraft(name), location, EventSource.TEXT).events().first()
        return (created.payload as EventPayload.ItemCreated).itemId
    }

    @Test
    fun should_create_an_item_in_a_place_with_two_events_carrying_source_and_raw_input() = runTest {
        val cave = place("cave")

        val result = items.create(
            ItemDraft(" passeport ", tags = listOf("papiers", " ", "papiers")),
            LocationRef.InPlace(cave),
            EventSource.VOICE,
            "passeport dans la cave",
        )

        val events = result.events()
        assertEquals(listOf("ItemCreated", "ItemStored"), events.map { it.type })
        assertTrue(
            events.all {
                it.deviceId == "device-T" &&
                    it.source == EventSource.VOICE &&
                    it.rawInput == "passeport dans la cave"
            },
        )
        val created = events.first().payload as EventPayload.ItemCreated
        assertEquals("passeport", created.name)
        assertEquals(listOf("papiers"), created.tags)
        assertEquals(cave, db.locationStateQueries.selectByItem(created.itemId).executeAsOne().place_id)
        assertTrue(events[0].id < events[1].id, "ids keep the journal order")
    }

    @Test
    fun should_reject_blank_names_and_unknown_locations() = runTest {
        assertEquals(Rejection.BLANK_NAME, items.create(ItemDraft("   "), null, EventSource.TEXT).reason())
        assertEquals(
            Rejection.UNKNOWN_PLACE,
            items.create(ItemDraft("x"), LocationRef.InPlace("nope"), EventSource.TEXT).reason(),
        )
        assertEquals(
            Rejection.UNKNOWN_CONTAINER,
            items.create(ItemDraft("x"), LocationRef.InContainer("nope"), EventSource.TEXT).reason(),
        )
        assertEquals(0L, store.count())
    }

    @Test
    fun should_run_the_take_put_back_lend_cycle_U5_U6() = runTest {
        val garage = place("garage")
        val drill = item("perceuse", LocationRef.InPlace(garage))

        items.take(drill, EventSource.VOICE, "je prends la perceuse").events()
        assertEquals(LocationStatus.TAKEN.name, db.locationStateQueries.selectByItem(drill).executeAsOne().state)
        items.putBack(drill, EventSource.VOICE, "perceuse remise au garage").events()
        assertEquals(LocationStatus.STORED.name, db.locationStateQueries.selectByItem(drill).executeAsOne().state)

        items.lend(drill, "Marc", EventSource.VOICE).events()
        val lent = db.locationStateQueries.selectByItem(drill).executeAsOne()
        assertEquals(LocationStatus.LENT.name, lent.state)
        assertEquals("Marc", lent.lent_to)
        assertEquals(Rejection.BLANK_NAME, items.lend(drill, " ", EventSource.TEXT).reason())

        items.putBack(drill, EventSource.TEXT).events()
        assertEquals(garage, db.locationStateQueries.selectByItem(drill).executeAsOne().place_id)
        assertEquals(1L, db.itemQueries.countActive().executeAsOne(), "no duplicate item")
    }

    @Test
    fun should_refuse_operations_on_unknown_or_deleted_items() = runTest {
        assertEquals(Rejection.UNKNOWN_ITEM, items.take("ghost", EventSource.TEXT).reason())
        val skis = item("skis")
        items.delete(skis, EventSource.TEXT).events()
        assertEquals(Rejection.ITEM_DELETED, items.take(skis, EventSource.TEXT).reason())
        assertEquals(Rejection.ITEM_DELETED, items.update(skis, ItemPatch(name = "x"), EventSource.TEXT).reason())
        items.restore(skis, EventSource.TEXT).events()
        assertEquals(Rejection.ITEM_NOT_DELETED, items.restore(skis, EventSource.TEXT).reason())
        items.take(skis, EventSource.TEXT).events()
    }

    @Test
    fun should_patch_items_and_refuse_blank_renames() = runTest {
        val cable = item("câble")
        items.update(
            cable,
            ItemPatch(name = "câble USB-C", pinned = true, quantity = 2.0, unit = "m"),
            EventSource.TEXT,
        ).events()
        val row = db.itemQueries.selectById(cable).executeAsOne()
        assertEquals("câble USB-C", row.name)
        assertEquals(2.0, row.quantity)
        assertTrue(row.pinned)
        assertEquals(Rejection.BLANK_NAME, items.update(cable, ItemPatch(name = " "), EventSource.TEXT).reason())
    }

    @Test
    fun should_create_nested_places_and_refuse_deleting_a_non_empty_one() = runTest {
        val house = place("maison")
        val cellar = place("cave", parentId = house)
        assertEquals(
            Rejection.UNKNOWN_PLACE,
            places.create("x", parentId = "nope", source = EventSource.TEXT).reason(),
        )
        assertEquals(Rejection.PLACE_NOT_EMPTY, places.delete(house, EventSource.TEXT).reason())

        val skis = item("skis", LocationRef.InPlace(cellar))
        assertEquals(Rejection.PLACE_NOT_EMPTY, places.delete(cellar, EventSource.TEXT).reason())
        items.move(skis, LocationRef.InPlace(house), EventSource.TEXT).events()
        places.delete(cellar, EventSource.TEXT).events()
        assertEquals(1L, db.placeQueries.countActive().executeAsOne())
    }

    @Test
    fun should_merge_places_but_never_into_a_descendant() = runTest {
        val house = place("maison")
        val cellar = place("cave", parentId = house)
        val shelf = place("étagère", parentId = cellar)
        assertEquals(Rejection.SAME_PLACE, places.merge(cellar, cellar, EventSource.TEXT).reason())
        assertEquals(Rejection.PLACE_CYCLE, places.merge(house, shelf, EventSource.TEXT).reason())
        assertEquals(Rejection.UNKNOWN_PLACE, places.merge(cellar, "nope", EventSource.TEXT).reason())

        places.merge(cellar, house, EventSource.TEXT).events()
        assertEquals(house, db.placeQueries.selectById(shelf).executeAsOne().parent_id)
        assertEquals(house, db.placeQueries.selectById(cellar).executeAsOne().merged_into)
        assertEquals(
            Rejection.UNKNOWN_PLACE,
            places.rename(cellar, "x", emptyList(), EventSource.TEXT).reason(),
            "a merged place is retired",
        )
    }

    @Test
    fun should_manage_containers_and_refuse_deleting_a_full_one() = runTest {
        val cave = place("cave")
        val box = (
            containers.create(
                "Carton 7",
                cave,
                number = 7,
                source = EventSource.TEXT,
            ).events().single().payload as EventPayload.ContainerCreated
        ).containerId
        assertEquals(Rejection.UNKNOWN_PLACE, containers.create("x", "nope", source = EventSource.TEXT).reason())
        val lights = item("guirlandes", LocationRef.InContainer(box))
        assertEquals(Rejection.CONTAINER_NOT_EMPTY, containers.delete(box, EventSource.TEXT).reason())

        val garage = place("garage")
        containers.move(box, garage, EventSource.TEXT).events()
        assertEquals(garage, db.containerQueries.selectById(box).executeAsOne().place_id)
        assertEquals(box, db.locationStateQueries.selectByItem(lights).executeAsOne().container_id)

        items.move(lights, LocationRef.InPlace(garage), EventSource.TEXT).events()
        containers.delete(box, EventSource.TEXT).events()
        assertEquals(Rejection.UNKNOWN_CONTAINER, containers.move(box, garage, EventSource.TEXT).reason())
    }

    @Test
    fun should_attach_photos_and_reminders_to_known_records_only() = runTest {
        val tent = item("tente")
        val file = PhotoFile("a.jpg", "a_t.jpg", 100, 80, "00")
        assertEquals(
            Rejection.UNKNOWN_ITEM,
            attachments.attachPhoto(PhotoOwnerType.ITEM, "nope", file, EventSource.PHOTO).reason(),
        )
        val photoId = (
            attachments.attachPhoto(
                PhotoOwnerType.ITEM,
                tent,
                file,
                EventSource.PHOTO,
            ).events().single().payload as EventPayload.PhotoAttached
        ).photoId
        assertEquals(1, db.photoQueries.selectByOwner("ITEM", tent).executeAsList().size)
        attachments.detachPhoto(photoId, EventSource.TEXT).events()
        assertEquals(Rejection.UNKNOWN_PHOTO, attachments.detachPhoto("nope", EventSource.TEXT).reason())

        val reminderId =
            (
                attachments.setReminder(
                    tent,
                    Instant.fromEpochMilliseconds(1_800_000_000_000L),
                    source = EventSource.VOICE,
                ).events().single().payload
                    as EventPayload.ReminderSet
            ).reminderId
        assertEquals(1, db.reminderQueries.selectPending().executeAsList().size)
        attachments.completeReminder(reminderId, EventSource.SYSTEM).events()
        assertEquals(0, db.reminderQueries.selectPending().executeAsList().size)
        assertEquals(Rejection.UNKNOWN_REMINDER, attachments.completeReminder("nope", EventSource.SYSTEM).reason())
    }

    @Test
    fun should_clear_a_description_patched_blank_and_skip_empty_patches() = runTest {
        val cable = item("câble")
        items.update(cable, ItemPatch(description = "blanc"), EventSource.TEXT).events()
        assertEquals("blanc", db.itemQueries.selectById(cable).executeAsOne().description)

        items.update(cable, ItemPatch(description = "   "), EventSource.TEXT).events()
        assertEquals(null, db.itemQueries.selectById(cable).executeAsOne().description)

        val before = store.count()
        assertEquals(emptyList(), items.update(cable, ItemPatch(), EventSource.TEXT).events())
        assertEquals(before, store.count(), "an empty patch appends nothing")
    }

    @Test
    fun should_refuse_non_finite_or_negative_quantities() = runTest {
        assertEquals(
            Rejection.INVALID_QUANTITY,
            items.create(ItemDraft("vis", quantity = Double.NaN), null, EventSource.TEXT).reason(),
        )
        assertEquals(
            Rejection.INVALID_QUANTITY,
            items.create(ItemDraft("vis", quantity = -1.0), null, EventSource.TEXT).reason(),
        )
        val screws = item("vis")
        assertEquals(
            Rejection.INVALID_QUANTITY,
            items.update(screws, ItemPatch(quantity = Double.POSITIVE_INFINITY), EventSource.TEXT).reason(),
        )
        items.update(screws, ItemPatch(quantity = 12.0, unit = "pcs"), EventSource.TEXT).events()
        assertEquals(12.0, db.itemQueries.selectById(screws).executeAsOne().quantity)
    }
}
