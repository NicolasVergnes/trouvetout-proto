package app.trouvetout.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {
    private val parser = Parser()

    private fun fr(text: String) = parser.parse(text, Lang.FR)

    private fun en(text: String) = parser.parse(text, Lang.EN)

    @Test
    fun should_split_object_and_place_at_the_first_plain_preposition() {
        val r = fr("passeport dans le tiroir du haut de la commode")
        assertEquals(Intent.STORE, r.intent)
        assertEquals(listOf("passeport"), r.items.map { it.name })
        assertEquals("tiroir du haut de la commode", r.place)
    }

    @Test
    fun should_keep_a_position_preposition_in_the_place_text() {
        assertEquals("sous le canapé", fr("3 m de câble HDMI sous le canapé").place)
        assertEquals(
            "behind the books in the living room",
            en("family record book behind the books in the living room").place,
        )
    }

    @Test
    fun should_not_treat_a_bare_a_inside_a_name_as_a_place() {
        val r = fr("je remets la clé à molette")
        assertEquals(Intent.RETURN, r.intent)
        assertEquals(listOf("clé à molette"), r.items.map { it.name })
        assertNull(r.place)
        assertEquals("cave", fr("la valise rouge est à la cave").place)
    }

    @Test
    fun should_extract_quantities_and_units() {
        val r = fr("3 m de câble HDMI sous le canapé").items.single()
        assertEquals(3.0, r.quantity)
        assertEquals("m", r.unit)
        assertEquals("câble HDMI", r.name)
        val screws = en("twelve M4 screws in the blue bin").items.single()
        assertEquals(12.0, screws.quantity)
        assertNull(screws.unit)
        assertEquals("M4 screws", screws.name)
    }

    @Test
    fun should_split_several_objects_unless_the_phrase_is_a_known_item() {
        assertEquals(
            listOf("passeport", "carnet de santé"),
            fr("passeport et carnet de santé dans le tiroir").items.map {
                it.name
            },
        )
        val knowing = Parser(knownItemNames = listOf("pain et chocolat"))
        assertEquals(
            listOf("pain et chocolat"),
            knowing.parse("pain et chocolat dans le placard", Lang.FR).items.map {
                it.name
            },
        )
    }

    @Test
    fun should_recognise_questions_and_keep_only_the_searched_words() {
        assertEquals("clés de la voiture", fr("où sont les clés de la voiture ?").query)
        assertEquals("lunettes de soleil", fr("c'est où les lunettes de soleil").query)
        assertEquals("passeport", fr("ou est le passeport").query, "missing accent tolerated")
        assertEquals("passport", en("where's the passport?").query)
        assertEquals("keys", en("where did I put my keys").query)
        assertEquals(Intent.QUERY, fr("les clés de la voiture c'est où").intent)
    }

    @Test
    fun should_recognise_movements() {
        assertEquals(Intent.TAKE, fr("je prends la perceuse").intent)
        assertEquals(Intent.TAKE, en("I'm taking the drill").intent)
        val back = fr("perceuse remise au garage")
        assertEquals(Intent.RETURN, back.intent)
        assertEquals("garage", back.place)
        val putBack = en("put the drill back in the garage")
        assertEquals(Intent.RETURN, putBack.intent)
        assertEquals(listOf("drill"), putBack.items.map { it.name })
        assertEquals("garage", putBack.place)
        val moved = fr("la perceuse est maintenant dans le garage")
        assertEquals(Intent.MOVE_TO, moved.intent)
        assertEquals(listOf("perceuse"), moved.items.map { it.name })
        assertEquals("garage", moved.place)
        assertEquals(Intent.MOVE_TO, en("the drill is now in the garage").intent)
        assertEquals("garage", en("moved the drill to the garage").place)
    }

    @Test
    fun should_recognise_loans_and_their_return() {
        val lent = fr("j'ai prêté la tente à Marc")
        assertEquals(Intent.LEND, lent.intent)
        assertEquals("Marc", lent.person)
        assertEquals(listOf("tente"), lent.items.map { it.name })
        assertEquals("Sophie", fr("prêté le vidéoprojecteur à Sophie").person)
        assertEquals("Mark", en("lent the tent to Mark").person)
        val returned = fr("Marc a rendu la tente")
        assertEquals(Intent.RETURN_FROM_LEND, returned.intent)
        assertEquals("Marc", returned.person)
        assertEquals("Marc", fr("Marc m'a rapporté la tente").person)
        val back = en("Mark brought the tent back")
        assertEquals(Intent.RETURN_FROM_LEND, back.intent)
        assertEquals(listOf("tent"), back.items.map { it.name })
        assertEquals(Intent.LEND, en("Mark borrowed the tent").intent)
        assertEquals(
            Intent.RETURN,
            en("I returned the drill to the garage").intent,
            "a first-person return is not a loan return",
        )
    }

    @Test
    fun should_drop_fillers_and_articles_and_keep_elisions_readable() {
        val r = fr("euh le chargeur du kindle sur le bureau")
        assertEquals(listOf("chargeur du kindle"), r.items.map { it.name })
        assertEquals("bureau", r.place)
        assertEquals("bac bleu de l'atelier", fr("12 vis M4 dans le bac bleu de l'atelier").place)
        assertEquals("entrée", fr("clés@entrée").place)
    }

    @Test
    fun should_never_report_a_place_without_a_preposition() {
        for (text in listOf("passeport", "les clés de la voiture", "perceuse sans fil", "tente deux places")) {
            val r = fr(text)
            assertNull(r.place, text)
            assertEquals(Intent.STORE, r.intent)
            assertTrue(r.confidence < 0.6f, "a bare noun phrase is a low-confidence proposal")
        }
    }

    @Test
    fun should_detect_the_language_from_markers() {
        assertEquals(Lang.EN, parser.detectLang("spare keys in the top drawer of the dresser"))
        assertEquals(Lang.FR, parser.detectLang("les clés dans le tiroir"))
        assertEquals(Lang.FR, parser.detectLang("passeport"), "ties go to the default language")
    }

    @Test
    fun should_return_unknown_for_an_empty_sentence() {
        assertEquals(Intent.UNKNOWN, fr("euh").intent)
        assertEquals(Intent.UNKNOWN, fr("  ").intent)
    }
}
