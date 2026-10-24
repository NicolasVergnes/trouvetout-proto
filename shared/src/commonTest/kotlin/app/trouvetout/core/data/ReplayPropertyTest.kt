package app.trouvetout.core.data

import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.PlaceKind
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property tests of the journal/projection contract (lot 1 criteria):
 * - `replay(events) == state` for 50 random scenarios;
 * - every item always has exactly one location state;
 * - the union of two devices' journals converges whatever the arrival order (prepares F5).
 */
class ReplayPropertyTest {
    private class Scenario(
        seed: Int,
    ) {
        val random = Random(seed)
        val fx = EventFixtures(deviceId = "device-${seed % 3}", seed = seed)
        val places = mutableListOf<String>()
        val containers = mutableListOf<String>()
        val items = mutableListOf<String>()
        private var counter = 0

        private fun newId(prefix: String) = "$prefix${counter++}"

        @Suppress("CyclomaticComplexMethod", "LongMethod") // random scenario generator: one branch per event kind
        fun nextEvent(): Event {
            val payload: EventPayload =
                when (random.nextInt(14)) {
                    0, 1 -> EventPayload.PlaceCreated(
                        newId("p").also(places::add),
                        "place",
                        parentId = places.randomOrNull(random),
                        kind = PlaceKind.entries.random(random),
                    ).also {
                    }
                    2 -> if (places.isEmpty()) {
                        createItem()
                    } else {
                        EventPayload.ContainerCreated(
                            newId("c").also(containers::add),
                            "box",
                            placeId = places.random(random),
                        )
                    }
                    3, 4 -> createItem()
                    5, 6 -> if (items.isEmpty()) createItem() else locate(EventPayload::ItemStored)
                    7 -> if (items.isEmpty()) createItem() else locate(EventPayload::ItemMovedTo)
                    8 -> if (items.isEmpty()) createItem() else EventPayload.ItemTaken(items.random(random))
                    9 -> if (items.isEmpty()) createItem() else EventPayload.ItemReturned(items.random(random))
                    10 -> if (items.isEmpty()) {
                        createItem()
                    } else {
                        EventPayload.ItemLent(
                            items.random(random),
                            to = "P${random.nextInt(3)}",
                        )
                    }
                    11 -> if (containers.isEmpty() ||
                        places.isEmpty()
                    ) {
                        createItem()
                    } else {
                        EventPayload.ContainerMoved(containers.random(random), places.random(random))
                    }
                    12 -> if (items.isEmpty()) {
                        createItem()
                    } else {
                        EventPayload.ItemUpdated(
                            items.random(random),
                            name = "n${random.nextInt(9)}",
                            pinned = random.nextBoolean(),
                        )
                    }
                    else ->
                        when {
                            items.isEmpty() -> createItem()
                            random.nextBoolean() -> EventPayload.ItemDeleted(items.random(random))
                            places.size >= 2 -> EventPayload.PlaceMerged(places.random(random), places.random(random))
                            else -> EventPayload.PhotoAttached(
                                newId("ph"),
                                PhotoOwnerType.ITEM,
                                items.random(random),
                                "f.jpg",
                                "t.jpg",
                                10,
                                10,
                                "00",
                            )
                        }
                }
            return fx.event(payload)
        }

        private fun createItem(): EventPayload =
            EventPayload.ItemCreated(newId("i").also(items::add), "item", tags = listOf("t${random.nextInt(4)}"))

        private fun locate(make: (String, String?, String?) -> EventPayload): EventPayload {
            val item = items.random(random)
            return if (containers.isNotEmpty() &&
                random.nextBoolean()
            ) {
                make(item, null, containers.random(random))
            } else {
                make(item, places.randomOrNull(random), null)
            }
        }
    }

    @Test
    fun should_equal_a_replay_from_scratch_for_50_random_scenarios() = runTest {
        repeat(50) { seed ->
            val scenario = Scenario(seed)
            val db = openTestDatabase()
            val store = db.testEventStore()
            repeat(40 + seed) { store.append(listOf(scenario.nextEvent())) }

            val incremental = db.snapshot()
            store.rebuildProjection()

            assertEquals(incremental, db.snapshot(), "scenario $seed")
            assertTrue(
                incremental.locations.map {
                    it.itemId
                }.containsAll(incremental.items.map { it.id }),
                "every item has a state ($seed)",
            )
            assertEquals(
                incremental.locations.size,
                incremental.locations.map {
                    it.itemId
                }.toSet().size,
                "one state per item ($seed)",
            )
        }
    }

    @Test
    fun should_converge_when_two_journals_are_merged_in_any_order() = runTest {
        repeat(10) { seed ->
            val a = Scenario(seed * 2)
            val b = Scenario(seed * 2 + 1)
            val eventsA = List(30) { a.nextEvent() }
            val eventsB = List(30) { b.nextEvent() }

            val dbAB = openTestDatabase()
            dbAB.testEventStore().apply {
                append(eventsA)
                append(eventsB)
            }
            val dbBA = openTestDatabase()
            dbBA.testEventStore().apply {
                append(eventsB)
                append(eventsA)
            }
            val dbMixed = openTestDatabase()
            dbMixed.testEventStore().append((eventsA + eventsB).shuffled(Random(seed)))

            assertEquals(dbAB.snapshot(), dbBA.snapshot(), "A∪B vs B∪A ($seed)")
            assertEquals(dbAB.snapshot(), dbMixed.snapshot(), "A∪B vs shuffled ($seed)")
        }
    }
}
