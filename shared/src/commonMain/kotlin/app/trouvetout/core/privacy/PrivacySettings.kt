package app.trouvetout.core.privacy

import app.trouvetout.core.data.db.MetaKeys
import app.trouvetout.core.data.db.TrouveToutDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** How long the app may stay in the background before it locks again (H3). */
public enum class LockDelay(
    public val seconds: Long,
) {
    IMMEDIATE(0),
    ONE_MINUTE(60),
    FIVE_MINUTES(300),
}

/** The privacy settings of this device (H3, keyboard learning); all off by default. */
public data class PrivacyState(
    /** The app asks for the device owner's authentication (biometrics or device code) when it opens. */
    val lockEnabled: Boolean = false,
    val lockDelay: LockDelay = LockDelay.IMMEDIATE,
    /** Android: the keyboard must not learn what is typed in the capture and search fields. */
    val noKeyboardLearning: Boolean = false,
)

/** The privacy settings, kept in `meta` (local, never exported, erased by the total wipe). */
public class PrivacySettings internal constructor(
    private val db: TrouveToutDb,
    private val io: CoroutineDispatcher,
) {
    private val stateFlow =
        MutableStateFlow(
            PrivacyState(
                lockEnabled = flag(MetaKeys.APP_LOCK),
                lockDelay =
                    db.metaQueries
                        .get(MetaKeys.LOCK_DELAY)
                        .executeAsOneOrNull()
                        ?.let { stored -> LockDelay.entries.firstOrNull { it.name == stored } }
                        ?: LockDelay.IMMEDIATE,
                noKeyboardLearning = flag(MetaKeys.NO_KEYBOARD_LEARNING),
            ),
        )

    public val state: StateFlow<PrivacyState> = stateFlow.asStateFlow()

    /** Told when the lock is turned on or off (the app lock follows). */
    internal var onLockChanged: (Boolean) -> Unit = {}

    /** The hosts authenticate the device owner before turning the lock off. */
    public suspend fun setLockEnabled(value: Boolean) {
        withContext(io) { db.metaQueries.set(MetaKeys.APP_LOCK, value.toString()) }
        stateFlow.value = stateFlow.value.copy(lockEnabled = value)
        onLockChanged(value)
    }

    public suspend fun setLockDelay(value: LockDelay) {
        withContext(io) { db.metaQueries.set(MetaKeys.LOCK_DELAY, value.name) }
        stateFlow.value = stateFlow.value.copy(lockDelay = value)
    }

    public suspend fun setNoKeyboardLearning(value: Boolean) {
        withContext(io) { db.metaQueries.set(MetaKeys.NO_KEYBOARD_LEARNING, value.toString()) }
        stateFlow.value = stateFlow.value.copy(noKeyboardLearning = value)
    }

    private fun flag(key: String): Boolean =
        db.metaQueries.get(key).executeAsOneOrNull()?.toBooleanStrictOrNull() ?: false
}
