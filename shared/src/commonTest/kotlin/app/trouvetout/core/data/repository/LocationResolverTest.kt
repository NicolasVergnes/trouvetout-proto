package app.trouvetout.core.data.repository

import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.LocationState
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.domain.model.PlaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class LocationResolverTest {
    private val t0 = Instant.fromEpochMilliseconds(0)

    private fun place(
        id: String,
        name: String,
        parent: String? = null,
        mergedInto: String? = null,
    ) = Place(id, name, parent, PlaceKind.ROOM, emptyList(), t0, null, mergedInto)

    private fun state(
        placeId: String? = null,
        containerId: String? = null,
    ) = LocationState("i", placeId, containerId, LocationStatus.STORED, null, t0)

    private val places =
        listOf(
            place("house", "maison"),
            place("cellar", "cave", parent = "house"),
            place("shelf", "étagère du fond", parent = "cellar"),
            place("oldcellar", "cellier", parent = "house", mergedInto = "cellar"),
        )
    private val containers = listOf(Container("box7", "carton 7", 7, "shelf", t0, null))
    private val resolver = LocationResolver(places, containers)

    @Test
    fun should_build_the_root_first_path_of_a_place() {
        assertEquals(listOf("maison", "cave", "étagère du fond"), resolver.pathOf("shelf").map { it.name })
    }

    @Test
    fun should_resolve_a_container_through_its_place() {
        val path = resolver.resolve(state(containerId = "box7"))
        assertEquals(listOf("maison", "cave", "étagère du fond", "carton 7"), path.labels)
        assertEquals("box7", path.container?.id)
    }

    @Test
    fun should_follow_merged_places_to_their_target() {
        assertEquals(listOf("maison", "cave"), resolver.pathOf("oldcellar").map { it.name })
    }

    @Test
    fun should_return_unknown_for_items_without_location_or_unknown_ids() {
        assertTrue(resolver.resolve(state()).isUnknown)
        assertTrue(resolver.resolve(state(placeId = "nope")).isUnknown)
        assertTrue(resolver.resolve(state(containerId = "nobox")).isUnknown)
    }

    @Test
    fun should_stop_on_parent_cycles() {
        val cyclic = LocationResolver(listOf(place("a", "a", parent = "b"), place("b", "b", parent = "a")), emptyList())
        assertEquals(2, cyclic.pathOf("a").size)
    }
}
