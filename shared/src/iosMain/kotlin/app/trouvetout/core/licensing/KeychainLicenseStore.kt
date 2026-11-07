package app.trouvetout.core.licensing

import app.trouvetout.core.data.db.KeychainItem
import app.trouvetout.core.platform.toByteArray
import app.trouvetout.core.platform.toNSData

/**
 * The iOS store: its own Keychain item, which the total wipe does not remove and a reinstall normally keeps. A Keychain
 * that cannot answer (before the first unlock) gives « no date » and is never written over; a write it refuses puts the
 * previous date back.
 */
public class KeychainLicenseStore(
    private val item: KeychainItem,
) : LicenseStore {
    override fun trialStartMillis(): Long? = item.read().data?.toByteArray()?.decodeToString()?.toLongOrNull()

    override fun setTrialStartMillis(millis: Long) {
        val current = item.read()
        if (current.failed) return
        item.remove()
        val written = item.write(millis.toString().encodeToByteArray().toNSData())
        if (!written) current.data?.let { item.write(it) }
    }
}
