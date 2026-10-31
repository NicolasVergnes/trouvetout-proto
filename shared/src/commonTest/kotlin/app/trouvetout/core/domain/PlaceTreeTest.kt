package app.trouvetout.core.domain

import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.places.PlaceTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class PlaceTreeTest {
    private fun place(
        id: String,
        parent: String?,
    ) = Place(id, id, parent, PlaceKind.SPOT, emptyList(), Instant.fromEpochMilliseconds(0), null, null)

    @Test
    fun descendants_include_the_place_and_everything_below_it() {
        val places =
            listOf(place("house", null), place("cellar", "house"), place("shelf", "cellar"), place("car", null))
        assertEquals(setOf("cellar", "shelf"), PlaceTree.descendantIds("cellar", places))
        assertEquals(setOf("house", "cellar", "shelf"), PlaceTree.descendantIds("house", places))
        assertEquals(setOf("car"), PlaceTree.descendantIds("car", places))
    }

    @Test
    fun a_cycle_in_corrupted_data_ends_the_walk() {
        val places = listOf(place("a", "b"), place("b", "a"))
        assertEquals(setOf("a", "b"), PlaceTree.descendantIds("a", places))
    }
}
