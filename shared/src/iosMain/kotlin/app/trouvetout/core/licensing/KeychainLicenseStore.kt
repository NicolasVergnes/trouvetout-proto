package app.trouvetout.core.licensing

import app.trouvetout.core.data.db.KeychainItem
import app.trouvetout.core.platform.toByteArray
import app.trouvetout.core.platform.toNSData

/**
 * The iOS store: its own Keychain item, which the total wipe does not remove and a reinstall normally keeps. A Keychain
 * that cannot answer (before the first unlock) gives « no date » and is never written over, so a date cannot be lost.
 */
public class KeychainLicenseStore(
    private val item: KeychainItem,
) : LicenseStore {
    override fun trialStartMillis(): Long? = item.read().data?.toByteArray()?.decodeToString()?.toLongOrNull()

    override fun setTrialStartMillis(millis: Long) {
        if (item.read().failed) return
        item.remove()
        item.write(millis.toString().encodeToByteArray().toNSData())
    }
}
