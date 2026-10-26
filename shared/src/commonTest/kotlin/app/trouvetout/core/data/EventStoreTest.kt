package app.trouvetout.core.data

import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.EventSubject
import app.trouvetout.core.domain.model.PlaceKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventStoreTest {
    private val db = openTestDatabase()
    private val store = db.testEventStore()
    private val fx = EventFixtures()

    @Test
    fun should_append_events_and_keep_them_in_journal_order() = runTest {
        val e1 = fx.event(
            EventPayload.ItemCreated("i1", "clés"),
            source = EventSource.VOICE,
            rawInput = "clés @ entrée",
        )
        val e2 = fx.event(EventPayload.ItemTaken("i1"))

        val result = store.append(listOf(e1, e2))

        assertEquals(AppendResult(appended = 2, skipped = 0, rebuilt = false), result)
        assertEquals(listOf(e1, e2), store.all())
        assertEquals("clés @ entrée", store.all().first().rawInput)
    }

    @Test
    fun should_skip_events_already_known_by_id() = runTest {
        val e1 = fx.event(EventPayload.ItemCreated("i1", "clés"))
        store.append(listOf(e1))

        val again = store.append(listOf(e1, fx.event(EventPayload.ItemTaken("i1"))))

        assertEquals(1, again.appended)
        assertEquals(1, again.skipped)
        assertEquals(2L, store.count())
    }

    @Test
    fun should_rebuild_the_projection_when_an_older_event_arrives() = runTest {
        val deviceA = EventFixtures("device-A", TickingClock(1_700_000_000_000L))
        val deviceB = EventFixtures("device-B", TickingClock(1_700_000_002_500L), seed = 2)
        val created = deviceA.event(EventPayload.PlaceCreated("p1", "cave", kind = PlaceKind.ROOT))
        val createdItem = deviceA.event(EventPayload.ItemCreated("i1", "skis"))
        // Device B stored the item at t+3s, device A moved it at t+9s; B's event reaches us last.
        val storedByB = deviceB.event(EventPayload.ItemStored("i1", placeId = "p1")).let { it.copy(at = it.at) }
        repeat(5) { deviceA.clock.tick() }
        val movedByA = deviceA.event(EventPayload.PlaceCreated("p2", "garage", kind = PlaceKind.ROOT))
        val movedItemByA = deviceA.event(EventPayload.ItemMovedTo("i1", placeId = "p2"))
        store.append(listOf(created, createdItem, movedByA, movedItemByA))
        assertEquals("p2", db.locationStateQueries.selectByItem("i1").executeAsOne().place_id)

        val late = store.append(listOf(storedByB))

        assertTrue(late.rebuilt)
        assertEquals(
            "p2",
            db.locationStateQueries.selectByItem("i1").executeAsOne().place_id,
            "the latest move wins",
        )
        assertEquals(
            listOf(created.id, createdItem.id, storedByB.id, movedByA.id, movedItemByA.id),
            store.all().map {
                it.id
            },
        )
    }

    @Test
    fun should_not_rebuild_for_in_order_appends() = runTest {
        store.append(listOf(fx.event(EventPayload.ItemCreated("i1", "a"))))
        val next = store.append(listOf(fx.event(EventPayload.ItemTaken("i1"))))
        assertFalse(next.rebuilt)
    }

    @Test
    fun should_index_history_by_subject() = runTest {
        store.append(
            listOf(
                fx.event(EventPayload.PlaceCreated("p1", "cave", kind = PlaceKind.ROOT)),
                fx.event(EventPayload.ItemCreated("i1", "skis")),
                fx.event(EventPayload.ItemCreated("i2", "luge")),
                fx.event(EventPayload.ItemStored("i1", placeId = "p1")),
                fx.event(EventPayload.ItemTaken("i2")),
            ),
        )
        val itemHistory = store.historyOf(EventSubject(EventSubject.SubjectType.ITEM, "i1"))
        assertEquals(listOf("ItemCreated", "ItemStored"), itemHistory.map { it.type })
        val placeHistory = store.historyOf(EventSubject(EventSubject.SubjectType.PLACE, "p1"))
        assertEquals(listOf("PlaceCreated", "ItemStored"), placeHistory.map { it.type })
    }

    @Test
    fun should_give_the_same_projection_after_a_rebuild() = runTest {
        store.append(
            listOf(
                fx.event(EventPayload.PlaceCreated("p1", "cave", kind = PlaceKind.ROOT)),
                fx.event(EventPayload.ItemCreated("i1", "skis", tags = listOf("hiver"))),
                fx.event(EventPayload.ItemStored("i1", placeId = "p1")),
                fx.event(EventPayload.ItemLent("i1", to = "Léa")),
            ),
        )
        val incremental = db.snapshot()
        store.rebuildProjection()
        assertEquals(incremental, db.snapshot())
    }

    @Test
    fun should_rebuild_when_a_same_millisecond_event_sorts_before_the_head() = runTest {
        val clockA = TickingClock(1_700_000_000_000L)
        val deviceA = EventFixtures("device-A", clockA, seed = 1)
        val deviceZ = EventFixtures("device-Z", TickingClock(1_700_000_000_000L), seed = 2)
        val created = deviceZ.event(EventPayload.ItemCreated("i1", "skis"))
        val place1 = deviceZ.event(EventPayload.PlaceCreated("p1", "cave", kind = PlaceKind.ROOT))
        val place2 = deviceZ.event(EventPayload.PlaceCreated("p2", "garage", kind = PlaceKind.ROOT))
        val movedByZ = deviceZ.event(EventPayload.ItemMovedTo("i1", placeId = "p2"))
        // Same millisecond as movedByZ but a smaller device id: canonically BEFORE it.
        repeat(3) { clockA.tick() }
        val storedByA = deviceA.event(EventPayload.ItemStored("i1", placeId = "p1"))
        assertEquals(movedByZ.at, storedByA.at)
        store.append(listOf(created, place1, place2, movedByZ))

        val late = store.append(listOf(storedByA))

        assertTrue(late.rebuilt, "same instant, earlier device id: must be replayed in canonical order")
        assertEquals("p2", db.locationStateQueries.selectByItem("i1").executeAsOne().place_id)
        assertEquals(listOf(created.id, place1.id, place2.id, storedByA.id, movedByZ.id), store.all().map { it.id })
    }

    @Test
    fun should_stream_the_journal_page_by_page_in_order() = runTest {
        val events = List(45) { fx.event(EventPayload.ItemCreated("i$it", "n$it")) }
        store.append(events)
        val seen = ArrayList<String>()
        store.forEachInOrder(pageSize = 10) { seen += it.id }
        assertEquals(events.map { it.id }, seen)
    }
}
