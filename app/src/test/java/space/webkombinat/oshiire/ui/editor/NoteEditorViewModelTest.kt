package space.webkombinat.oshiire.ui.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.webkombinat.oshiire.data.NoteRepository
import space.webkombinat.oshiire.model.MdBlock
import space.webkombinat.oshiire.model.Note
import space.webkombinat.oshiire.model.NoteContent
import space.webkombinat.oshiire.model.NoteSummary
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repo = FakeRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(id: String = "n1") = NoteEditorViewModel(id, repo, scope.backgroundScope)

    private fun NoteEditorViewModel.firstText() = state.value.blocks.first() as EditorBlock.Text

    @Test
    fun newNote_focusesTitle_andDoesNotSaveUntilTyped() = scope.runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(NoteEditorViewModel.TITLE, vm.state.value.focus?.blockId)
        vm.flush()
        advanceUntilIdle()
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun typing_isSavedAfterDebounce() = scope.runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onTitleChange("題")
        vm.onTextChange(vm.firstText().id, "本文", 2)
        advanceTimeBy(NoteEditorViewModel.SAVE_DEBOUNCE_MS - 100)
        runCurrent()
        assertTrue(repo.saved.isEmpty())
        advanceTimeBy(200)
        advanceUntilIdle()
        assertEquals(NoteContent("題", listOf(MdBlock.Text("本文"))), repo.saved.last().second)
    }

    @Test
    fun flush_savesImmediately() = scope.runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onTitleChange("すぐ保存")
        vm.flush()
        runCurrent()
        assertEquals("すぐ保存", repo.saved.single().second.title)
    }

    @Test
    fun openingExistingNote_doesNotRewriteIt() = scope.runTest {
        repo.stored["n1"] = NoteContent("既存", listOf(MdBlock.Text("a"), MdBlock.Image("p.jpg"), MdBlock.Text("b")))
        val vm = vm()
        advanceUntilIdle()
        assertNull(vm.state.value.focus)
        vm.flush()
        advanceUntilIdle()
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun insertImage_atCaret_movesFocusToTextAfterPhoto() = scope.runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.firstText().id
        vm.onTextChange(id, "前後", 2)
        vm.onCaret(id, 1)
        vm.insertImage("attachments/IMG_1.jpg")

        val blocks = vm.state.value.blocks
        assertEquals(3, blocks.size)
        assertEquals("前", (blocks[0] as EditorBlock.Text).text)
        assertEquals("attachments/IMG_1.jpg", (blocks[1] as EditorBlock.Image).path)
        assertEquals("後", (blocks[2] as EditorBlock.Text).text)
        assertEquals(blocks[2].id, vm.state.value.focus?.blockId)
        assertEquals(0, vm.state.value.focus?.cursor)
        assertEquals(blocks[1].id, vm.state.value.revealBlockId)

        // 続けて撮ると、さっきの写真の後ろに入る
        vm.insertImage("attachments/IMG_2.jpg")
        val paths = vm.state.value.blocks.filterIsInstance<EditorBlock.Image>().map { it.path }
        assertEquals(listOf("attachments/IMG_1.jpg", "attachments/IMG_2.jpg"), paths)

        advanceUntilIdle()
        assertEquals(
            listOf(MdBlock.Text("前"), MdBlock.Image("attachments/IMG_1.jpg"), MdBlock.Text(""), MdBlock.Image("attachments/IMG_2.jpg"), MdBlock.Text("後")),
            repo.saved.last().second.blocks,
        )
    }

    @Test
    fun clearingEverything_savesBlank_soRepositoryDeletesFile() = scope.runTest {
        repo.stored["n1"] = NoteContent("消す", emptyList())
        val vm = vm()
        advanceUntilIdle()
        vm.onTitleChange("")
        vm.flush()
        advanceUntilIdle()
        assertTrue(repo.saved.last().second.isBlank)
    }

    @Test
    fun imageChange_bumpsVersion() = scope.runTest {
        repo.stored["n1"] = NoteContent("", listOf(MdBlock.Image("p.jpg")))
        val vm = vm()
        advanceUntilIdle()
        repo.notifyImageChanged("p.jpg")
        advanceUntilIdle()
        assertEquals(1L, vm.state.value.blocks.filterIsInstance<EditorBlock.Image>().single().version)
    }

    @Test
    fun deleteNote_deletesAndStopsSaving() = scope.runTest {
        repo.stored["n1"] = NoteContent("x", emptyList())
        val vm = vm()
        advanceUntilIdle()
        vm.onTitleChange("y")
        vm.deleteNote()
        vm.flush()
        advanceUntilIdle()
        assertEquals(listOf("n1"), repo.deleted)
        assertTrue(repo.saved.isEmpty())
        assertTrue(vm.state.value.deleted)
    }

    private class FakeRepository : NoteRepository {
        val stored = HashMap<String, NoteContent>()
        val saved = mutableListOf<Pair<String, NoteContent>>()
        val deleted = mutableListOf<String>()
        private val changes = MutableSharedFlow<String>()

        override val notes: StateFlow<List<NoteSummary>> = MutableStateFlow(emptyList())
        override val imageChanges: SharedFlow<String> = changes
        override val vaultDir = File("vault")
        override suspend fun refresh() = Unit
        override suspend fun create() = "new"
        override suspend fun load(id: String) = stored[id]?.let { Note(id, it, 0L) }
        override suspend fun save(id: String, content: NoteContent) {
            saved += id to content
            stored[id] = content
        }
        override suspend fun delete(id: String) {
            deleted += id
        }
        override suspend fun restore(id: String) = Unit
        override fun resolve(path: String) = File(vaultDir, path)
        override suspend fun newImageFile() = "attachments/x.jpg" to File("x.jpg")
        override suspend fun importImage(input: InputStream) = "attachments/imported.jpg"
        override suspend fun notifyImageChanged(path: String) = changes.emit(path)
    }
}
