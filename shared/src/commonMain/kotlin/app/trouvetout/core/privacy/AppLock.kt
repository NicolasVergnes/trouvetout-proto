package app.trouvetout.core.privacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * When the app must ask for the device owner's authentication (H3), and whether sensitive items may be shown
 * (H4). Pure state, one per process: the hosts report the app going to the background and coming back, run the
 * system authentication (biometrics with the device code as fallback) and report its success.
 *
 * The time away is the longer of the wall clock and a monotonic clock: the monotonic clock stops in deep sleep
 * on both platforms, the wall clock can be set back by whoever holds the unlocked phone; a wall clock that went
 * backwards locks at once.
 */
public class AppLock internal constructor(
    private val settings: PrivacySettings,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val lockedFlow = MutableStateFlow(settings.state.value.lockEnabled)
    private val revealedFlow = MutableStateFlow(false)
    private var leftAt: Pair<Instant, TimeMark>? = null
    private var returnExpected = false
    private var authenticationMark: Pair<Instant, TimeMark>? = null
    private var inFront = true

    init {
        settings.onLockChanged = { enabled ->
            // Turning it on: the user is here. Turning it off: nothing stays locked.
            if (!enabled) lockedFlow.value = false
        }
    }

    /** The content is covered until [unlocked]. */
    public val locked: StateFlow<Boolean> = lockedFlow.asStateFlow()

    /** Sensitive items are shown in lists and answers (after an authentication, until the app leaves). */
    public val sensitiveRevealed: StateFlow<Boolean> = revealedFlow.asStateFlow()

    /** Showing sensitive items asks for the device owner first: only when the lock is on (H4). */
    public val revealNeedsAuthentication: Boolean
        get() = settings.state.value.lockEnabled && !revealedFlow.value

    /**
     * The app is about to open a system screen that sends it to the background (Android's camera app, the share
     * sheet, a file picker): coming back from it waits at least [EXPECTED_RETURN_GRACE] before locking, so that a
     * photo taken with the « immediately » delay does not ask for the device owner every time.
     */
    public fun expectReturn() {
        returnExpected = true
    }

    /**
     * The system prompt is shown. On Android 10 the device-code prompt is an activity of its own, which stops
     * the app's: a return during the prompt is decided when it ends ([authenticationEnded]), or the app would lock
     * again behind the prompt and hide what the user just asked to see. The window closes by itself after
     * [AUTHENTICATION_WINDOW] (a prompt that never answers cannot disable the lock).
     */
    public fun authenticationStarted() {
        authenticationMark = clock.now() to timeSource.markNow()
    }

    /**
     * The prompt answered. Succeeded: the departure was the prompt's own activity and is forgotten. Failed or
     * cancelled: the departure counts as any other, now if the app is in front, else at its return.
     */
    public fun authenticationEnded(succeeded: Boolean) {
        authenticationMark = null
        if (succeeded) {
            leftAt = null
            returnExpected = false
        } else if (inFront) {
            decideReturn()
        }
    }

    /** The app left the foreground: the clock of the delay starts, sensitive items are hidden again. */
    public fun backgrounded() {
        inFront = false
        leftAt = clock.now() to timeSource.markNow()
        revealedFlow.value = false
    }

    /** The app came back: it locks when the lock is on and the delay has passed. */
    public fun foregrounded() {
        inFront = true
        if (!authenticating) decideReturn()
    }

    /** Closed after the window on either clock (the monotonic one stops in deep sleep), or by a clock set back. */
    private val authenticating: Boolean
        get() {
            val (wallStart, monotonicStart) = authenticationMark ?: return false
            val wall = clock.now() - wallStart
            return wall >= Duration.ZERO &&
                wall < AUTHENTICATION_WINDOW &&
                monotonicStart.elapsedNow() < AUTHENTICATION_WINDOW
        }

    private fun decideReturn() {
        val expected = returnExpected
        returnExpected = false
        val (wallLeft, monotonicLeft) = leftAt ?: return
        leftAt = null
        val state = settings.state.value
        if (!state.lockEnabled) return
        val wall = clock.now() - wallLeft
        val away = maxOf(wall, monotonicLeft.elapsedNow())
        val delay = state.lockDelay.seconds.seconds.let { if (expected) maxOf(it, EXPECTED_RETURN_GRACE) else it }
        if (wall < Duration.ZERO || away >= delay) {
            lockedFlow.value = true
        }
    }

    /** The system authentication succeeded. */
    public fun unlocked() {
        lockedFlow.value = false
    }

    /** The user asked to see the sensitive items (and authenticated when [revealNeedsAuthentication]). */
    public fun revealed() {
        revealedFlow.value = true
    }

    public companion object {
        /** The longest stay in the camera app or the share sheet that does not lock (« immediately » delay). */
        public val EXPECTED_RETURN_GRACE: Duration = 60.seconds

        /** The longest a system prompt may hold back the lock decision. */
        public val AUTHENTICATION_WINDOW: Duration = 120.seconds
    }
}
