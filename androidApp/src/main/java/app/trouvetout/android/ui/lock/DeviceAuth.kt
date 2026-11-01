package app.trouvetout.android.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * The device owner's authentication (H3, H4) through the framework's `BiometricPrompt` (API 28+, no extra library):
 * fingerprint or face when enrolled, the device PIN, pattern or password otherwise. The app has no password of its
 * own: nothing to forget, nothing to recover without a server.
 */
object DeviceAuth {
    /** A screen lock is set: without one there is nothing to ask, and the app lock cannot be turned on. */
    fun available(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Shows the system prompt; [onResult] gets true once the owner is recognised, false when it is dismissed. */
    fun authenticate(
        context: Context,
        title: String,
        onResult: (Boolean) -> Unit,
    ) {
        if (!available(context)) {
            onResult(false)
            return
        }
        val builder = BiometricPrompt.Builder(context).setTitle(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION") // the only way on API 29 to offer the device code next to the biometrics
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onResult(true)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence?,
                ) {
                    onResult(false)
                }
            },
        )
    }
}
