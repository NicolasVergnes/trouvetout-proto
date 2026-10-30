package app.trouvetout.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechPolicyTest {
    @Test
    fun should_never_fall_back_to_a_server_B2() {
        assertEquals(SpeechAvailability.ON_DEVICE, SpeechPolicy.decide(true, true, false))
        assertEquals(SpeechAvailability.UNAVAILABLE, SpeechPolicy.decide(false, true, false), "online only: text")
        assertEquals(SpeechAvailability.UNAVAILABLE, SpeechPolicy.decide(true, false, false), "no recogniser at all")
        assertEquals(SpeechAvailability.LEGACY_PREFER_OFFLINE, SpeechPolicy.decide(null, true, false), "Android 10–11")
        assertEquals(SpeechAvailability.UNAVAILABLE, SpeechPolicy.decide(null, true, true), "offline run failed once")
    }
}
