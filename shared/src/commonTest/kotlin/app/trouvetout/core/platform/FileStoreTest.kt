package app.trouvetout.core.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fresh empty directory for the test (platform temp dir). */
expect fun testTempDirectory(): String

class FileStoreTest {
    private val store = createFileStore(testTempDirectory())

    @Test
    fun should_write_read_list_and_delete_files_in_nested_directories() {
        assertNull(store.read("photos/a.jpg"))
        assertFalse(store.exists("photos/a.jpg"))
        assertEquals(emptyList(), store.list("photos"))

        store.write("photos/a.jpg", byteArrayOf(1, 2, 3))
        store.write("photos/b.jpg", byteArrayOf(4))
        store.write("photos/a.jpg", byteArrayOf(9, 9))

        assertContentEquals(byteArrayOf(9, 9), store.read("photos/a.jpg"))
        assertTrue(store.exists("photos/a.jpg"))
        assertEquals(listOf("a.jpg", "b.jpg"), store.list("photos"))
        assertTrue(
            store.absolutePath("photos/a.jpg").endsWith("photos/a.jpg") ||
                store.absolutePath("photos/a.jpg").endsWith("photos\\a.jpg"),
        )

        assertTrue(store.delete("photos/a.jpg"))
        assertFalse(store.delete("photos/a.jpg"))
        assertEquals(listOf("b.jpg"), store.list("photos"))
    }

    @Test
    fun should_refuse_paths_escaping_the_store() {
        assertFailsWith<IllegalArgumentException> { store.write("../escape.txt", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { store.read("photos/../../x") }
        assertFailsWith<IllegalArgumentException> { store.exists("/absolute") }
        assertFailsWith<IllegalArgumentException> { store.delete("") }
    }
}
