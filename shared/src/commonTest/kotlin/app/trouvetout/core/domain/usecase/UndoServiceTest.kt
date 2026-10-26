package app.trouvetout.core.domain.usecase

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.PlaceKind
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoServiceTest {
    private val db = openTestDatabase()
    private val store = db.testEventStore()
    private val clock = TickingClock()
    private val ctx = CommandContext(store, MonotonicUlidGenerator(clock, Random(5)), clock, "device-U")
    private val items = ItemCommands(ctx)
    private val places = PlaceCommands(ctx)
    private val containers = ContainerCommands(ctx)
    private val attachments = AttachmentCommands(ctx)
    private val undo = UndoService(store, ctx, "device-U")

    private fun CommandResult.events() = assertIs<CommandResult.Done>(this).events

    private suspend fun place(name: String): String {
        clock.tick()
        return (
            places.create(
                name,
                kind = PlaceKind.ROOT,
                source = EventSource.TEXT,
            ).events().single().payload as EventPayload.PlaceCreated
        ).placeId
    }

    private suspend fun item(
        name: String,
        location: LocationRef? = null,
    ): String {
        clock.tick()
        return (
            items.create(
                ItemDraft(name),
                location,
                EventSource.TEXT,
            ).events().first().payload as EventPayload.ItemCreated
        ).itemId
    }

    /** Ticks the clock first: two taps never share a millisecond. */
    private suspend fun undoNow(): CommandResult {
        clock.tick()
        return undo.undoLast(EventSource.TEXT)
    }

    private fun location(itemId: String) = db.locationStateQueries.selectByItem(itemId).executeAsOne()

    @Test
    fun should_undo_a_whole_capture_create_plus_store() = runTest {
        val cave = place("cave")
        val skis = item("skis", LocationRef.InPlace(cave))
        assertEquals(2, undo.peek().size, "the capture appended two events at once")

        val result = undoNow().events()

        assertEquals(listOf("ItemStored", "ItemDeleted"), result.map { it.type })
        assertEquals(0L, db.itemQueries.countActive().executeAsOne())
        assertNull(location(skis).place_id, "location reverted to unknown before the delete")
    }

    @Test
    fun should_undo_a_move_back_to_the_previous_location() = runTest {
        val cave = place("cave")
        val garage = place("garage")
        val drill = item("perceuse", LocationRef.InPlace(cave))
        clock.tick()
        items.move(drill, LocationRef.InPlace(garage), EventSource.VOICE).events()
        assertEquals(garage, location(drill).place_id)

        undoNow().events()

        assertEquals(cave, location(drill).place_id)
    }

    @Test
    fun should_undo_take_lend_and_return() = runTest {
        val cave = place("cave")
        val tent = item("tente", LocationRef.InPlace(cave))
        clock.tick()
        items.lend(tent, "Marc", EventSource.TEXT).events()
        clock.tick()
        items.putBack(tent, EventSource.TEXT).events()
        assertEquals("STORED", location(tent).state)

        undoNow().events()
        assertEquals("LENT", location(tent).state)
        assertEquals("Marc", location(tent).lent_to)

        clock.tick()
        undoNow().events() // undoes the re-lend → returned
        assertEquals("STORED", location(tent).state)
    }

    @Test
    fun should_undo_an_update_by_restoring_the_previous_fields() = runTest {
        val cable = item("câble")
        clock.tick()
        items.update(cable, ItemPatch(description = "blanc", quantity = 2.0, unit = "m"), EventSource.TEXT).events()
        clock.tick()
        items.update(
            cable,
            ItemPatch(name = "câble USB-C", clearDescription = true, pinned = true),
            EventSource.TEXT,
        ).events()

        undoNow().events()

        val row = db.itemQueries.selectById(cable).executeAsOne()
        assertEquals("câble", row.name)
        assertEquals("blanc", row.description)
        assertEquals(2.0, row.quantity)
        assertEquals(false, row.pinned)
    }

    @Test
    fun should_undo_delete_restore_archive_rename_container_move_and_photo() = runTest {
        val cave = place("cave")
        val garage = place("garage")
        val box = (
            containers.create(
                "Carton",
                cave,
                source = EventSource.TEXT,
            ).events().single().payload as EventPayload.ContainerCreated
        ).containerId
        val skis = item("skis", LocationRef.InContainer(box))

        clock.tick()
        items.delete(skis, EventSource.TEXT).events()
        undoNow().events()
        assertNull(db.itemQueries.selectById(skis).executeAsOne().deleted_at)

        clock.tick()
        items.setArchived(skis, true, EventSource.TEXT).events()
        undoNow().events()
        assertEquals(false, db.itemQueries.selectById(skis).executeAsOne().archived)

        clock.tick()
        places.rename(cave, "la cave", listOf("cave"), EventSource.TEXT).events()
        undoNow().events()
        assertEquals("cave", db.placeQueries.selectById(cave).executeAsOne().name)

        clock.tick()
        containers.move(box, garage, EventSource.TEXT).events()
        undoNow().events()
        assertEquals(cave, db.containerQueries.selectById(box).executeAsOne().place_id)

        clock.tick()
        val photoId = (
            attachments.attachPhoto(
                PhotoOwnerType.ITEM,
                skis,
                PhotoFile("a.jpg", "a_t.jpg", 10, 10, "00"),
                EventSource.PHOTO,
            ).events().single().payload as EventPayload.PhotoAttached
        ).photoId
        clock.tick()
        attachments.detachPhoto(photoId, EventSource.TEXT).events()
        undoNow().events()
        assertNull(db.photoQueries.selectById(photoId).executeAsOne().detached_at, "detach undone by re-attaching")
    }

    @Test
    fun should_refuse_when_nothing_or_an_irreversible_event_is_last() = runTest {
        assertEquals(
            Rejection.NOTHING_TO_UNDO,
            assertIs<CommandResult.Rejected>(undoNow()).reason,
        )
        val a = place("a")
        val b = place("b")
        clock.tick()
        places.merge(a, b, EventSource.TEXT).events()
        assertEquals(
            Rejection.NOT_UNDOABLE,
            assertIs<CommandResult.Rejected>(undoNow()).reason,
        )
        assertTrue(undo.peek().isNotEmpty())
    }

    @Test
    fun should_never_undo_imported_or_other_devices_events() = runTest {
        val fx = app.trouvetout.core.data.EventFixtures(
            deviceId = "device-OTHER",
            clock = TickingClock(
                clock.millis + 10_000,
            ),
        )
        store.append(listOf(fx.event(EventPayload.ItemCreated("x", "x"))))
        assertTrue(undo.peek().isEmpty(), "the other device's event is not ours to undo")
        val imported = app.trouvetout.core.data.EventFixtures(
            deviceId = "device-U",
            clock = TickingClock(
                clock.millis + 20_000,
            ),
        )
        store.append(listOf(imported.event(EventPayload.ItemCreated("y", "y"), source = EventSource.IMPORT)))
        assertTrue(undo.peek().isEmpty(), "imported events are never undone")
    }

    @Test
    fun should_undo_a_put_back_to_the_taken_state() = runTest {
        val garage = place("garage")
        val drill = item("perceuse", LocationRef.InPlace(garage))
        clock.tick()
        items.take(drill, EventSource.TEXT).events()
        clock.tick()
        items.store(drill, LocationRef.InPlace(garage), EventSource.TEXT).events()
        assertEquals("STORED", location(drill).state)

        undoNow().events()

        assertEquals("TAKEN", location(drill).state, "the drill is out again, as before the put-back (U5)")
        assertEquals(garage, location(drill).place_id)
    }

    @Test
    fun should_undo_a_given_set_of_events_written_at_different_instants() = runTest {
        val cave = place("cave")
        val skis = item("skis", LocationRef.InPlace(cave))
        val everything =
            store.historyOf(
                app.trouvetout.core.domain.model.EventSubject(
                    app.trouvetout.core.domain.model.EventSubject.SubjectType.ITEM,
                    skis,
                ),
            ) +
                store.historyOf(
                    app.trouvetout.core.domain.model.EventSubject(
                        app.trouvetout.core.domain.model.EventSubject.SubjectType.PLACE,
                        cave,
                    ),
                )
        clock.tick()

        val result = undo.undo(everything.distinctBy { it.id }, EventSource.TEXT).events()

        assertEquals(setOf("ItemStored", "ItemDeleted", "PlaceDeleted"), result.map { it.type }.toSet())
        assertEquals(0L, db.itemQueries.countActive().executeAsOne())
        assertEquals(0L, db.placeQueries.countActive().executeAsOne())
    }
}
