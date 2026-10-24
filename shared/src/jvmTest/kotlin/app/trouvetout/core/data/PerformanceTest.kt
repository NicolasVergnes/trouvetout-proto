package app.trouvetout.core.data

import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.repository.ItemRepository
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.platform.CoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

/** N7: 10 000 items and 100 000+ events must stay usable (JVM, in-memory SQLite). Budgets are deliberately loose. */
class PerformanceTest {
    @Test
    fun should_absorb_10k_items_and_100k_events() = runTest(timeout = 10.minutes) {
        val db = openTestDatabase()
        val store = db.testEventStore()
        val fx = EventFixtures(clock = TickingClock(stepMillis = 1))
        val places = List(60) { "p$it" }
        val items = List(10_000) { "i$it" }
        val events = ArrayList<Event>(110_000)
        places.forEach { events += fx.event(EventPayload.PlaceCreated(it, "place $it", kind = PlaceKind.ROOT)) }
        items.forEachIndexed { index, id ->
            events += fx.event(EventPayload.ItemCreated(id, "item $index", tags = listOf("t${index % 20}")))
            events += fx.event(EventPayload.ItemStored(id, placeId = places[index % places.size]))
        }
        val random = Random(7)
        while (events.size < 110_000) {
            val id = items[random.nextInt(items.size)]
            val payload: EventPayload =
                when (random.nextInt(4)) {
                    0 -> EventPayload.ItemTaken(id)
                    1 -> EventPayload.ItemReturned(id)
                    2 -> EventPayload.ItemMovedTo(id, placeId = places[random.nextInt(places.size)])
                    else -> EventPayload.ItemUpdated(id, pinned = random.nextBoolean())
                }
            events += fx.event(payload)
        }

        val appendMs = measureTime { events.chunked(1_000).forEach { store.append(it) } }.inWholeMilliseconds
        val rebuildMs = measureTime { store.rebuildProjection() }.inWholeMilliseconds
        val byPlaceMs =
            measureTime {
                repeat(places.size) { db.locationStateQueries.itemsInPlace(places[it]).executeAsList() }
            }.inWholeMilliseconds / places.size.toDouble()
        val dispatchers =
            CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
        val viewsMs = measureTime { ItemRepository(db, dispatchers).observeActive().first() }.inWholeMilliseconds
        println("N7 append=${appendMs}ms rebuild=${rebuildMs}ms listByPlace=${byPlaceMs}ms views(10k)=${viewsMs}ms")

        assertEquals(events.size.toLong(), store.count())
        assertEquals(10_000L, db.itemQueries.countActive().executeAsOne())
        assertTrue(appendMs < 120_000, "append took ${appendMs}ms")
        assertTrue(rebuildMs < 60_000, "rebuild took ${rebuildMs}ms")
        assertTrue(byPlaceMs < 50, "list by place took ${byPlaceMs}ms")
        assertTrue(viewsMs < 5_000, "10k views took ${viewsMs}ms")
    }
}
