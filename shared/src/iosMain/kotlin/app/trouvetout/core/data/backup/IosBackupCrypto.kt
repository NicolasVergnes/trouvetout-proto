package app.trouvetout.core.data.backup

import app.trouvetout.core.platform.toByteArray
import app.trouvetout.core.platform.toNSData
import platform.Foundation.NSData

/**
 * CryptoKit, which only Swift can call (`CryptoKitBackupCipher`). Sealed data is the ciphertext and the 16-byte tag.
 * Null means the system refused: for [open], that the data does not open.
 */
public interface NativeBackupCipher {
    public fun randomBytes(count: Int): NSData?

    public fun seal(
        key: NSData,
        nonce: NSData,
        aad: NSData,
        plain: NSData,
    ): NSData?

    public fun open(
        key: NSData,
        nonce: NSData,
        aad: NSData,
        sealed: NSData,
    ): NSData?
}

/** The iOS side of [BackupCrypto]; a refusal of the system fails the backup, it never writes anything weaker. */
public class IosBackupCrypto(
    private val native: NativeBackupCipher,
) : BackupCrypto {
    override fun randomBytes(count: Int): ByteArray =
        checkNotNull(native.randomBytes(count)) { "no random bytes" }.toByteArray()

    override fun seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plain: ByteArray,
    ): ByteArray =
        checkNotNull(native.seal(key.toNSData(), nonce.toNSData(), aad.toNSData(), plain.toNSData())) { "not sealed" }
            .toByteArray()

    override fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        sealed: ByteArray,
    ): ByteArray? = native.open(key.toNSData(), nonce.toNSData(), aad.toNSData(), sealed.toNSData())?.toByteArray()
}
