package app.trouvetout.core.search

/**
 * Embedding vectors of the items, contiguous in memory, searched by brute force (dot product of normalised
 * vectors = cosine): a few milliseconds for 10 000 vectors of 256 floats, no vector database (ADR-021).
 * Mutable so that indexing can feed it batch by batch; callers serialise access.
 */
internal class SemanticIndex(
    val dim: Int,
) {
    private val slots = HashMap<String, Int>()
    private var ids = arrayOfNulls<String>(INITIAL_CAPACITY)
    private var matrix = FloatArray(INITIAL_CAPACITY * dim)
    private var used = 0

    val size: Int get() = slots.size

    operator fun contains(id: String): Boolean = id in slots

    fun put(
        id: String,
        vector: FloatArray,
    ) {
        require(vector.size == dim) { "dimension ${vector.size} != $dim" }
        val slot = slots[id] ?: (used++).also {
            grow()
            slots[id] = it
        }
        ids[slot] = id
        vector.copyInto(matrix, slot * dim)
    }

    fun remove(id: String) {
        val slot = slots.remove(id) ?: return
        ids[slot] = null
    }

    /** Keeps only [keep]; vacated slots are compacted so that the scan stays dense. */
    fun retain(keep: Set<String>) {
        val survivors = slots.keys.filter { it in keep }.map { it to vector(it) }
        slots.clear()
        ids = arrayOfNulls(maxOf(INITIAL_CAPACITY, survivors.size))
        matrix = FloatArray(ids.size * dim)
        used = 0
        survivors.forEach { (id, vector) -> put(id, vector) }
    }

    private fun vector(id: String): FloatArray {
        val slot = slots.getValue(id)
        return matrix.copyOfRange(slot * dim, (slot + 1) * dim)
    }

    /** The [k] most similar ids among those [allowed] accepts, best first, with their cosine. */
    fun nearest(
        query: FloatArray,
        k: Int,
        allowed: (String) -> Boolean = { true },
    ): List<Pair<String, Float>> {
        require(query.size == dim) { "dimension ${query.size} != $dim" }
        val scored = ArrayList<Pair<String, Float>>(slots.size)
        for (slot in 0 until used) {
            val id = ids[slot]
            if (id != null && allowed(id)) scored += id to dot(query, slot)
        }
        scored.sortByDescending { it.second }
        return scored.take(k)
    }

    private fun dot(
        query: FloatArray,
        slot: Int,
    ): Float {
        var sum = 0f
        val base = slot * dim
        for (i in 0 until dim) sum += query[i] * matrix[base + i]
        return sum
    }

    private fun grow() {
        if (used <= ids.size) return
        val capacity = ids.size * 2
        ids = ids.copyOf(capacity)
        matrix = matrix.copyOf(capacity * dim)
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
    }
}
