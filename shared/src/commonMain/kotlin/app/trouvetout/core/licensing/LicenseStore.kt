package app.trouvetout.core.licensing

import app.trouvetout.core.platform.FileStore

/**
 * Where the start of the trial is kept, outside the database: the total wipe (H6) does not reset the trial, and the
 * user is told so (ADR-025). It holds one date, nothing about the user or their items.
 * Neither call throws: an unreadable store answers null and a refused write is dropped, so a storage problem never
 * stops anyone from adding an item.
 */
public interface LicenseStore {
    public fun trialStartMillis(): Long?

    public fun setTrialStartMillis(millis: Long)
}

/** The Android store: a file of the private directory, outside the directories the total wipe removes. */
public class FileLicenseStore(
    private val files: FileStore,
) : LicenseStore {
    override fun trialStartMillis(): Long? =
        runCatching { files.read(PATH)?.decodeToString()?.toLongOrNull() }.getOrNull()

    override fun setTrialStartMillis(millis: Long) {
        runCatching { files.write(PATH, millis.toString().encodeToByteArray()) }
    }

    public companion object {
        internal const val PATH: String = "licensing/trial_start"
    }
}
