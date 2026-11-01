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
    private var authenticating = false

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
     * the app's: until [authenticationEnded] the trip does not count, or the app would lock again behind the prompt
     * and hide what the user just asked to see.
     */
    public fun authenticationStarted() {
        authenticating = true
    }

    public fun authenticationEnded() {
        authenticating = false
    }

    /** The app left the foreground: the clock of the delay starts, sensitive items are hidden again. */
    public fun backgrounded() {
        if (authenticating) return
        leftAt = clock.now() to timeSource.markNow()
        revealedFlow.value = false
    }

    /** The app came back: it locks when the lock is on and the delay has passed. */
    public fun foregrounded() {
        if (authenticating) return
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
    }
}
