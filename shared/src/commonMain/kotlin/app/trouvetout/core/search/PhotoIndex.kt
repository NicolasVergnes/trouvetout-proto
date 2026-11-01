package app.trouvetout.core.search

import app.trouvetout.core.capture.Lang
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.log.LogLevel
import app.trouvetout.core.log.Logger
import app.trouvetout.core.ml.LabelCatalog
import app.trouvetout.core.ml.ModelRuntime
import app.trouvetout.core.platform.CoroutineDispatchers
import app.trouvetout.core.platform.ImageCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A photo attached to an active item, as the photo index needs it. */
internal class ItemPhoto(
    val photoId: String,
    val itemId: String,
    val fileName: String,
    val sha256: String,
)

/** How the search reads stored photos: the platform codec and the photo store. */
internal class PhotoFiles(
    val codec: ImageCodec,
    val read: suspend (fileName: String) -> ByteArray?,
)

/** A name proposed for a photographed object (B8); the user chooses, nothing is named silently. */
public data class NameSuggestion(
    val key: String,
    val name: String,
)

/**
 * The photo side of the search (B8, C10, ADR-022): one vector per photo of an item, computed in the foreground
 * with the text+vision model, and the bundled label catalog. Photos are given to the model as a JPEG re-encoded by
 * the platform codec (orientation applied, no metadata, at most [IMAGE_SIDE] pixels).
 */
internal class PhotoIndex(
    private val db: TrouveToutDb,
    private val runtime: ModelRuntime,
    private val tuning: SearchTuning,
    private val codec: ImageCodec,
    private val readPhoto: suspend (fileName: String) -> ByteArray?,
    private val dispatchers: CoroutineDispatchers,
    private val logger: Logger,
) {
    private val lock = Mutex()
    private val vectors = SemanticIndex(tuning.dimension)
    private var ownerOf: Map<String, String> = emptyMap()
    private var catalog: LabelCatalog? = null
    private var catalogLoaded = false
    private val labelIndex = SemanticIndex(tuning.dimension)

    /** `photoId:sha256` of the photos that cannot be read or decoded: not retried in this process. */
    private val unusable = HashSet<String>()

    private fun key(): String? = runtime.imageModelId?.let { "$it/${tuning.dimension}/$IMAGE_KEY_VERSION" }

    /**
     * Brings the photo vectors in line with [photos]; [progress] reports `done / total` photos to embed. False when
     * the image model cannot be used.
     */
    suspend fun index(
        photos: List<ItemPhoto>,
        progress: (done: Int, total: Int) -> Unit,
    ): Boolean {
        val key = key() ?: return false
        val stored =
            withContext(dispatchers.io) {
                db.embeddingQueries.hashes(OWNER_PHOTO, key).executeAsList().associate { it.owner_id to it.text_hash }
            }
        // A photo detached or purged takes its vector with it, also while photos are off (text only, load failure).
        val keep = photos.map { it.photoId }.toSet()
        withContext(dispatchers.io) {
            db.transaction { (stored.keys - keep).forEach { db.embeddingQueries.deleteOne(OWNER_PHOTO, it, key) } }
        }
        if (!runtime.imagesActive) return false
        loadStored(key, photos, stored)
        // Stored vectors answer at once, before the missing ones are computed.
        val inMemory =
            lock.withLock {
                ownerOf = photos.associate { it.photoId to it.itemId }
                photos.map { it.photoId }.filterTo(HashSet()) { it in vectors }
            }
        val missing =
            photos.filter {
                (stored[it.photoId] != it.sha256 || it.photoId !in inMemory) &&
                    "${it.photoId}:${it.sha256}" !in unusable
            }
        var done = 0
        if (missing.isNotEmpty()) progress(0, missing.size)
        for (batch in missing.chunked(BATCH)) {
            val images = batch.map { readPrepared(it) }
            val results = embedImages(images) ?: return false
            store(key, batch.zip(results))
            done += batch.size
            progress(done, missing.size)
        }
        withContext(dispatchers.io) { db.embeddingQueries.deleteOtherModelsOf(OWNER_PHOTO, key) }
        lock.withLock { vectors.retain(keep) }
        return true
    }

    /** The model's input for a stored photo; null (remembered for this process) when it cannot be read or decoded. */
    private suspend fun readPrepared(photo: ItemPhoto): ByteArray? {
        val image =
            try {
                prepare(readPhoto(photo.fileName))
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.log(LogLevel.WARN, TAG, "photo unreadable: ${e::class.simpleName}")
                null
            }
        if (image == null) lock.withLock { unusable += "${photo.photoId}:${photo.sha256}" }
        return image
    }

    /** The vector of a photo taken now (never stored); null when photos are not active or the image is unusable. */
    suspend fun embedQuery(photo: ByteArray): FloatArray? {
        if (!runtime.imagesActive) return null
        val image = prepare(photo) ?: return null
        return embedImages(listOf(image))?.single()
    }

    /** Items whose photos look like [query], best photo per item, best first. */
    suspend fun nearestItems(
        query: FloatArray,
        allowed: Set<String>,
        k: Int,
    ): List<Pair<String, Float>> = lock.withLock {
        val best = LinkedHashMap<String, Float>()
        vectors.nearest(query, vectors.size) { ownerOf[it] in allowed }.forEach { (photoId, cosine) ->
            ownerOf[photoId]?.let { item -> if (item !in best) best[item] = cosine }
        }
        best.entries.take(k).map { it.key to it.value }
    }

    /** The [limit] labels closest to [query] in the user's language, above the floor (B8). */
    suspend fun suggest(
        query: FloatArray,
        lang: Lang,
        limit: Int,
    ): List<NameSuggestion> {
        val labels = labels() ?: return emptyList()
        val byKey = labels.labels.associateBy { it.key }
        return lock.withLock { labelIndex.nearest(query, limit) }
            .filter { it.second >= tuning.labelFloor }
            .mapNotNull { (key, _) -> byKey[key]?.let { NameSuggestion(key, if (lang == Lang.FR) it.fr else it.en) } }
    }

    /** The bundled catalog, if it was computed with the running text model and size (else no names, logged). */
    private suspend fun labels(): LabelCatalog? = lock.withLock {
        if (!catalogLoaded) {
            // Marked loaded only once read: a naming cancelled during the first read tries again next time.
            catalog =
                withContext(dispatchers.io) { readCatalog() }
                    ?.takeIf { it.header.modelId == runtime.modelId && it.header.dim == tuning.dimension }
            catalogLoaded = true
            val loaded = catalog
            if (loaded == null) {
                logger.log(LogLevel.WARN, TAG, "label catalog missing or for another model")
            } else {
                loaded.labels.forEachIndexed { i, label -> labelIndex.put(label.key, loaded.vector(i)) }
            }
        }
        catalog
    }

    private fun readCatalog(): LabelCatalog? = try {
        runtime.labelCatalog()?.let(LabelCatalog::decode)
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        logger.log(LogLevel.WARN, TAG, "label catalog unreadable: ${e::class.simpleName}")
        null
    }

    /** Oriented, metadata-free, bounded JPEG for the model; null when the bytes are not an image. */
    private suspend fun prepare(bytes: ByteArray?): ByteArray? {
        bytes ?: return null
        return withContext(dispatchers.default) { codec.transcodeJpeg(bytes, IMAGE_SIDE, IMAGE_QUALITY)?.bytes }
    }

    /** One vector (or null) per image; null altogether when the model is unusable. A failure is logged by class. */
    private suspend fun embedImages(images: List<ByteArray?>): List<FloatArray?>? {
        val present = images.filterNotNull()
        val computed: List<FloatArray?>? =
            if (present.isEmpty()) {
                emptyList()
            } else {
                try {
                    runtime.withImageModel { model ->
                        model.embedImages(present, tuning.dimension).also {
                            check(it.size == present.size) { "vector count" }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    logger.log(LogLevel.WARN, TAG, "image embedding failed: ${e::class.simpleName}")
                    present.map { null }
                }
            }
        computed ?: return null
        val iterator = computed.iterator()
        return images.map { image ->
            image?.let { iterator.next()?.takeIf { v -> v.size == tuning.dimension && v.all { x -> x.isFinite() } } }
        }
    }

    private suspend fun store(
        key: String,
        results: List<Pair<ItemPhoto, FloatArray?>>,
    ) {
        withContext(dispatchers.io) {
            db.transaction {
                for ((photo, vector) in results) {
                    if (vector == null) {
                        db.embeddingQueries.deleteOne(OWNER_PHOTO, photo.photoId, key)
                    } else {
                        db.embeddingQueries.upsert(
                            OWNER_PHOTO,
                            photo.photoId,
                            key,
                            tuning.dimension.toLong(),
                            Float16.encode(vector),
                            photo.sha256,
                        )
                    }
                }
            }
        }
        lock.withLock {
            for ((photo, vector) in results) {
                if (vector == null) vectors.remove(photo.photoId) else vectors.put(photo.photoId, vector)
            }
        }
    }

    private suspend fun loadStored(
        key: String,
        photos: List<ItemPhoto>,
        stored: Map<String, String>,
    ) {
        if (lock.withLock { vectors.size } > 0) return
        val current = photos.associate { it.photoId to it.sha256 }
        val dim = tuning.dimension.toLong()
        val rows = withContext(dispatchers.io) { db.embeddingQueries.vectors(OWNER_PHOTO, key, dim).executeAsList() }
        lock.withLock {
            for (row in rows) {
                val vector = Float16.decode(row.vector)
                val valid = vector.size == tuning.dimension && vector.all { it.isFinite() }
                if (valid && stored[row.owner_id] == current[row.owner_id]) vectors.put(row.owner_id, vector)
            }
        }
    }

    companion object {
        const val OWNER_PHOTO = "photo"
        private const val TAG = "PhotoIndex"
        private const val BATCH = 2

        /** The model resizes to its 70-token grid anyway; this bounds the decode cost of a 12-megapixel photo. */
        const val IMAGE_SIDE = 768
        private const val IMAGE_QUALITY = 85
        private const val IMAGE_KEY_VERSION = "i1"
    }
}
