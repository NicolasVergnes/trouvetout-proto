package app.trouvetout.core.capture

/** How the host may listen (B2): on the device, or not at all — never through a server. */
public enum class SpeechAvailability {
    /** The platform guarantees on-device recognition (Android 12+, iOS `supportsOnDeviceRecognition`). */
    ON_DEVICE,

    /**
     * A recogniser exists but nothing guarantees it stays on the device (Android 10–11 only take a « prefer
     * offline » hint; iOS without an on-device model for the language): the host offers text and says why.
     */
    NOT_ON_DEVICE,

    /** No recogniser at all (some emulators, devices without a speech service): the host offers text. */
    NO_RECOGNIZER,
}

/** Pure decision shared by both hosts; the platform supplies the facts. */
public object SpeechPolicy {
    /**
     * @param onDeviceSupported the platform's own answer; null when it cannot tell (Android 10–11), which is
     *   never taken as a yes.
     * @param recognizerInstalled false when no speech service exists at all.
     */
    public fun decide(
        onDeviceSupported: Boolean?,
        recognizerInstalled: Boolean,
    ): SpeechAvailability = when {
        !recognizerInstalled -> SpeechAvailability.NO_RECOGNIZER
        onDeviceSupported == true -> SpeechAvailability.ON_DEVICE
        else -> SpeechAvailability.NOT_ON_DEVICE
    }
}
