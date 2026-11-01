package app.trouvetout.core.privacy

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.openTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTest {
    private val db = openTestDatabase()
    private val clock = TickingClock()

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
        val lock = AppLock(settings(), clock)
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
        val lock = AppLock(settings, clock)
        assertTrue(lock.locked.value, "a new process starts locked")
        lock.unlocked()
        assertFalse(lock.locked.value)
    }

    @Test
    fun the_delay_decides_whether_coming_back_locks_H3() = runTest {
        val settings = settings()
        settings.setLockEnabled(true)
        settings.setLockDelay(LockDelay.ONE_MINUTE)
        val lock = AppLock(settings, clock)
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
    fun a_foreground_without_a_background_does_not_lock() = runTest {
        val settings = settings()
        settings.setLockEnabled(true)
        val lock = AppLock(settings, clock)
        lock.unlocked()
        lock.foregrounded()
        assertFalse(lock.locked.value, "a second activity start is not a return")
    }

    @Test
    fun turning_the_lock_on_does_not_lock_the_user_out_and_off_unlocks() = runTest {
        val settings = settings()
        val lock = AppLock(settings, clock)
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
        val lock = AppLock(settings, clock)
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
