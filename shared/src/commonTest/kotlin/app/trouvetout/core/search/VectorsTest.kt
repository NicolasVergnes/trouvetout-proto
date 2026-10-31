package app.trouvetout.core.search

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VectorsTest {
    @Test
    fun float16_round_trips_within_half_precision() {
        val values = floatArrayOf(0f, -0f, 1f, -1f, 0.5f, 0.1f, -0.0731f, 0.999f, 1e-5f, 6.1e-5f, 65504f, 3.14159f)
        val back = Float16.decode(Float16.encode(values))
        values.forEachIndexed { i, v ->
            val tolerance = maxOf(abs(v) * 1e-3f, 1e-7f)
            assertTrue(abs(back[i] - v) <= tolerance, "$v → ${back[i]}")
        }
        assertEquals(2 * values.size, Float16.encode(values).size)
    }

    @Test
    fun float16_never_produces_nan_for_unit_vectors() {
        val vector = FloatArray(256) { ((it * 37) % 101 - 50) / 400f }
        assertFalse(Float16.decode(Float16.encode(vector)).any { it.isNaN() })
    }

    @Test
    fun nearest_ranks_by_cosine_and_honours_filters() {
        val index = SemanticIndex(3)
        index.put("x", floatArrayOf(1f, 0f, 0f))
        index.put("y", floatArrayOf(0f, 1f, 0f))
        index.put("xy", floatArrayOf(0.7071f, 0.7071f, 0f))
        val query = floatArrayOf(0.98f, 0.199f, 0f)
        assertEquals(listOf("x", "xy", "y"), index.nearest(query, 3).map { it.first })
        assertEquals(listOf("xy", "y"), index.nearest(query, 3) { it != "x" }.map { it.first })
        index.put("x", floatArrayOf(0f, 0f, 1f))
        assertEquals("xy", index.nearest(query, 1).single().first, "a replaced vector is replaced")
        index.remove("xy")
        index.retain(setOf("y", "x"))
        assertEquals(2, index.size)
        assertEquals("y", index.nearest(query, 1).single().first)
    }

    @Test
    fun the_index_grows_past_its_initial_capacity() {
        val index = SemanticIndex(2)
        repeat(200) { index.put("i$it", floatArrayOf(it.toFloat(), 1f)) }
        assertEquals(200, index.size)
        assertEquals("i199", index.nearest(floatArrayOf(1f, 0f), 1).single().first)
    }
}
