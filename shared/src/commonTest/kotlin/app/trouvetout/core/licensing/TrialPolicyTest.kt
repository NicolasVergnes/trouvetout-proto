package app.trouvetout.core.licensing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class TrialPolicyTest {
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    @Test
    fun should_count_six_calendar_months_and_clamp_month_ends() {
        assertEquals(millis("2027-05-07T10:00:00Z"), TrialPolicy.endOf(millis("2026-11-07T10:00:00Z")))
        assertEquals(millis("2027-02-28T10:00:00Z"), TrialPolicy.endOf(millis("2026-08-31T10:00:00Z")))
    }

    @Test
    fun should_stop_additions_at_the_very_moment_the_trial_ends() {
        val start = millis("2026-11-07T10:00:00Z")
        val end = TrialPolicy.endOf(start)
        assertTrue(TrialPolicy.canAddItems(purchased = false, nowMillis = end - 1, startMillis = start))
        assertFalse(TrialPolicy.canAddItems(purchased = false, nowMillis = end, startMillis = start))
        assertTrue(TrialPolicy.canAddItems(purchased = true, nowMillis = end, startMillis = start))
    }
}
