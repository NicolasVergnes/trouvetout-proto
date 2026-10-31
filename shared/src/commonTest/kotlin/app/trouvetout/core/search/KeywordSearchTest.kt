package app.trouvetout.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeywordSearchTest {
    private fun doc(
        id: String,
        name: String,
        description: String? = null,
        tags: List<String> = emptyList(),
        location: List<String> = emptyList(),
        raw: List<String> = emptyList(),
    ) = SearchDocument(id, name, description, tags, location, raw)

    private val documents =
        listOf(
            doc("cable", "Câble USB-C blanc", location = listOf("Maison", "Bureau", "Tiroir du bureau")),
            doc("passeport", "Passeport", tags = listOf("papiers"), location = listOf("Maison", "Chambre", "Commode")),
            doc("cle", "Clé à molette", location = listOf("Maison", "Garage", "Établi")),
            doc("tire", "Tire-bouchon", location = listOf("Maison", "Cuisine", "Tiroir à couverts")),
            doc("gants-ski", "Gants", tags = listOf("ski"), location = listOf("Maison", "Cave", "Carton 7")),
            doc("gants-jardin", "Gants", tags = listOf("jardin"), location = listOf("Maison", "Garage")),
            doc(
                "perceuse",
                "Perceuse sans fil",
                description = "Bosch 18 V avec deux batteries",
                location = listOf("Maison", "Garage"),
            ),
            doc("guirlandes", "Guirlandes lumineuses", tags = listOf("Noël"), location = listOf("Maison", "Grenier")),
        )
    private val index = KeywordIndex(documents)

    private fun ranked(query: String): List<String> =
        index.search(query).entries.sortedByDescending { it.value }.map { documents[it.key].itemId }

    @Test
    fun terms_fold_accents_plurals_and_grammar_words_C1() {
        assertEquals(listOf("cle", "voiture"), SearchText.terms("Où sont les clés de la voiture ?"))
        assertEquals(listOf("tire", "bouchon", "tirebouchon"), SearchText.terms("le tire-bouchon"))
        assertEquals(listOf("armoire"), SearchText.terms("l'armoire"))
        assertEquals(listOf("carton", "7"), SearchText.terms("carton 7"))
    }

    @Test
    fun exact_accents_and_plurals_find_the_item_C1() {
        assertEquals("passeport", ranked("où est mon passeport ?").first())
        assertEquals("cle", ranked("cle a molette").first())
        assertEquals("guirlandes", ranked("guirlande").first())
    }

    @Test
    fun typos_are_tolerated_C1() {
        assertEquals("perceuse", ranked("perseuse").first())
        assertEquals("passeport", ranked("pasport").first())
    }

    @Test
    fun the_word_being_typed_is_completed_C1() {
        assertEquals("perceuse", ranked("perc").first())
        assertEquals("guirlandes", ranked("guirl").first())
    }

    @Test
    fun synonyms_and_other_language_find_the_item_C3() {
        assertEquals("cable", ranked("chargeur").first(), "U3: chargeur → câble")
        assertEquals("passeport", ranked("passport").first())
        assertEquals("tire", ranked("corkscrew").first(), "a compound is a synonym")
        assertEquals("guirlandes", ranked("christmas").first())
    }

    @Test
    fun the_place_tells_two_items_apart() {
        assertEquals("gants-ski", ranked("les gants de la cave").first())
        assertEquals("gants-jardin", ranked("gants garage").first())
    }

    @Test
    fun description_and_tags_are_searched() {
        assertEquals("perceuse", ranked("bosch").first())
        assertEquals("gants-ski", ranked("ski").first())
    }

    @Test
    fun nothing_matches_nonsense() {
        assertTrue(ranked("xylophone").isEmpty())
    }
}
