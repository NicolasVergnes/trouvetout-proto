package app.trouvetout.core.search

import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.usecase.ItemPatch
import app.trouvetout.core.domain.usecase.LocationRef
import app.trouvetout.core.ml.KeywordOnlyReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchServiceTest {
    /** « kindle » and « câble » share a meaning the keyword index cannot see (U3). */
    private val concepts = mapOf("kindle" to "charge", "cable" to "charge", "liseuse" to "charge")

    /** The fake vectors mix every word of an item: looser floors than the real model's. */
    private val loose = SearchTuning(semanticFloor = 0.3f, semanticAnswerFloor = 0.4f)

    private suspend fun SearchWorld.household(): Map<String, String> {
        val house = place("Maison")
        val office = place("Bureau", house)
        val cellar = place("Cave", house)
        val box = container("Carton 7", cellar)
        return mapOf(
            "house" to house,
            "office" to office,
            "cellar" to cellar,
            "box" to box,
            "cable" to item("Câble USB-C blanc", LocationRef.InPlace(office)),
            "passport" to item("Passeport", LocationRef.InPlace(office), tags = listOf("papiers"), sensitive = true),
            "gloves" to item("Gants", LocationRef.InContainer(box), tags = listOf("ski")),
            "tent" to item("Tente", LocationRef.InPlace(cellar), description = "quatre places"),
        )
    }

    @Test
    fun without_a_model_the_search_answers_with_keywords_and_says_so() = runTest {
        val world = SearchWorld(model = null)
        val ids = world.household()
        assertEquals(SearchMode.KeywordOnly(KeywordOnlyReason.NO_MODEL), world.search.mode.value)
        val result = world.search.search("chargeur")
        assertEquals(ids["cable"], result.hits.first().view.item.id, "synonyms work without the model")
        assertEquals(MatchKind.KEYWORD, result.hits.first().match)
    }

    @Test
    fun the_model_finds_what_keywords_cannot_U3() = runTest {
        val model = FakeModelSource(concepts)
        val world = SearchWorld(model, loose)
        val ids = world.household()
        assertTrue(world.search.search("le Kindle").hits.isEmpty(), "no vectors yet: nothing semantic")
        world.search.prepare()
        assertEquals(SearchMode.Hybrid, world.search.mode.value)
        val result = world.search.search("le Kindle")
        assertEquals(ids["cable"], result.hits.first().view.item.id)
        assertEquals(MatchKind.SEMANTIC, result.hits.first().match)
    }

    @Test
    fun the_answer_line_carries_the_full_location_C4_C6() = runTest {
        val world = SearchWorld()
        val ids = world.household()
        val answer = assertNotNull(world.search.search("gants").answer)
        assertEquals(ids["gloves"], answer.itemId)
        assertEquals(listOf("Maison", "Cave"), answer.places.map { it.name })
        assertEquals(listOf(ids["house"], ids["cellar"]), answer.places.map { it.id })
        assertEquals("Carton 7", answer.containerLabel)
        assertEquals(LocationStatus.STORED, answer.status)
    }

    @Test
    fun nothing_found_is_an_empty_result() = runTest {
        val world = SearchWorld(FakeModelSource(concepts), loose)
        world.household()
        world.search.prepare()
        val result = world.search.search("xylophone")
        assertTrue(result.hits.isEmpty())
        assertNull(result.answer)
    }

    @Test
    fun filters_narrow_and_list_without_a_query_C11() = runTest {
        val world = SearchWorld()
        val ids = world.household()
        val inCellar = world.search.search("", SearchFilters(placeId = ids["cellar"]))
        assertEquals(
            setOf(ids["gloves"], ids["tent"]),
            inCellar.hits.map {
                it.view.item.id
            }.toSet(),
            "a place includes its containers",
        )
        assertNull(inCellar.answer)
        assertEquals(
            listOf(ids["gloves"]),
            world.search.search("", SearchFilters(containerId = ids["box"])).hits.map {
                it.view.item.id
            },
        )
        assertEquals(
            listOf(ids["passport"]),
            world.search.search("", SearchFilters(tag = "Papiers")).hits.map {
                it.view.item.id
            },
        )
        assertTrue(world.search.search("gants", SearchFilters(placeId = ids["office"])).hits.isEmpty())
        assertTrue(world.search.search("").hits.isEmpty(), "no query, no filter: nothing")

        world.items.take(ids.getValue("tent"), EventSource.TEXT)
        val taken = world.search.search("", SearchFilters(state = ItemStateFilter.TAKEN))
        assertEquals(listOf(ids["tent"]), taken.hits.map { it.view.item.id }, "the index follows the journal")
        world.items.setArchived(ids.getValue("cable"), true, EventSource.TEXT)
        assertEquals(
            listOf(ids["cable"]),
            world.search.search("", SearchFilters(state = ItemStateFilter.ARCHIVED)).hits.map {
                it.view.item.id
            },
        )
        assertEquals(
            ids["cable"],
            world.search.search("cable").hits.first().view.item.id,
            "archived items still answer",
        )
    }

    @Test
    fun suggestions_complete_names_tags_and_places_but_never_a_sensitive_item_C12() = runTest {
        val world = SearchWorld()
        world.household()
        val texts = world.search.suggest("pa").map { it.text }
        assertTrue("Passeport" !in texts, "sensitive item: $texts")
        assertTrue("papiers" !in texts, "tags of a sensitive item are not suggested either: $texts")
        assertEquals(listOf("Gants"), world.search.suggest("gan").map { it.text })
        assertEquals(SuggestionKind.PLACE, world.search.suggest("cav").single().kind)
        assertTrue(world.search.suggest("").isEmpty())
    }

    @Test
    fun an_opened_item_moves_up_among_equals_C3() = runTest {
        val world = SearchWorld()
        val house = world.place("Maison")
        val first = world.item("Lampe", LocationRef.InPlace(house))
        world.clock.millis += 30L * 24 * 3600 * 1000
        val second = world.item("Lampe", LocationRef.InPlace(house))
        val before = world.search.search("lampe").hits.map { it.view.item.id }
        assertEquals(listOf(second, first), before, "the most recently stored first")
        repeat(3) { world.search.recordOpened(first) }
        assertEquals(first, world.search.search("lampe").hits.first().view.item.id)
    }

    @Test
    fun vectors_are_stored_reused_and_recomputed_only_when_needed() = runTest {
        val model = FakeModelSource(concepts)
        val world = SearchWorld(model, loose)
        val ids = world.household()
        world.search.prepare()
        assertEquals(4, model.embedder.texts, "one vector per item")
        assertEquals(
            4L,
            world.db.embeddingQueries.countFor("item", SearchTuning().modelKey("fake-model")).executeAsOne(),
        )

        // A new launch reads the stored vectors instead of recomputing them (the query itself is embedded).
        val relaunched = SearchWorld(model, loose, db = world.db)
        relaunched.search.prepare()
        assertEquals(4, model.embedder.texts)
        assertEquals(ids["cable"], relaunched.search.search("kindle").hits.first().view.item.id)
        assertEquals(5, model.embedder.texts)

        // Editing one item recomputes that one only; a deleted item loses its vector.
        world.items.update(ids.getValue("tent"), ItemPatch(description = "deux places"), EventSource.TEXT)
        world.items.delete(ids.getValue("gloves"), EventSource.TEXT)
        world.search.prepare()
        assertEquals(6, model.embedder.texts)
        assertEquals(
            3L,
            world.db.embeddingQueries.countFor("item", SearchTuning().modelKey("fake-model")).executeAsOne(),
        )
    }

    @Test
    fun another_model_replaces_the_vectors_without_losing_anything() = runTest {
        val world = SearchWorld(FakeModelSource(concepts, modelId = "old"), loose)
        world.household()
        world.search.prepare()
        val newer = FakeModelSource(concepts, modelId = "new")
        val upgraded = SearchWorld(newer, loose, db = world.db)
        upgraded.search.prepare()
        assertEquals(4, newer.embedder.texts)
        assertEquals(0L, world.db.embeddingQueries.countFor("item", SearchTuning().modelKey("old")).executeAsOne())
        assertEquals(4L, world.db.embeddingQueries.countFor("item", SearchTuning().modelKey("new")).executeAsOne())
    }

    @Test
    fun a_model_that_fails_to_load_leaves_keyword_search_working() = runTest {
        val world = SearchWorld(FakeModelSource(concepts, fail = true), loose)
        val ids = world.household()
        world.search.prepare()
        assertEquals(SearchMode.KeywordOnly(KeywordOnlyReason.LOAD_FAILED), world.search.mode.value)
        assertEquals(ids["cable"], world.search.search("cable").hits.first().view.item.id)
    }

    @Test
    fun an_unsupported_device_never_loads_the_model() = runTest {
        val model = FakeModelSource(concepts, isSupported = false)
        val world = SearchWorld(model, loose)
        world.household()
        world.search.prepare()
        assertEquals(SearchMode.KeywordOnly(KeywordOnlyReason.UNSUPPORTED_DEVICE), world.search.mode.value)
        world.search.search("kindle")
        assertEquals(0, model.loads)
    }

    @Test
    fun release_frees_the_model_and_the_next_use_loads_it_again() = runTest {
        val model = FakeModelSource(concepts)
        val world = SearchWorld(model, loose)
        world.household()
        world.search.prepare()
        assertTrue(world.runtime.isLoaded)
        world.search.release()
        assertTrue(model.embedder.closed)
        assertTrue(!world.runtime.isLoaded)
        assertIs<SearchMode.Hybrid>(world.search.mode.value)
        world.search.search("kindle")
        assertEquals(2, model.loads)
    }
}
