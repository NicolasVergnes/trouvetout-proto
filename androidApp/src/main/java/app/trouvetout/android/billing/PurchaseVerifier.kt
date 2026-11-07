package app.trouvetout.android.billing

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Checks that Google Play signed a purchase with the app's key (ADR-007: no server, local check only; it stops the
 * trivial fake, which is proportionate to the price). No key or a bad one recognises nothing.
 */
class PurchaseVerifier(
    base64PublicKey: String,
) {
    private val key: PublicKey? =
        runCatching {
            KeyFactory
                .getInstance(KEY_ALGORITHM)
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64PublicKey)))
        }.getOrNull()

    fun isGenuine(
        purchaseJson: String,
        base64Signature: String,
    ): Boolean {
        val publicKey = key ?: return false
        return try {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(purchaseJson.toByteArray())
                verify(Base64.getDecoder().decode(base64Signature))
            }
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private companion object {
        const val KEY_ALGORITHM = "RSA"
        const val SIGNATURE_ALGORITHM = "SHA1withRSA" // the store's choice, not ours
    }
}
