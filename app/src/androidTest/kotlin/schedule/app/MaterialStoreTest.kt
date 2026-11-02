package schedule.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import schedule.android.MaterialStore
import schedule.school.CourseMaterial
import java.io.File

class MaterialStoreTest {
    private fun isolated(block: (Context, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "material-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        try { block(context, directory) } finally { directory.deleteRecursively() }
    }

    @Test fun rebuildRetainsLabelsAndContents() = isolated { context, _ ->
        val material = CourseMaterial("activity", 5, 7, "练习.pdf", 3, "极限与连续")
        val saved = MaterialStore(context).save("course", "微积分", material, byteArrayOf(1, 2, 3))
        val rebuilt = MaterialStore(context)
        assertEquals(listOf(saved), rebuilt.list())
        assertEquals("微积分 - 极限与连续 - 练习.pdf", saved.exportName)
        assertArrayEquals(byteArrayOf(1, 2, 3), rebuilt.file(saved).readBytes())
    }

    @Test fun deletionIsIsolatedAndRejectsUnsafePaths() = isolated { context, directory ->
        val store = MaterialStore(context)
        val material = CourseMaterial("activity", 1, null, "notes.pdf", 1)
        val first = store.save("first", "课程一", material, byteArrayOf(1))
        val second = store.save("second", "课程二", material, byteArrayOf(2))
        val outside = File(directory, "keep.pdf").apply { writeText("keep", Charsets.UTF_8) }
        try { store.delete(first.copy(storedName = "../../keep.pdf")); fail("Unsafe path accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(outside.exists())
        store.delete(first)
        assertEquals(listOf(second), MaterialStore(context).list())
        assertArrayEquals(byteArrayOf(2), store.file(second).readBytes())
        assertTrue(outside.exists())
    }
}
