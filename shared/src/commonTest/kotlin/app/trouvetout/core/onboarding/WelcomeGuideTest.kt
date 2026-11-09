package app.trouvetout.core.onboarding

import app.trouvetout.core.data.EventFixtures
import app.trouvetout.core.data.EventStore
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.projection.Projector
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.PlaceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** I10: the first steps are offered on a fresh device, never forced, and gone for good once dismissed or used. */
class WelcomeGuideTest {
    private val db = openTestDatabase()
    private val fixtures = EventFixtures()

    private fun guide() = WelcomeGuide(db, Dispatchers.Unconfined)

    @Test
    fun a_fresh_device_offers_the_first_steps() = runTest {
        assertTrue(guide().visible.first())
    }

    @Test
    fun dismissed_they_stay_gone_after_a_relaunch() = runTest {
        guide().dismiss()
        assertFalse(guide().visible.first())
    }

    @Test
    fun the_first_recorded_action_ends_them_even_when_everything_is_deleted_later() = runTest {
        val guide = guide()
        EventStore(db, Projector(db), Dispatchers.Unconfined)
            .append(listOf(fixtures.event(EventPayload.PlaceCreated("p1", "Cave", null, PlaceKind.ROOT, emptyList()))))
        assertFalse(guide.visible.first())
        assertFalse(guide().visible.first())
    }
}
