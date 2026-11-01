package app.trouvetout.core.ml

import app.trouvetout.core.search.Float16
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One household label (B8): a stable key and the name shown in each language. */
public data class Label(
    val key: String,
    val fr: String,
    val en: String,
)

/** What the vectors of a catalog were computed with; a mismatch with the running model makes the catalog unusable. */
@Serializable
public data class LabelCatalogHeader(
    val modelId: String,
    val dim: Int,
    /** How each label became a text before embedding, e.g. `{fr} / {en}`. */
    val template: String,
    /** SHA-256 of `models/labels_fr_en.tsv`, so that a stale catalog is detected in CI. */
    val sourceSha256: String,
    val count: Int,
)

/**
 * The ~2 000 household labels with their text vectors, computed once at build time by `tools` from the text
 * model (the text weights are identical on every device, ADR-022) and bundled with the app: naming a photo needs
 * no on-device pass over the labels.
 *
 * Binary format, little-endian: `TTLC`, version byte, header length (u32) and JSON header, then per label three
 * UTF-8 strings (u16 length each: key, fr, en), then `count × dim` float16 values.
 */
public class LabelCatalog(
    public val header: LabelCatalogHeader,
    public val labels: List<Label>,
    /** Row-major, one normalised vector of `header.dim` floats per label. */
    internal val vectors: FloatArray,
) {
    init {
        require(labels.size == header.count && vectors.size == header.count * header.dim) { "inconsistent catalog" }
    }

    public fun vector(index: Int): FloatArray = vectors.copyOfRange(index * header.dim, (index + 1) * header.dim)

    public fun encode(): ByteArray {
        val out = ByteBuilder()
        MAGIC.forEach { out.byte(it.code) }
        out.byte(VERSION)
        val json = Json.encodeToString(LabelCatalogHeader.serializer(), header).encodeToByteArray()
        out.int(json.size)
        out.bytes(json)
        for (label in labels) {
            out.string(label.key)
            out.string(label.fr)
            out.string(label.en)
        }
        out.bytes(Float16.encode(vectors))
        return out.toByteArray()
    }

    public companion object {
        private const val MAGIC = "TTLC"
        private const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }

        private const val MAX_HEADER_BYTES = 4096
        private const val MAX_LABELS = 10_000
        private const val MAX_DIM = 2048

        /**
         * Null when [bytes] is not a well-formed catalog of a known version (the hosts then offer no names). Sizes are
         * bounded before anything is allocated, the file must end exactly after the vectors, and every value must be
         * finite.
         */
        public fun decode(bytes: ByteArray): LabelCatalog? = runCatching {
            val input = ByteReader(bytes)
            check(MAGIC.all { input.byte() == it.code } && input.byte() == VERSION) { "not a catalog" }
            val headerLength = input.int()
            check(headerLength in 1..MAX_HEADER_BYTES) { "bad header" }
            val header = json.decodeFromString(
                LabelCatalogHeader.serializer(),
                input.bytes(headerLength).decodeToString(),
            )
            check(header.count in 1..MAX_LABELS && header.dim in 1..MAX_DIM) { "bad sizes" }
            val labels = ArrayList<Label>(header.count)
            repeat(header.count) { labels += Label(input.string(), input.string(), input.string()) }
            val vectors = Float16.decode(input.bytes(header.count * header.dim * 2))
            check(input.atEnd && vectors.all { it.isFinite() }) { "bad vectors" }
            LabelCatalog(header, labels, vectors)
        }.getOrNull()
    }
}

private class ByteBuilder {
    private var data = ByteArray(1 shl 16)
    private var size = 0

    fun byte(value: Int) {
        ensure(1)
        data[size++] = value.toByte()
    }

    fun int(value: Int) {
        for (shift in 0 until Int.SIZE_BITS step Byte.SIZE_BITS) byte(value ushr shift and BYTE_MASK)
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(data, size)
        size += value.size
    }

    fun string(value: String) {
        val utf8 = value.encodeToByteArray()
        require(utf8.size <= U16_MAX) { "label too long" }
        byte(utf8.size and BYTE_MASK)
        byte(utf8.size ushr Byte.SIZE_BITS and BYTE_MASK)
        bytes(utf8)
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }
}

private class ByteReader(
    private val data: ByteArray,
) {
    private var position = 0

    fun byte(): Int {
        check(position < data.size) { "truncated catalog" }
        return data[position++].toInt() and BYTE_MASK
    }

    fun int(): Int {
        var value = 0
        for (shift in 0 until Int.SIZE_BITS step Byte.SIZE_BITS) value = value or (byte() shl shift)
        return value
    }

    val atEnd: Boolean get() = position == data.size

    fun bytes(count: Int): ByteArray {
        check(count >= 0 && count <= data.size - position) { "truncated catalog" }
        return data.copyOfRange(position, position + count).also { position += count }
    }

    fun string(): String {
        val length = byte() or (byte() shl Byte.SIZE_BITS)
        return bytes(length).decodeToString()
    }
}

private const val BYTE_MASK = 0xFF
private const val U16_MAX = 0xFFFF
