package app.trouvetout.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechPolicyTest {
    @Test
    fun should_never_fall_back_to_a_server_B2() {
        assertEquals(SpeechAvailability.ON_DEVICE, SpeechPolicy.decide(true, true))
        assertEquals(SpeechAvailability.NOT_ON_DEVICE, SpeechPolicy.decide(false, true), "online only: text")
        assertEquals(SpeechAvailability.NOT_ON_DEVICE, SpeechPolicy.decide(null, true), "Android 10–11: a hint only")
        assertEquals(SpeechAvailability.NO_RECOGNIZER, SpeechPolicy.decide(true, false), "no recogniser at all")
        assertEquals(SpeechAvailability.NO_RECOGNIZER, SpeechPolicy.decide(null, false))
    }
}
