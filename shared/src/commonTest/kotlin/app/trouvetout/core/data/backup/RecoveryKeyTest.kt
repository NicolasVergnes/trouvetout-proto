package app.trouvetout.core.data.backup

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryKeyTest {
    private val key = Random(11).nextBytes(RecoveryKey.KEY_BYTES)

    @Test
    fun should_read_back_what_it_wrote_with_any_key() {
        repeat(200) { seed ->
            val random = Random(seed).nextBytes(RecoveryKey.KEY_BYTES)
            assertContentEquals(random, RecoveryKey.decode(RecoveryKey.encode(random)))
        }
    }

    @Test
    fun should_write_seven_groups_of_four_to_copy_by_hand() {
        val text = RecoveryKey.encode(key)
        val groups = text.split('-')
        assertEquals(7, groups.size)
        assertTrue(groups.all { it.length == 4 }, text)
        assertTrue(text.none { it in "ILOU" }, "no letter that reads like another: $text")
    }

    @Test
    fun should_forgive_case_separators_and_look_alike_letters() {
        val text = RecoveryKey.encode(key)
        assertContentEquals(key, RecoveryKey.decode(text.lowercase()))
        assertContentEquals(key, RecoveryKey.decode(" " + text.replace('-', ' ') + "\n"))
        assertContentEquals(key, RecoveryKey.decode(text.replace("-", "")))
        val allZero = RecoveryKey.encode(ByteArray(RecoveryKey.KEY_BYTES))
        assertContentEquals(ByteArray(RecoveryKey.KEY_BYTES), RecoveryKey.decode(allZero.replace('0', 'o')))
    }

    @Test
    fun should_tell_a_typing_mistake() {
        val text = RecoveryKey.encode(key)
        val mistaken = (if (text[0] == 'A') 'B' else 'A') + text.drop(1)
        assertNull(RecoveryKey.decode(mistaken))
        assertNull(RecoveryKey.decode(text.dropLast(1)))
        assertNull(RecoveryKey.decode(text + "A"))
        assertNull(RecoveryKey.decode(""))
        assertNull(RecoveryKey.decode(text.replaceFirstChar { 'U' }), "out of the alphabet")
    }
}

class HkdfTest {
    private fun hex(text: String) = text.hexToByteArray()

    /** RFC 5869, test case 1. */
    @Test
    fun should_match_the_rfc_vector() {
        val derived =
            Hkdf.derive(
                keyMaterial = ByteArray(22) { 0x0b },
                salt = hex("000102030405060708090a0b0c"),
                info = hex("f0f1f2f3f4f5f6f7f8f9"),
                length = 42,
            )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            derived.toHexString(),
        )
    }

    /** RFC 4231, test case 6: a key longer than the block. */
    @Test
    fun should_match_the_hmac_vector_with_a_long_key() {
        val mac =
            Hkdf.hmacSha256(
                ByteArray(131) { 0xaa.toByte() },
                "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
            )
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54", mac.toHexString())
    }
}
