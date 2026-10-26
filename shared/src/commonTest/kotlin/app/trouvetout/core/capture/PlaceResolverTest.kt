package app.trouvetout.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaceResolverTest {
    private val cave = LocationCandidate("cave", "Cave")
    private val etagere = LocationCandidate("etagere", "Étagère du fond", parentId = "cave")
    private val carton = LocationCandidate("carton7", "Carton 7", parentId = "etagere", isContainer = true)
    private val chambre = LocationCandidate("chambre", "Chambre", aliases = listOf("chambre parentale"))
    private val commode = LocationCandidate("commode", "Commode", parentId = "chambre", aliases = listOf("la commode"))
    private val resolver = PlaceResolver(listOf(cave, etagere, carton, chambre, commode))

    @Test
    fun should_resolve_an_exact_or_near_exact_name_ignoring_case_and_accents() {
        val exact = assertIs<PlaceResolution.Resolved>(resolver.resolve("cave"))
        assertEquals("cave", exact.match.candidate.id)
        val typo = assertIs<PlaceResolution.Resolved>(resolver.resolve("etagere du fond"))
        assertEquals("etagere", typo.match.candidate.id)
    }

    @Test
    fun should_resolve_through_an_alias() {
        val r = assertIs<PlaceResolution.Resolved>(resolver.resolve("chambre parentale"))
        assertEquals("chambre", r.match.candidate.id)
    }

    @Test
    fun should_walk_a_multi_level_text_from_the_root() {
        val r = assertIs<PlaceResolution.Resolved>(resolver.resolve("carton 7 de l'étagère du fond de la cave"))
        assertEquals("carton7", r.match.candidate.id)
    }

    @Test
    fun should_propose_to_create_the_missing_levels_under_the_deepest_known_one() {
        val r = assertIs<PlaceResolution.Unknown>(resolver.resolve("tiroir du haut de la commode"))
        assertEquals(listOf("tiroir du haut"), r.names)
        assertEquals("commode", r.under?.candidate?.id)
        val two = assertIs<PlaceResolution.Unknown>(resolver.resolve("boîte rouge du tiroir du haut de la commode"))
        assertEquals(
            listOf("boîte rouge du tiroir du haut"),
            two.names,
            "unknown levels stay one name; the user splits if needed",
        )
        assertEquals("commode", two.under?.candidate?.id)
    }

    @Test
    fun should_suggest_close_names_instead_of_deciding() {
        val r = assertIs<PlaceResolution.Ambiguous>(resolver.resolve("la chambre"))
        assertEquals("chambre", r.suggestions.first().candidate.id)
        assertTrue(r.suggestions.first().score < PlaceResolver.RESOLVED)
    }

    @Test
    fun should_ignore_a_position_preposition_when_matching() {
        val r = assertIs<PlaceResolution.Resolved>(resolver.resolve("sous la commode"))
        assertEquals("commode", r.match.candidate.id)
    }

    @Test
    fun should_report_unknown_text_as_a_creation() {
        val r = assertIs<PlaceResolution.Unknown>(resolver.resolve("garage"))
        assertEquals(listOf("garage"), r.names)
        assertEquals(null, r.under)
    }

    @Test
    fun should_autocomplete_on_names_and_aliases() {
        assertEquals(listOf("cave"), resolver.suggest("ca").map { it.id }.filter { it == "cave" })
        assertEquals("chambre", resolver.suggest("parent").single().id)
        assertEquals(emptyList(), resolver.suggest("  "))
    }

    @Test
    fun should_split_english_levels_too() {
        val en = PlaceResolver(listOf(LocationCandidate("b", "basement")), Lang.EN)
        val r = assertIs<PlaceResolution.Unknown>(en.resolve("box 7 on the back shelf in the basement"))
        assertEquals(listOf("box 7 on the back shelf"), r.names)
        assertEquals("b", r.under?.candidate?.id)
    }
}
