package app.trouvetout.core.privacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * When the app must ask for the device owner's authentication (H3), and whether sensitive items may be shown
 * (H4). Pure state, one per process: the hosts report the app going to the background and coming back, run the
 * system authentication (biometrics with the device code as fallback) and report its success.
 */
public class AppLock internal constructor(
    private val settings: PrivacySettings,
    private val clock: Clock,
) {
    private val lockedFlow = MutableStateFlow(settings.state.value.lockEnabled)
    private val revealedFlow = MutableStateFlow(false)
    private var leftAt: Instant? = null

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

    /** The app left the foreground: the clock of the delay starts, sensitive items are hidden again. */
    public fun backgrounded() {
        leftAt = clock.now()
        revealedFlow.value = false
    }

    /** The app came back: it locks when the lock is on and the delay has passed. */
    public fun foregrounded() {
        val left = leftAt ?: return
        leftAt = null
        val state = settings.state.value
        if (state.lockEnabled && clock.now() - left >= state.lockDelay.seconds.seconds) {
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
}
