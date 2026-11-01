package app.trouvetout.core.data.db

import app.trouvetout.core.platform.toByteArray
import app.trouvetout.core.platform.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/**
 * The Keychain item that holds the database key, implemented in Swift (`KeychainDatabaseKey`): a generic password,
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, never synchronised. Each call answers at once.
 */
public interface KeychainItem {
    /** The stored bytes; null when there is no item. */
    public fun read(): NSData?

    /** Stores [data] (adds or replaces); false when the Keychain refused. */
    public fun write(data: NSData): Boolean

    public fun remove()
}

/** The database key (H2) in the iOS Keychain: random bytes from `SecRandomCopyBytes`, created on first use. */
@OptIn(ExperimentalForeignApi::class)
public class IosDatabaseKeys(
    private val item: KeychainItem,
) : DatabaseKeyStore {
    override fun exists(): Boolean = item.read() != null

    override fun obtain(): ByteArray {
        item.read()?.let { stored ->
            val key = stored.toByteArray()
            check(key.size == KEY_BYTES) { "key item malformed" }
            return key
        }
        val key = ByteArray(KEY_BYTES)
        val status = key.usePinned { SecRandomCopyBytes(kSecRandomDefault, KEY_BYTES.toULong(), it.addressOf(0)) }
        check(status == errSecSuccess) { "no random bytes" }
        check(item.write(key.toNSData())) { "key not stored" }
        return key
    }

    override fun delete() {
        item.remove()
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
