package app.trouvetout.core.search

import app.trouvetout.core.capture.Lang
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.usecase.ItemPatch
import app.trouvetout.core.domain.usecase.LocationRef
import app.trouvetout.core.ml.Label
import app.trouvetout.core.ml.LabelCatalog
import app.trouvetout.core.ml.LabelCatalogHeader
import app.trouvetout.core.ml.ModelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoSearchTest {
    private val labels =
        listOf(
            Label("tent", "tente", "tent"),
            Label("corkscrew", "tire-bouchon", "corkscrew"),
            Label("drill", "perceuse", "drill"),
            Label("glove", "gant", "glove"),
        )

    /** Text and image share one meaning in the fake model: « camping » is the tent's concept. */
    private val concepts = mapOf("camping" to "tente", "toile" to "tente")

    private fun model(imageModelId: String? = "fake-vision") =
        FakeModelSource(concepts, imageModelId = imageModelId, labels = labels)

    private val loose = SearchTuning(semanticFloor = 0.0f, semanticAnswerFloor = 0.0f)

    @Test
    fun a_photo_gets_three_names_in_the_user_language_B8() = runTest {
        val world = SearchWorld(model(), loose)
        val fr = world.search.suggestNames("tente".encodeToByteArray(), Lang.FR)
        assertEquals("tente", fr.first().name)
        assertEquals(3, fr.size)
        assertEquals("tent", world.search.suggestNames("tente".encodeToByteArray(), Lang.EN).first().name)
    }

    @Test
    fun no_names_without_the_photo_model_B8() = runTest {
        val photo = "tente".encodeToByteArray()
        assertTrue(SearchWorld(model(imageModelId = null), loose).search.suggestNames(photo, Lang.FR).isEmpty())
        val textOnly = SearchWorld(model(), loose, imagesWanted = false)
        assertTrue(textOnly.search.suggestNames(photo, Lang.FR).isEmpty(), "4 GB phone or « text only »")
    }

    @Test
    fun a_catalog_made_with_another_model_is_not_used() = runTest {
        val other = FakeModelSource(concepts, modelId = "other-model", imageModelId = "fake-vision", labels = labels)
        val world = SearchWorld(other, loose)
        // The catalog is stamped with its own model id, so this one matches: names come.
        assertTrue(world.search.suggestNames("tente".encodeToByteArray(), Lang.FR).isNotEmpty())
        val stale = LabelCatalog.decode(checkNotNull(model().labelCatalog()))
        assertEquals("fake-model", stale?.header?.modelId, "stamped with the model that computed it")
    }

    @Test
    fun the_label_catalog_round_trips() {
        val vectors = FloatArray(2 * 4) { it / 10f }
        val catalog = LabelCatalog(LabelCatalogHeader("m", 4, "{fr} / {en}", "abc", 2), labels.take(2), vectors)
        val back = assertNotNull(LabelCatalog.decode(catalog.encode()))
        assertEquals(catalog.header, back.header)
        assertEquals(catalog.labels, back.labels)
        assertEquals(0.5f, back.vector(1)[1], 0.001f)
        assertNull(LabelCatalog.decode("not a catalog".encodeToByteArray()))
    }

    @Test
    fun item_photos_are_embedded_found_by_a_photo_and_forgotten_when_detached_C10() = runTest {
        val model = model()
        val world = SearchWorld(model, loose)
        val house = world.place("Maison")
        val tent = world.item("Tente quatre places", LocationRef.InPlace(house))
        val drill = world.item("Perceuse", LocationRef.InPlace(house))
        world.photo(tent, "toile verte camping")
        world.photo(drill, "perceuse bosch")
        world.search.prepareAndWait()
        assertEquals(2, model.embedder.images, "one vector per item photo")
        assertEquals(2L, world.db.embeddingQueries.countFor("photo", "fake-vision/256/i1").executeAsOne())

        val result = world.search.searchByPhoto("camping toile".encodeToByteArray())
        assertTrue(result.byPhoto)
        assertEquals(tent, result.hits.first().view.item.id)
        assertEquals(MatchKind.PHOTO, result.hits.first().match)
        assertEquals(tent, result.answer?.itemId)

        // The text vectors of a new pass never take the photo vectors with them.
        val before = model.embedder.images
        world.items.update(drill, ItemPatch(description = "sans fil"), EventSource.TEXT)
        world.search.prepareAndWait()
        assertEquals(2L, world.db.embeddingQueries.countFor("photo", "fake-vision/256/i1").executeAsOne())
        assertEquals(before, model.embedder.images, "an unchanged photo is not embedded again")
    }

    @Test
    fun a_single_photo_in_the_household_is_still_found_C10() = runTest {
        val world = SearchWorld(model(), SearchTuning(photoFloor = 0.3f, photoTextFloor = 0.9f))
        val house = world.place("Maison")
        val tent = world.item("Tente", LocationRef.InPlace(house))
        world.photo(tent, "toile verte camping")
        world.search.prepareAndWait()
        val found = world.search.searchByPhoto("toile verte camping".encodeToByteArray())
        assertEquals(tent, found.hits.single().view.item.id)
        assertTrue(
            world.search.searchByPhoto("perceuse bosch".encodeToByteArray()).hits.isEmpty(),
            "nothing looks like it: no answer rather than a wrong one",
        )
    }

    @Test
    fun text_only_devices_never_load_the_vision_file_and_keep_text_search() = runTest {
        val model = model()
        val world = SearchWorld(model, loose, imagesWanted = false)
        val house = world.place("Maison")
        val tent = world.item("Tente", LocationRef.InPlace(house))
        world.photo(tent, "toile verte camping")
        world.search.prepareAndWait()
        assertEquals(0, model.loadsWithImages)
        assertEquals(0, model.embedder.images)
        assertTrue(world.search.searchByPhoto("camping".encodeToByteArray()).hits.isEmpty())
        assertEquals(tent, world.search.search("tente").hits.first().view.item.id)
    }

    @Test
    fun a_vision_file_that_fails_to_load_leaves_text_search_working() = runTest {
        val model = FakeModelSource(concepts, imageModelId = "fake-vision", labels = labels, failWithImages = true)
        val world = SearchWorld(model, loose)
        val house = world.place("Maison")
        val tent = world.item("Tente quatre places", LocationRef.InPlace(house))
        world.photo(tent, "toile verte camping")
        world.search.prepareAndWait()
        assertEquals(tent, world.search.search("camping").hits.first().view.item.id, "meaning still found")
        assertTrue(world.search.suggestNames("tente".encodeToByteArray(), Lang.FR).isEmpty())
        assertEquals(0, model.embedder.images)
    }

    @Test
    fun the_model_settings_follow_memory_and_the_user_choice() = runTest {
        val gib = 1024L * 1024 * 1024
        val small = ModelSettings(openTestDatabase(), 4 * gib, bundled = true, io = Dispatchers.Unconfined)
        assertTrue(!small.state.value.imagesActive, "a 4 GB phone gets the text model only")
        val db = openTestDatabase()
        val big = ModelSettings(db, 8 * gib, bundled = true, io = Dispatchers.Unconfined)
        assertTrue(big.state.value.imagesActive)
        assertTrue(big.state.value.photoSearchAvailable)
        var changes = 0
        big.onModelChange = { changes++ }
        big.setForceTextOnly(true)
        assertTrue(!big.state.value.imagesActive)
        assertEquals(1, changes)
        val again = ModelSettings(db, 8 * gib, bundled = true, io = Dispatchers.Unconfined)
        assertTrue(again.state.value.forceTextOnly, "kept in meta")
        val notBundled = ModelSettings(openTestDatabase(), 8 * gib, bundled = false, io = Dispatchers.Unconfined)
        assertTrue(!notBundled.state.value.imagesActive)
    }
}
