package app.trouvetout.core.capture

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.usecase.CommandContext
import app.trouvetout.core.domain.usecase.ContainerCommands
import app.trouvetout.core.domain.usecase.ItemCommands
import app.trouvetout.core.domain.usecase.LocationRef
import app.trouvetout.core.domain.usecase.PlaceCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureServiceTest {
    private val db = openTestDatabase()
    private val store = db.testEventStore()
    private val clock = TickingClock()
    private val ctx = CommandContext(store, MonotonicUlidGenerator(clock, Random(7)), clock, "device-C")
    private val service =
        CaptureService(db, ItemCommands(ctx), PlaceCommands(ctx), ContainerCommands(ctx), Dispatchers.Unconfined)

    /** Captures with the default decisions: create what is unknown, act on confident matches. */
    private suspend fun say(text: String): CaptureOutcome {
        clock.tick()
        val proposal = service.propose(text)
        val choice =
            when (val place = proposal.place) {
                is PlaceResolution.Unknown -> PlaceChoice.CreateNew(
                    place.names,
                    place.under?.candidate?.id,
                    place.under?.candidate?.isContainer ?: false,
                )
                is PlaceResolution.Ambiguous -> PlaceChoice.Existing(
                    place.suggestions.first().candidate.id,
                    place.suggestions.first().candidate.isContainer,
                )
                else -> null
            }
        return service.commit(proposal, choice)
    }

    private fun items() = db.itemQueries.selectActive().executeAsList()

    private fun places() = db.placeQueries.selectActive().executeAsList()

    private fun location(itemId: String) = db.locationStateQueries.selectByItem(itemId).executeAsOne()

    @Test
    fun should_create_the_place_and_the_item_from_one_sentence_U1() = runTest {
        val outcome = assertIs<CaptureOutcome.Recorded>(say("passeport dans le tiroir du haut de la commode"))

        assertEquals(1, items().size)
        assertEquals("passeport", items().single().name)
        assertEquals(listOf("tiroir du haut de la commode"), places().map { it.name })
        assertIs<LocationRef.InPlace>(outcome.location)
        assertEquals("passeport dans le tiroir du haut de la commode", outcome.events.first().rawInput)
    }

    @Test
    fun should_reuse_a_known_place_and_walk_down_to_a_new_container() = runTest {
        say("vélo dans la cave")
        say("skis dans la cave")
        assertEquals(listOf("cave"), places().map { it.name }, "the second capture resolved « cave »")

        val outcome = assertIs<CaptureOutcome.Recorded>(say("décos de Noël dans le carton 7 de la cave"))

        val container = db.containerQueries.selectActive().executeAsList().single()
        assertEquals("carton 7", container.label)
        assertEquals(7L, container.number)
        assertEquals(LocationRef.InContainer(container.id), outcome.location)
    }

    @Test
    fun should_follow_an_item_through_take_and_put_back_U5() = runTest {
        val stored = assertIs<CaptureOutcome.Recorded>(say("perceuse dans le garage"))
        val drill = stored.itemIds.single()

        assertIs<CaptureOutcome.Recorded>(say("je prends la perceuse"))
        assertEquals("TAKEN", location(drill).state)

        assertIs<CaptureOutcome.Recorded>(say("perceuse remise au garage"))
        assertEquals("STORED", location(drill).state)
        assertEquals(1, items().size, "no duplicate drill")
    }

    @Test
    fun should_lend_and_get_back_U6() = runTest {
        val stored = assertIs<CaptureOutcome.Recorded>(say("tente dans la cave"))
        val tent = stored.itemIds.single()

        assertIs<CaptureOutcome.Recorded>(say("j'ai prêté la tente à Marc"))
        assertEquals("LENT", location(tent).state)
        assertEquals("Marc", location(tent).lent_to)

        assertIs<CaptureOutcome.Recorded>(say("Marc a rendu la tente"))
        assertEquals("STORED", location(tent).state)
    }

    @Test
    fun should_flag_duplicates_and_move_the_existing_item_when_asked_A14() = runTest {
        val first = assertIs<CaptureOutcome.Recorded>(say("perceuse dans le garage"))
        clock.tick()

        val proposal = service.propose("perceuse dans la cave")
        assertEquals(first.itemIds, proposal.targets.single().matches.map { it.itemId })
        val choice = (proposal.place as PlaceResolution.Unknown).let { PlaceChoice.CreateNew(it.names, null, false) }
        val moved =
            assertIs<CaptureOutcome.Recorded>(
                service.commit(proposal, choice, itemChoices = listOf(ItemChoice(0, first.itemIds.single()))),
            )

        assertEquals(1, items().size)
        assertEquals(moved.location, LocationRef.InPlace(location(first.itemIds.single()).place_id!!))
    }

    @Test
    fun should_refuse_a_movement_of_an_unknown_item() = runTest {
        val outcome = say("je prends la perceuse")
        assertEquals(CaptureOutcome.Refused(CaptureProblem.ITEM_NOT_FOUND), outcome)
    }

    @Test
    fun should_ask_before_writing_when_the_place_is_ambiguous_or_unknown() = runTest {
        say("vélo dans la chambre parentale")
        clock.tick()
        val proposal = service.propose("tapis dans la chambre")
        assertTrue(proposal.needsPlaceDecision)
        assertEquals(CaptureOutcome.Refused(CaptureProblem.PLACE_UNDECIDED), service.commit(proposal))
        assertEquals(1, items().size, "nothing written without a decision")

        val place = places().single()
        val outcome = service.commit(
            proposal,
            PlaceChoice.Existing(place.id, isContainer = false, rememberAlias = true),
        )
        assertIs<CaptureOutcome.Recorded>(outcome)
        assertTrue(
            places().single().aliases.contains("chambre"),
            "the dictated text became an alias: ${places().single().aliases}",
        )
    }

    @Test
    fun should_turn_a_question_into_a_search_and_nonsense_into_a_refusal() = runTest {
        assertEquals(CaptureOutcome.Search("clés de la voiture"), say("où sont les clés de la voiture ?"))
        assertEquals(CaptureOutcome.Refused(CaptureProblem.NOTHING_UNDERSTOOD), say("euh"))
        assertNull(items().firstOrNull())
    }

    @Test
    fun should_offer_place_autocomplete() = runTest {
        say("vélo dans la cave")
        say("skis dans le garage")
        assertEquals(listOf("cave"), service.suggestPlaces("ca").map { it.name })
        assertNotNull(service.suggestPlaces("gar").firstOrNull())
    }

    @Test
    fun should_recognise_container_names() {
        assertEquals(7L, CaptureService.containerNumber("carton 7"))
        assertEquals(-1L, CaptureService.containerNumber("boîte rouge"))
        assertNull(CaptureService.containerNumber("étagère du fond"))
        assertEquals(3L, CaptureService.containerNumber("box 3"))
    }

    @Test
    fun should_refuse_before_writing_anything_when_the_item_of_a_movement_is_unknown() = runTest {
        val proposal = service.propose("perceuse remise au garage")
        val choice = (proposal.place as PlaceResolution.Unknown).let { PlaceChoice.CreateNew(it.names, null, false) }

        assertEquals(CaptureOutcome.Refused(CaptureProblem.ITEM_NOT_FOUND), service.commit(proposal, choice))

        assertTrue(places().isEmpty(), "no place created by a refused capture")
        assertEquals(0L, db.eventQueries.count().executeAsOne())
    }

    @Test
    fun should_apply_each_item_choice_to_its_own_target() = runTest {
        say("perceuse dans le garage")
        val tent = assertIs<CaptureOutcome.Recorded>(say("tente dans la cave")).itemIds.single()
        clock.tick()

        val proposal = service.propose("perceuse et tente dans le salon")
        assertEquals(2, proposal.targets.size)
        val choice = (proposal.place as PlaceResolution.Unknown).let { PlaceChoice.CreateNew(it.names, null, false) }
        // First target: create a second drill; second target: move the existing tent.
        assertIs<CaptureOutcome.Recorded>(service.commit(proposal, choice, itemChoices = listOf(ItemChoice(1, tent))))

        assertEquals(3, items().size)
        val salon = places().single { it.name == "salon" }
        assertEquals(salon.id, location(tent).place_id)
    }

    @Test
    fun should_remember_the_alias_without_its_position_preposition() = runTest {
        say("tapis dans le salon")
        clock.tick()
        val proposal = service.propose("ballon sous le canapé du salon")
        val salon = places().single()
        assertIs<CaptureOutcome.Recorded>(
            service.commit(proposal, PlaceChoice.Existing(salon.id, isContainer = false, rememberAlias = true)),
        )
        assertTrue(places().single().aliases.contains("canapé du salon"), places().single().aliases)
        assertTrue(!places().single().aliases.contains("sous"), places().single().aliases)
    }

    @Test
    fun should_refuse_an_overlong_sentence_before_any_work() = runTest {
        val wall = "a".repeat(100_000)
        val proposal = service.propose("$wall dans le garage")
        assertEquals(CaptureProblem.INPUT_TOO_LONG, proposal.problem)
        assertEquals(CaptureOutcome.Refused(CaptureProblem.INPUT_TOO_LONG), service.commit(proposal))
        val list = (1..10_000).joinToString(", ") { "objet $it" }
        assertEquals(CaptureProblem.INPUT_TOO_LONG, service.propose("$list dans le garage").problem)
        assertEquals(0L, db.eventQueries.count().executeAsOne())
    }

    @Test
    fun should_keep_hostile_characters_as_plain_text() = runTest {
        val nasty = "clé ';\" -- DROP TABLE item; % * 🔑 ‏ dans le garage"
        val outcome = assertIs<CaptureOutcome.Recorded>(say(nasty))
        val name = items().single().name
        // Quotes and semicolons are punctuation for the tokenizer; the rest stays plain text, nothing is interpreted.
        assertTrue(name.contains("DROP TABLE item") && name.contains("% *") && name.contains("🔑"), name)
        assertEquals(nasty, outcome.events.first().rawInput)
        assertEquals(1, places().size)
    }

    @Test
    fun should_tell_the_host_which_target_to_ask_about() = runTest {
        say("perceuse dans le garage")
        clock.tick()
        val proposal = service.propose("marteau et perceuse dans la cave")
        assertEquals(1, proposal.nextQuestion(emptyList()), "only the drill has a look-alike")
        assertEquals(null, proposal.nextQuestion(listOf(1)))
        val movement = service.propose("je prends le marteau et la perceuse")
        assertEquals(0, movement.nextQuestion(emptyList()), "the hammer is unknown, the drill is confident")
    }
}
