package app.trouvetout.core.capture

/** How the host may listen (B2): on the device, or not at all — never through a server. */
public enum class SpeechAvailability {
    /** The platform guarantees on-device recognition (Android 12+, iOS `supportsOnDeviceRecognition`). */
    ON_DEVICE,

    /**
     * Android 10–11: the recogniser only takes a « prefer offline » hint. Allowed until a run fails for lack of
     * network, after which the host remembers it and falls back to text.
     */
    LEGACY_PREFER_OFFLINE,

    /** No usable recogniser: the host offers text and says why. */
    UNAVAILABLE,
}

/** Pure decision shared by both hosts; the platform supplies the facts. */
public object SpeechPolicy {
    /**
     * @param onDeviceSupported the platform's own answer when it has one; null when it cannot tell (Android 10–11).
     * @param recognizerInstalled false when no speech service exists at all (some emulators, de-Googled devices).
     * @param legacyOfflineFailed true once a « prefer offline » run ended with a network error.
     */
    public fun decide(
        onDeviceSupported: Boolean?,
        recognizerInstalled: Boolean,
        legacyOfflineFailed: Boolean,
    ): SpeechAvailability = when {
        !recognizerInstalled -> SpeechAvailability.UNAVAILABLE
        onDeviceSupported == true -> SpeechAvailability.ON_DEVICE
        onDeviceSupported == false -> SpeechAvailability.UNAVAILABLE
        legacyOfflineFailed -> SpeechAvailability.UNAVAILABLE
        else -> SpeechAvailability.LEGACY_PREFER_OFFLINE
    }
}
