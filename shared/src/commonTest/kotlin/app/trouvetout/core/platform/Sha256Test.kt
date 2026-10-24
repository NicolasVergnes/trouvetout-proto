package app.trouvetout.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun should_match_known_vector_when_hashing_abc() {
        val digest = sha256("abc".encodeToByteArray()).toHexString()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
    }

    @Test
    fun should_match_known_vector_when_input_is_empty() {
        val digest = sha256(ByteArray(0)).toHexString()
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest)
    }
}
