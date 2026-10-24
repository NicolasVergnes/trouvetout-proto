package app.trouvetout.core.data.photo

import app.trouvetout.core.platform.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoStoreTest {
    private val files = InMemoryFileStore()
    private var counter = 0
    private val store = PhotoStore(files, FakeImageCodec()) { "01HPHOTO${counter++}" }

    @Test
    fun should_write_a_bounded_jpeg_and_a_thumbnail_named_by_id() {
        val photo = assertNotNull(store.store("3000x1500".encodeToByteArray()))

        assertEquals("01HPHOTO0.jpg", photo.fileName)
        assertEquals("01HPHOTO0_t.jpg", photo.thumbName)
        assertEquals(2048, photo.width)
        assertEquals(1024, photo.height)
        assertEquals("jpeg:2048x1024:q85", files.read("photos/01HPHOTO0.jpg")?.decodeToString())
        assertEquals("jpeg:256x128:q80", files.read("photos/01HPHOTO0_t.jpg")?.decodeToString())
        assertEquals(sha256("jpeg:2048x1024:q85".encodeToByteArray()).toHexString(), photo.sha256)
        assertEquals(listOf("01HPHOTO0.jpg", "01HPHOTO0_t.jpg"), store.listFiles())
        assertTrue(store.exists(photo.fileName))
        assertEquals("/mem/photos/01HPHOTO0.jpg", store.absolutePath(photo.fileName))
    }

    @Test
    fun should_not_upscale_small_images() {
        val photo = assertNotNull(store.store("300x200".encodeToByteArray()))
        assertEquals(300, photo.width)
        assertEquals(200, photo.height)
    }

    @Test
    fun should_write_nothing_for_undecodable_bytes() {
        assertNull(store.store("not an image".encodeToByteArray()))
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun should_delete_files() {
        val photo = assertNotNull(store.store("100x100".encodeToByteArray()))
        assertTrue(store.delete(photo.fileName))
        assertTrue(store.delete(photo.thumbName))
        assertTrue(store.listFiles().isEmpty())
    }
}
