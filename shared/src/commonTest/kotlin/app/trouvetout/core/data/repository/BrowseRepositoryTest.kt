package app.trouvetout.core.data.repository

import app.trouvetout.core.capture.Lang
import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.photo.FakeImageCodec
import app.trouvetout.core.data.photo.InMemoryFileStore
import app.trouvetout.core.data.photo.PhotoStore
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.places.PlaceTemplates
import app.trouvetout.core.domain.usecase.AttachmentCommands
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowseRepositoryTest {
    private val db = openTestDatabase()
    private val store = db.testEventStore()
    private val clock = TickingClock()
    private val ctx = CommandContext(store, MonotonicUlidGenerator(clock, Random(11)), clock, "device-B")
    private val places = PlaceCommands(ctx)
    private val containers = ContainerCommands(ctx)
    private val items = ItemCommands(ctx)
    private val attachments = AttachmentCommands(ctx)
    private val dispatchers =
        CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val itemRepository = ItemRepository(db, dispatchers)
    private val browse = BrowseRepository(db, dispatchers, itemRepository, store)
    private val files = InMemoryFileStore()
    private var photoCounter = 0
    private val photos = PhotoStore(files, FakeImageCodec(), {
        "01HPH0T0" + "0".repeat(16) +
            (photoCounter++).toString().padStart(2, '0')
    }, Dispatchers.Unconfined)

    private fun CommandResult.id(): String {
        val done = assertIs<CommandResult.Done>(this)
        return done.events.first().payload.let { p ->
            when (p) {
                is EventPayload.PlaceCreated -> p.placeId
                is EventPayload.ContainerCreated -> p.containerId
                is EventPayload.ItemCreated -> p.itemId
                else -> error("unexpected ${p.typeName}")
            }
        }
    }

    private suspend fun place(
        name: String,
        parentId: String? = null,
        kind: PlaceKind = PlaceKind.SPOT,
    ): String {
        clock.tick()
        return places.create(name, parentId, kind, source = EventSource.TEXT).id()
    }

    private suspend fun item(
        name: String,
        location: LocationRef,
        tags: List<String> = emptyList(),
    ): String {
        clock.tick()
        return items.create(ItemDraft(name, tags = tags), location, EventSource.TEXT).id()
    }

    @Test
    fun should_list_roots_then_children_containers_and_direct_items_with_the_path_C6_C7() = runTest {
        val cave = place("Cave", kind = PlaceKind.ROOT)
        val garage = place("Garage", kind = PlaceKind.ROOT)
        val shelf = place("Étagère du fond", cave)
        clock.tick()
        val box = containers.create("Carton 7", shelf, 7, EventSource.TEXT).id()
        val skis = item("skis", LocationRef.InPlace(shelf))
        val lights = item("guirlandes", LocationRef.InContainer(box))
        val bike = item("vélo", LocationRef.InPlace(cave))

        val root = browse.observePlace(null).first()
        assertEquals(null, root.place)
        assertEquals(listOf("Cave", "Garage"), root.children.map { it.name })
        assertTrue(root.items.isEmpty() && root.containers.isEmpty() && root.path.isEmpty())

        val inCave = browse.observePlace(cave).first()
        assertEquals("Cave", inCave.place?.name)
        assertEquals(listOf("Cave"), inCave.path.map { it.name })
        assertEquals(listOf("Étagère du fond"), inCave.children.map { it.name })
        assertEquals(listOf(bike), inCave.items.map { it.item.id }, "only the item stored directly in the cave")

        val onShelf = browse.observePlace(shelf).first()
        assertEquals(listOf("Cave", "Étagère du fond"), onShelf.path.map { it.name })
        assertEquals(listOf("Carton 7"), onShelf.containers.map { it.label })
        assertEquals(listOf(skis), onShelf.items.map { it.item.id }, "the item in the box is not listed on the shelf")

        val inBox = assertNotNull(browse.observeContainer(box).first())
        assertEquals(listOf("Cave", "Étagère du fond"), inBox.path.map { it.name })
        assertEquals(listOf(lights), inBox.items.map { it.item.id })
        assertEquals(garage, browse.observePlace(null).first().children.last().id)
    }

    @Test
    fun should_move_a_container_with_its_content_in_one_event_D4() = runTest {
        val cave = place("Cave", kind = PlaceKind.ROOT)
        val garage = place("Garage", kind = PlaceKind.ROOT)
        clock.tick()
        val box = containers.create("Carton 7", cave, 7, EventSource.TEXT).id()
        val a = item("a", LocationRef.InContainer(box))
        val b = item("b", LocationRef.InContainer(box))
        clock.tick()

        val moved = assertIs<CommandResult.Done>(containers.move(box, garage, EventSource.TEXT))

        assertEquals(1, moved.events.size, "one event for the whole box")
        val inGarage = assertNotNull(browse.observeContainer(box).first())
        assertEquals(listOf("Garage"), inGarage.path.map { it.name })
        assertEquals(setOf(a, b), inGarage.items.map { it.item.id }.toSet())
        assertEquals(listOf("Garage", "Carton 7"), itemRepository.observeItem(a).first()?.path?.labels)
    }

    @Test
    fun should_keep_every_event_when_two_places_are_merged_D6() = runTest {
        val cave = place("Cave", kind = PlaceKind.ROOT)
        val cellar = place("Cellier", kind = PlaceKind.ROOT)
        val wine = item("vin", LocationRef.InPlace(cellar))
        val before = db.eventQueries.count().executeAsOne()
        clock.tick()

        assertIs<CommandResult.Done>(places.merge(cellar, cave, EventSource.TEXT))

        assertEquals(before + 1, db.eventQueries.count().executeAsOne(), "a merge adds one event, removes none")
        val inCave = browse.observePlace(cave).first()
        assertEquals(listOf(wine), inCave.items.map { it.item.id }, "the merged place's items show under the target")
        assertEquals(listOf("Cave"), itemRepository.observeItem(wine).first()?.path?.labels)
        assertEquals(listOf("Cave"), browse.observePlace(null).first().children.map { it.name })
    }

    @Test
    fun should_expose_photos_tags_and_history_for_the_item_sheet() = runTest {
        val cave = place("Cave", kind = PlaceKind.ROOT)
        val skis = item("skis", LocationRef.InPlace(cave), tags = listOf("hiver", "sport"))
        item("luge", LocationRef.InPlace(cave), tags = listOf("hiver"))
        clock.tick()
        val file = assertNotNull(photos.store("200x100".encodeToByteArray()))
        assertIs<CommandResult.Done>(attachments.attachPhoto(PhotoOwnerType.PLACE, cave, file, EventSource.TEXT))
        clock.tick()
        assertIs<CommandResult.Done>(
            items.update(skis, ItemPatch(pinned = true, quantity = 2.0, unit = "paires"), EventSource.TEXT),
        )

        assertEquals(listOf("hiver", "sport"), browse.observeTags().first(), "most used first")
        assertEquals(1, browse.observePlace(cave).first().photos.size)
        assertEquals(
            listOf("skis", "luge"),
            browse.observePlace(cave).first().items.map {
                it.item.name
            },
            "pinned first",
        )
        assertEquals(
            listOf("ItemCreated", "ItemStored", "ItemUpdated"),
            browse.observeHistory(skis).first().map {
                it.type
            },
        )
    }

    @Test
    fun should_create_a_template_with_its_rooms_in_the_right_language_D2() = runTest {
        val home = PlaceTemplates.forLang(Lang.FR).first()
        assertEquals("Maison", home.name)
        assertTrue(PlaceTemplates.forLang(Lang.EN).first().children.map { it.name }.contains("Kitchen"))
        clock.tick()

        val created = assertIs<CommandResult.Done>(places.createFromTemplate(home, EventSource.TEXT))

        assertEquals(home.children.size + 1, created.events.size)
        val roots = browse.observePlace(null).first().children
        assertEquals(listOf("Maison"), roots.map { it.name })
        assertEquals(
            home.children.map {
                it.name
            }.sorted(),
            browse.observePlace(roots.single().id).first().children.map { it.name },
        )
        assertEquals(PlaceKind.ROOM, browse.observePlace(roots.single().id).first().children.first().kind)
    }
}
