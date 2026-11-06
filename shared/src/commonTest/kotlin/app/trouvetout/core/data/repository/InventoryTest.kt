package app.trouvetout.core.data.repository

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.usecase.CommandContext
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ContainerCommands
import app.trouvetout.core.domain.usecase.ItemCommands
import app.trouvetout.core.domain.usecase.ItemDraft
import app.trouvetout.core.domain.usecase.ItemPatch
import app.trouvetout.core.domain.usecase.LocationRef
import app.trouvetout.core.domain.usecase.PlaceCommands
import app.trouvetout.core.platform.CoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** C16: the printable inventory of a place or a container. */
class InventoryTest {
    private val db = openTestDatabase()
    private val store = db.testEventStore()
    private val clock = TickingClock()
    private val ctx = CommandContext(store, MonotonicUlidGenerator(clock, Random(5)), clock, "device-I")
    private val places = PlaceCommands(ctx)
    private val containers = ContainerCommands(ctx)
    private val items = ItemCommands(ctx)
    private val dispatchers =
        CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val browse = BrowseRepository(db, dispatchers, ItemRepository(db, dispatchers), store)

    private fun CommandResult.id(): String = when (val p = assertIs<CommandResult.Done>(this).events.first().payload) {
        is EventPayload.PlaceCreated -> p.placeId
        is EventPayload.ContainerCreated -> p.containerId
        is EventPayload.ItemCreated -> p.itemId
        else -> error("unexpected ${p.typeName}")
    }

    private suspend fun place(
        name: String,
        parentId: String? = null,
    ): String {
        clock.tick()
        val kind = if (parentId == null) PlaceKind.ROOT else PlaceKind.SPOT
        return places.create(name, parentId, kind, source = EventSource.TEXT).id()
    }

    private suspend fun item(
        name: String,
        location: LocationRef,
        quantity: Double? = null,
        unit: String? = null,
    ): String {
        clock.tick()
        return items.create(ItemDraft(name, quantity = quantity, unit = unit), location, EventSource.TEXT).id()
    }

    private fun Inventory.text() = lines.map { "  ".repeat(it.depth) + it.label }

    @Test
    fun a_place_prints_its_whole_subtree_sorted_with_quantities() = runTest {
        val cave = place("Cave")
        val shelf = place("Étagère du fond", cave)
        val left = place("Armoire", cave)
        clock.tick()
        val box = containers.create("Carton 7", shelf, 7L, EventSource.TEXT).id()
        item("vis M4", LocationRef.InPlace(shelf), 12.0)
        item("décos de Noël", LocationRef.InContainer(box))
        item("câble", LocationRef.InContainer(box), 3.0, "m")
        item("valise", LocationRef.InPlace(cave))
        val drill = item("perceuse", LocationRef.InPlace(left))
        clock.tick()
        items.lend(drill, "Marc", EventSource.TEXT)

        val printed = browse.placeInventory(cave, includeSensitive = false)!!
        assertEquals("Cave", printed.title)
        assertEquals(emptyList(), printed.path)
        assertEquals(
            listOf(
                "valise",
                "Armoire",
                "  perceuse",
                "Étagère du fond",
                "  vis M4",
                "  Carton 7",
                "    câble",
                "    décos de Noël",
            ),
            printed.text(),
        )
        val cable = printed.lines.first { it.label == "câble" }
        assertEquals(3.0 to "m", cable.quantity to cable.unit)
        val lent = printed.lines.first { it.label == "perceuse" }
        assertEquals(LocationStatus.LENT, lent.status)
        assertEquals("Marc", lent.lentTo)
        assertEquals(5, printed.itemCount)

        val inBox = browse.containerInventory(box, includeSensitive = false)!!
        assertEquals("Carton 7", inBox.title)
        assertEquals(listOf("Cave", "Étagère du fond"), inBox.path)
        assertEquals(listOf("câble", "décos de Noël"), inBox.text())
    }

    @Test
    fun sensitive_items_are_printed_only_when_shown_and_archived_ones_never_H4() = runTest {
        val office = place("Bureau")
        val drawer = place("Tiroir", office)
        val passport = item("passeport", LocationRef.InPlace(drawer))
        item("agrafeuse", LocationRef.InPlace(drawer))
        val old = item("vieux téléphone", LocationRef.InPlace(drawer))
        clock.tick()
        items.update(passport, ItemPatch(sensitive = true), EventSource.TEXT)
        clock.tick()
        items.setArchived(old, true, EventSource.TEXT)

        val masked = browse.placeInventory(office, includeSensitive = false)!!
        assertEquals(listOf("Tiroir", "  agrafeuse"), masked.text())
        assertEquals(1, masked.hiddenCount)
        val revealed = browse.placeInventory(office, includeSensitive = true)!!
        assertEquals(listOf("Tiroir", "  agrafeuse", "  passeport"), revealed.text())
        assertEquals(0, revealed.hiddenCount)
        assertEquals(listOf("Bureau"), browse.placeInventory(drawer, true)!!.path)
    }

    @Test
    fun a_merged_place_prints_under_its_target_and_a_gone_one_prints_nothing() = runTest {
        val home = place("Maison")
        val garage = place("Garage", home)
        val workshop = place("Atelier", home)
        item("perceuse", LocationRef.InPlace(workshop))
        clock.tick()
        places.merge(workshop, garage, EventSource.TEXT)
        assertEquals(listOf("Garage", "  perceuse"), browse.placeInventory(home, false)!!.text())
        assertNull(browse.placeInventory(workshop, false))
        assertNull(browse.containerInventory("nope", false))
    }
}
