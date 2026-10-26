package app.trouvetout.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateDetectorTest {
    private val detector =
        DuplicateDetector(
            listOf("1" to "Perceuse", "2" to "Perceuse sans fil", "3" to "Tente", "4" to "Clés de la voiture"),
        )

    @Test
    fun should_flag_the_same_name_with_different_case_or_accents() {
        assertEquals(listOf("1"), detector.find("perceuse").map { it.itemId })
        assertEquals(listOf("4"), detector.find("cles de la voiture").map { it.itemId })
    }

    @Test
    fun should_ignore_clearly_different_names() {
        assertTrue(detector.find("perceuse à percussion").none { it.itemId == "3" })
        assertEquals(emptyList(), detector.find("marteau"))
    }

    @Test
    fun should_rank_the_closest_first() {
        val matches = detector.find("perceuse sans fils")
        assertEquals("2", matches.first().itemId)
    }

    @Test
    fun should_not_merge_names_that_differ_by_a_number_or_a_last_letter() {
        val people = DuplicateDetector(listOf("a" to "passeport de Léa", "7" to "carton 7"))
        assertEquals(emptyList(), people.find("passeport de Léo").map { it.itemId })
        assertEquals(emptyList(), people.find("carton 8").map { it.itemId })
        assertEquals(
            listOf("a"),
            people.find("passeports de léa").map {
                it.itemId
            },
            "plural and accents are tolerated",
        )
    }
}
