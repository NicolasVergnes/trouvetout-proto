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

/** What the Keychain answered: the bytes, nothing (no item), or a failure that says nothing about the item. */
public class KeychainRead(
    public val data: NSData?,
    /** The Keychain could not answer (locked before the first unlock, entitlement…): not « no key ». */
    public val failed: Boolean,
)

/**
 * The Keychain item that holds the database key, implemented in Swift (`KeychainDatabaseKey`): a generic password,
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, never synchronised. Each call answers at once.
 */
public interface KeychainItem {
    public fun read(): KeychainRead

    /** Adds [data]; false when the Keychain refused or an item already exists (an existing key is never replaced). */
    public fun write(data: NSData): Boolean

    public fun remove()
}

/** The Keychain did not answer: the store is unavailable for now, the key is not lost. */
public class KeychainUnavailableException : IllegalStateException("keychain unavailable")

/** The database key (H2) in the iOS Keychain: random bytes from `SecRandomCopyBytes`, created on first use. */
@OptIn(ExperimentalForeignApi::class)
public class IosDatabaseKeys(
    private val item: KeychainItem,
) : DatabaseKeyStore {
    override fun exists(): Boolean = read() != null

    override fun obtain(): ByteArray {
        read()?.let { stored ->
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

    /** The stored key, or null when there is none; a Keychain that cannot answer is never taken for « no key ». */
    private fun read(): NSData? {
        val answer = item.read()
        if (answer.failed) throw KeychainUnavailableException()
        return answer.data
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
