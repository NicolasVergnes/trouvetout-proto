package app.trouvetout.core.data.db

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database key wrapped by an AES-256-GCM key of the Android Keystore (H2, ADR-023): the wrapping key never
 * leaves the Keystore (StrongBox when the phone has one), needs no user authentication (the database opens for the
 * widget and the tile too), and the wrapped key lives in `<noBackupFilesDir>/keys/db.key` (IV then ciphertext),
 * outside every backup. Without the Keystore entry the file is useless.
 */
public class AndroidDatabaseKeys(
    context: Context,
    directory: File,
    /** The Keystore entry; tests use their own so as never to touch the app's. */
    private val alias: String = ALIAS,
) : DatabaseKeyStore {
    private val appContext = context.applicationContext
    private val file = File(directory, FILE_NAME)

    override fun exists(): Boolean = file.isFile && keyStore().containsAlias(alias)

    override fun obtain(): ByteArray {
        if (file.isFile) return unwrap(file.readBytes())
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val wrapped = wrap(key)
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeBytes(wrapped)
        check(temp.renameTo(file)) { "key file not written" }
        return key
    }

    override fun delete() {
        file.delete()
        File(file.path + ".tmp").delete()
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun wrap(key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(create = true))
        return cipher.iv + cipher.doFinal(key)
    }

    private fun unwrap(stored: ByteArray): ByteArray {
        check(stored.size > IV_BYTES) { "key file truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = wrappingKey(create = false) ?: throw DatabaseKeyLostException()
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES))
        return cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
    }

    private fun wrappingKey(create: Boolean): SecretKey? {
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!create) return null
        val strongBox = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return if (strongBox) {
            try {
                generate(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
        } else {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BYTES * Byte.SIZE_BITS)
                .setUserAuthenticationRequired(false)
                .setIsStrongBoxBacked(strongBox)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    public companion object {
        /** Under `noBackupFilesDir`, next to `db/`. */
        public const val DIRECTORY: String = "keys"
        private const val FILE_NAME = "db.key"
        private const val ALIAS = "trouvetout.db.wrap"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
