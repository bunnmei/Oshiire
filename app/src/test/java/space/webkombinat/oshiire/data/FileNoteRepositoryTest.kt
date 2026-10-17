package space.webkombinat.oshiire.data

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.webkombinat.oshiire.model.MdBlock
import space.webkombinat.oshiire.model.NoteContent
import java.io.File

class FileNoteRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_760_000_000_000L
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun repo(dir: File = File(tmp.root, "vault")) = FileNoteRepository(dir, dispatcher) { now }

    private fun content(title: String, vararg blocks: MdBlock) = NoteContent(title, blocks.toList())

    @Test
    fun create_doesNotWriteFileUntilSaved() = scope.runTest {
        val r = repo()
        val id = r.create()
        assertFalse(File(r.vaultDir, "$id.md").exists())
        r.refresh()
        assertTrue(r.notes.value.isEmpty())
    }

    @Test
    fun create_returnsUniqueIdsWithinSameSecond() = scope.runTest {
        val r = repo()
        val a = r.create()
        val b = r.create()
        assertNotEquals(a, b)
    }

    @Test
    fun save_thenLoad_roundTrips() = scope.runTest {
        val r = repo()
        val id = r.create()
        val c = content("タイトル", MdBlock.Text("本文"), MdBlock.Image("attachments/a.jpg"))
        r.save(id, c)
        val loaded = r.load(id)!!
        assertEquals(c.title, loaded.content.title)
        assertEquals(c.blocks, loaded.content.blocks)
        assertEquals("# タイトル\n\n本文\n\n![](attachments/a.jpg)\n", File(r.vaultDir, "$id.md").readText())
    }

    @Test
    fun notes_areSortedNewestFirst_andSummarized() = scope.runTest {
        val r = repo()
        val old = r.create()
        r.save(old, content("古い", MdBlock.Text("前のメモ")))
        now += 60_000
        val new = r.create()
        r.save(new, content("新しい", MdBlock.Text("後のメモ"), MdBlock.Image("attachments/x.jpg")))

        val list = r.notes.value
        assertEquals(listOf(new, old), list.map { it.id })
        assertEquals("新しい", list[0].title)
        assertEquals("後のメモ", list[0].excerpt)
        assertEquals("attachments/x.jpg", list[0].thumbnail)
        assertNull(list[1].thumbnail)

        // 古い方を編集すると先頭に来る
        now += 60_000
        r.save(old, content("古い", MdBlock.Text("書き足した")))
        assertEquals(listOf(old, new), r.notes.value.map { it.id })
    }

    @Test
    fun save_blankContent_deletesFile() = scope.runTest {
        val r = repo()
        val id = r.create()
        r.save(id, content("t"))
        assertTrue(File(r.vaultDir, "$id.md").exists())
        r.save(id, content("", MdBlock.Text("  ")))
        assertFalse(File(r.vaultDir, "$id.md").exists())
        assertTrue(r.notes.value.isEmpty())
    }

    @Test
    fun delete_movesToTrash_andRestoreBringsBack() = scope.runTest {
        val r = repo()
        val id = r.create()
        r.save(id, content("消す"))
        r.delete(id)
        assertTrue(r.notes.value.isEmpty())
        assertTrue(File(r.vaultDir, ".trash/$id.md").exists())
        r.restore(id)
        assertEquals(listOf(id), r.notes.value.map { it.id })
    }

    @Test
    fun refresh_picksUpFilesWrittenOutside() = scope.runTest {
        val r = repo()
        r.vaultDir.mkdirs()
        File(r.vaultDir, "from-pc.md").writeText("# PCで書いた\n本文")
        File(r.vaultDir, "notes.txt").writeText("無視される")
        r.refresh()
        assertEquals(listOf("from-pc"), r.notes.value.map { it.id })
        assertEquals("PCで書いた", r.notes.value[0].title)
    }

    @Test
    fun newImageFile_createsUniqueFilesInAttachments() = scope.runTest {
        val r = repo()
        val (p1, f1) = r.newImageFile()
        val (p2, f2) = r.newImageFile()
        assertTrue(p1.startsWith("attachments/IMG_") && p1.endsWith(".jpg"))
        assertNotEquals(p1, p2)
        assertTrue(f1.exists() && f2.exists())
        assertEquals(f1, r.resolve(p1))
    }

    @Test
    fun importImage_copiesBytes() = scope.runTest {
        val r = repo()
        val path = r.importImage(byteArrayOf(1, 2, 3).inputStream())
        assertEquals(listOf<Byte>(1, 2, 3), r.resolve(path).readBytes().toList())
    }

    @Test
    fun resolve_findsObsidianStyleBareFileNameInAttachments() = scope.runTest {
        val r = repo()
        val (path, file) = r.newImageFile()
        assertEquals(file, r.resolve(path.substringAfter('/')))
    }

    @Test
    fun writeAtomic_replacesContentAndLeavesNoTempFile() {
        val f = File(tmp.root, "a.md")
        f.writeText("old")
        FileNoteRepository.writeAtomic(f, "new".toByteArray())
        assertEquals("new", f.readText())
        assertEquals(listOf("a.md"), tmp.root.list()!!.toList())
    }
}
