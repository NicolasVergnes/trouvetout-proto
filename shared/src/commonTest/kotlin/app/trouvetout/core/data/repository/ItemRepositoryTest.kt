package app.trouvetout.core.data.repository

import app.trouvetout.core.data.EventFixtures
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.platform.CoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemRepositoryTest {
    private val db = openTestDatabase()
    private val store = db.testEventStore()
    private val fx = EventFixtures()
    private val dispatchers =
        CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val items = ItemRepository(db, dispatchers)
    private val containers = ContainerRepository(db, dispatchers)

    @Test
    fun should_expose_items_with_their_resolved_path_and_react_to_new_events() = runTest {
        store.append(
            listOf(
                fx.event(EventPayload.PlaceCreated("house", "maison", kind = PlaceKind.ROOT)),
                fx.event(EventPayload.PlaceCreated("cellar", "cave", parentId = "house", kind = PlaceKind.ROOM)),
                fx.event(EventPayload.ContainerCreated("box7", "carton 7", placeId = "cellar", number = 7)),
                fx.event(EventPayload.ItemCreated("lights", "guirlandes")),
                fx.event(EventPayload.ItemStored("lights", containerId = "box7")),
                fx.event(EventPayload.ItemCreated("loose", "clé")),
            ),
        )

        val views = items.observeActive().first().associateBy { it.item.id }
        assertEquals(listOf("maison", "cave", "carton 7"), views.getValue("lights").path.labels)
        assertEquals(LocationStatus.STORED, views.getValue("lights").location.status)
        assertEquals(emptyList(), views.getValue("loose").path.labels)
        assertEquals(1, containers.observeInPlace("cellar").first().size)

        store.append(listOf(fx.event(EventPayload.ContainerMoved("box7", placeId = "house"))))
        assertEquals(listOf("maison", "carton 7"), items.observeItem("lights").first()?.path?.labels)

        store.append(listOf(fx.event(EventPayload.ItemDeleted("lights"))))
        assertNull(items.observeActive().first().find { it.item.id == "lights" })
        assertEquals(listOf("lights"), items.observeDeleted().first().map { it.item.id })
    }
}
