package app.trouvetout.core.data.photo

import app.trouvetout.core.platform.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoStoreTest {
    private val files = InMemoryFileStore()
    private var counter = 0
    private val store = PhotoStore(files, FakeImageCodec(), { id(counter++) }, Dispatchers.Unconfined)

    /** A valid 26-character ULID-shaped id ending with the counter. */
    private fun id(n: Int) = "01HTEST" + "0".repeat(17) + n.toString().padStart(2, '0')

    @Test
    fun should_write_a_bounded_jpeg_and_a_thumbnail_from_the_source_named_by_id() = runTest {
        val photo = assertNotNull(store.store("3000x1500".encodeToByteArray()))

        assertEquals("${id(0)}.jpg", photo.fileName)
        assertEquals("${id(0)}_t.jpg", photo.thumbName)
        assertEquals(2048, photo.width)
        assertEquals(1024, photo.height)
        assertEquals("jpeg:2048x1024:q85", files.read("photos/${id(0)}.jpg")?.decodeToString())
        assertEquals("jpeg:256x128:q80", files.read("photos/${id(0)}_t.jpg")?.decodeToString())
        assertEquals(sha256("jpeg:2048x1024:q85".encodeToByteArray()).toHexString(), photo.sha256)
        assertEquals(listOf("${id(0)}.jpg", "${id(0)}_t.jpg"), store.listFiles())
        assertTrue(store.exists(photo.fileName))
        assertEquals("/mem/photos/${id(0)}.jpg", store.absolutePath(photo.fileName))
    }

    @Test
    fun should_not_upscale_small_images() = runTest {
        val photo = assertNotNull(store.store("300x200".encodeToByteArray()))
        assertEquals(300, photo.width)
        assertEquals(200, photo.height)
    }

    @Test
    fun should_write_nothing_for_undecodable_bytes() = runTest {
        assertNull(store.store("not an image".encodeToByteArray()))
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun should_remove_the_full_image_when_the_thumbnail_write_fails() = runTest {
        files.failWritesContaining = "_t.jpg"
        assertFailsWith<IllegalStateException> { store.store("100x100".encodeToByteArray()) }
        assertTrue(files.files.isEmpty(), "no orphaned full-size file")
    }

    @Test
    fun should_delete_files() = runTest {
        val photo = assertNotNull(store.store("100x100".encodeToByteArray()))
        assertTrue(store.delete(photo.fileName))
        assertTrue(store.delete(photo.thumbName))
        assertTrue(store.listFiles().isEmpty())
    }

    @Test
    fun should_only_accept_file_names_it_generates() {
        assertTrue(PhotoStore.isValidFileName("${id(7)}.jpg"))
        assertTrue(PhotoStore.isValidFileName("${id(7)}_t.jpg"))
        assertFalse(PhotoStore.isValidFileName("../x.jpg"))
        assertFalse(PhotoStore.isValidFileName("${id(7)}.png"))
        assertFalse(PhotoStore.isValidFileName(id(7).lowercase() + ".jpg"))
        assertFailsWith<IllegalArgumentException> { store.absolutePath("evil.jpg") }
    }
}
