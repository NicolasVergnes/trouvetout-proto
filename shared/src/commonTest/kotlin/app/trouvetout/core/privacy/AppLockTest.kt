package app.trouvetout.core.privacy

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.openTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class AppLockTest {
    private val db = openTestDatabase()
    private val clock = TickingClock()
    private val monotonic = TestTimeSource()

    private fun settings() = PrivacySettings(db, Dispatchers.Unconfined)

    @Test
    fun the_settings_are_off_by_default_and_survive_a_relaunch() = runTest {
        val first = settings()
        assertEquals(PrivacyState(), first.state.value)
        first.setLockEnabled(true)
        first.setLockDelay(LockDelay.ONE_MINUTE)
        first.setNoKeyboardLearning(true)
        assertEquals(PrivacyState(true, LockDelay.ONE_MINUTE, true), settings().state.value)
    }

    @Test
    fun without_the_lock_nothing_is_ever_locked() = runTest {
        val lock = AppLock(settings(), clock, monotonic)
        assertFalse(lock.locked.value)
        lock.backgrounded()
        clock.millis += 3_600_000
        lock.foregrounded()
        assertFalse(lock.locked.value)
        assertFalse(lock.revealNeedsAuthentication)
    }

    @Test
    fun a_launch_with_the_lock_on_starts_locked_and_unlocking_opens_it() = runTest {
        val settings = settings()
        settings.setLockEnabled(true)
        val lock = AppLock(settings, clock, monotonic)
        assertTrue(lock.locked.value, "a new process starts locked")
        lock.unlocked()
        assertFalse(lock.locked.value)
    }

    @Test
    fun the_delay_decides_whether_coming_back_locks_H3() = runTest {
        val settings = settings()
        settings.setLockEnabled(true)
        settings.setLockDelay(LockDelay.ONE_MINUTE)
        val lock = AppLock(settings, clock, monotonic)
        lock.unlocked()

        lock.backgrounded()
        clock.millis += 30_000
        lock.foregrounded()
        assertFalse(lock.locked.value, "back within the delay")

        lock.backgrounded()
        clock.millis += 60_000
        lock.foregrounded()
        assertTrue(lock.locked.value, "back after the delay")

        lock.unlocked()
        settings.setLockDelay(LockDelay.IMMEDIATE)
        lock.backgrounded()
        lock.foregrounded()
        assertTrue(lock.locked.value, "immediately")
    }

    @Test
    fun a_clock_set_back_or_a_deep_sleep_cannot_shorten_the_time_away() = runTest {
        val settings = settings()
        settings.setLockEnabled(true)
        settings.setLockDelay(LockDelay.FIVE_MINUTES)
        val lock = AppLock(settings, clock, monotonic)
        lock.unlocked()

        lock.backgrounded()
        monotonic += 400.seconds
        clock.millis -= 1_000
        lock.foregrounded()
        assertTrue(lock.locked.value, "a wall clock set back locks")

        lock.unlocked()
        lock.backgrounded()
        clock.millis += 600_000 // deep sleep: the monotonic clock did not move
        lock.foregrounded()
        assertTrue(lock.locked.value, "the wall clock counts the sleep")

        lock.unlocked()
        lock.backgrounded()
        monotonic += 301.seconds
        lock.foregrounded()
        assertTrue(lock.locked.value, "the monotonic clock counts when the wall clock lags")
    }

    @Test
    fun a_photo_taken_in_the_camera_app_does_not_lock_but_a_long_stay_does() = runTest {
        val settings = settings()
        settings.setLockEnabled(true)
        val lock = AppLock(settings, clock, monotonic)
        lock.unlocked()

        lock.expectReturn()
        lock.backgrounded()
        clock.millis += 20_000
        lock.foregrounded()
        assertFalse(lock.locked.value, "back from the camera app")

        lock.backgrounded()
        lock.foregrounded()
        assertTrue(lock.locked.value, "the grace is for one return only")

        lock.unlocked()
        lock.expectReturn()
        lock.backgrounded()
        clock.millis += 61_000
        lock.foregrounded()
        assertTrue(lock.locked.value, "a long stay elsewhere locks")

        lock.unlocked()
        lock.expectReturn() // a translucent picker: the app is only paused, never sent away
        monotonic += 30.seconds
        lock.backgrounded() // later, a real departure
        clock.millis += 5_000
        lock.foregrounded()
        assertTrue(lock.locked.value, "an old announcement gives no grace")
    }

    @Test
    fun the_prompt_activity_of_android_10_does_not_lock_again_nor_hide_what_was_asked() = runTest {
        val settings = settings()
        settings.setLockEnabled(true)
        val lock = AppLock(settings, clock, monotonic)
        lock.unlocked()

        lock.authenticationStarted()
        lock.backgrounded() // the device-code activity covers the app
        lock.revealed()
        lock.foregrounded()
        lock.authenticationEnded(succeeded = true)
        assertFalse(lock.locked.value)
        assertTrue(lock.sensitiveRevealed.value, "the reveal survives its own prompt")

        lock.backgrounded()
        lock.foregrounded()
        assertTrue(lock.locked.value, "a real departure still counts")
    }

    @Test
    fun a_prompt_cancelled_or_left_counts_as_a_departure_and_cannot_hold_the_lock_forever() = runTest {
        val settings = settings()
        settings.setLockEnabled(true)
        val lock = AppLock(settings, clock, monotonic)
        lock.unlocked()

        lock.authenticationStarted()
        lock.backgrounded()
        lock.foregrounded()
        lock.authenticationEnded(succeeded = false)
        assertTrue(lock.locked.value, "cancelled: the departure counts")

        lock.unlocked()
        lock.authenticationStarted()
        lock.backgrounded()
        lock.authenticationEnded(succeeded = false) // the user went home during the prompt
        assertFalse(lock.locked.value)
        lock.foregrounded()
        assertTrue(lock.locked.value, "decided at the return")

        lock.unlocked()
        lock.authenticationStarted() // a prompt that never answers
        lock.backgrounded()
        monotonic += 121.seconds
        lock.foregrounded()
        assertTrue(lock.locked.value, "the window closes by itself")

        lock.unlocked()
        lock.authenticationStarted() // the phone sleeps with the prompt shown: only the wall clock moves
        lock.backgrounded()
        clock.millis += 121_000
        lock.foregrounded()
        assertTrue(lock.locked.value, "deep sleep does not keep the window open")
    }

    @Test
    fun a_foreground_without_a_background_does_not_lock() = runTest {
        val settings = settings()
        settings.setLockEnabled(true)
        val lock = AppLock(settings, clock, monotonic)
        lock.unlocked()
        lock.foregrounded()
        assertFalse(lock.locked.value, "a second activity start is not a return")
    }

    @Test
    fun turning_the_lock_on_does_not_lock_the_user_out_and_off_unlocks() = runTest {
        val settings = settings()
        val lock = AppLock(settings, clock, monotonic)
        settings.setLockEnabled(true)
        assertFalse(lock.locked.value)
        lock.backgrounded()
        lock.foregrounded()
        assertTrue(lock.locked.value)
        settings.setLockEnabled(false)
        assertFalse(lock.locked.value, "turning the lock off opens the app")
    }

    @Test
    fun sensitive_items_need_authentication_only_with_the_lock_until_the_app_leaves_H4() = runTest {
        val settings = settings()
        val lock = AppLock(settings, clock, monotonic)
        assertFalse(lock.revealNeedsAuthentication)
        settings.setLockEnabled(true)
        lock.unlocked()
        assertTrue(lock.revealNeedsAuthentication, "the app lock does not reveal sensitive items by itself")
        lock.revealed()
        assertFalse(lock.revealNeedsAuthentication)
        assertTrue(lock.sensitiveRevealed.value)
        lock.backgrounded()
        assertTrue(lock.revealNeedsAuthentication, "leaving the app hides them again")
        assertFalse(lock.sensitiveRevealed.value)
    }
}
