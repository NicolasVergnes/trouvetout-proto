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
        val absolute = store.absolutePath("photos/a.jpg")
        assertTrue(absolute.endsWith("photos/a.jpg") || absolute.endsWith("photos" + '\\' + "a.jpg"), absolute)

        assertTrue(store.delete("photos/a.jpg"))
        assertFalse(store.delete("photos/a.jpg"))
        assertEquals(listOf("b.jpg"), store.list("photos"))
    }

    @Test
    fun should_refuse_paths_escaping_the_store() {
        val nul = '\u0000'
        val del = '\u007F'
        val backslash = '\\'
        val bad =
            listOf(
                "../escape.txt",
                "photos/../../x",
                "photos/..",
                "/absolute",
                "",
                "   ",
                "photos${backslash}x.jpg",
                "C:/x",
                "photos/./x",
                "./x",
                "photos/",
                "photos//x",
                "photos/..$nul",
                "photos/x$nul.jpg",
                "photos/x\n.jpg",
                "photos/x$del",
            )
        for (path in bad) {
            assertFailsWith<IllegalArgumentException>(path) { store.write(path, byteArrayOf(1)) }
            assertFailsWith<IllegalArgumentException>(path) { store.read(path) }
            assertFailsWith<IllegalArgumentException>(path) { store.exists(path) }
            assertFailsWith<IllegalArgumentException>(path) { store.delete(path) }
        }
    }

    @Test
    fun should_not_list_hidden_or_temporary_files() {
        store.write("photos/.hidden", byteArrayOf(1))
        store.write("photos/left.over.tmp", byteArrayOf(1))
        store.write("photos/real.jpg", byteArrayOf(1))
        assertEquals(listOf("real.jpg"), store.list("photos"))
    }
}
