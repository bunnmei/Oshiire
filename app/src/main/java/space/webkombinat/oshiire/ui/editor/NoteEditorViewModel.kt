package space.webkombinat.oshiire.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.webkombinat.oshiire.data.NoteRepository
import space.webkombinat.oshiire.di.ApplicationScope
import space.webkombinat.oshiire.markdown.MarkdownCodec
import space.webkombinat.oshiire.model.NoteContent
import java.io.InputStream

/** フォーカスを移したい場所。[blockId] が [TITLE] ならタイトル欄 */
data class FocusRequest(val blockId: Long, val cursor: Int, val seq: Long)

data class EditorUiState(
    val loading: Boolean = true,
    val title: String = "",
    val blocks: List<EditorBlock> = emptyList(),
    val focus: FocusRequest? = null,
    /** 最後に挿入した画像（スクロールして見せる） */
    val revealBlockId: Long? = null,
    val deleted: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel(assistedFactory = NoteEditorViewModel.Factory::class)
class NoteEditorViewModel @AssistedInject constructor(
    @Assisted val noteId: String,
    private val repository: NoteRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(noteId: String): NoteEditorViewModel
    }

    private val ops = BlockOps()
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    // 写真を入れる位置（最後に触った文章ブロックとカーソル）。再描画の必要がないので state に入れない
    private var caretBlockId: Long? = null
    private var caretOffset: Int = 0
    private var focusSeq = 0L

    private val saveMutex = Mutex()
    /** 最後に保存した Markdown（開いただけで更新日時が変わらないよう、中身が変わったときだけ書く） */
    private var lastSaved: String? = null

    init {
        viewModelScope.launch {
            val note = repository.load(noteId)
            val blocks = ops.fromMarkdown(note?.content?.blocks.orEmpty())
            lastSaved = note?.content?.let(MarkdownCodec::encode)
            val last = blocks.last()
            caretBlockId = last.id
            caretOffset = (last as? EditorBlock.Text)?.text?.length ?: 0
            _state.value = EditorUiState(
                loading = false,
                title = note?.content?.title.orEmpty(),
                blocks = blocks,
                // 新しいメモはタイトルから書き始める
                focus = if (note == null) FocusRequest(TITLE, 0, ++focusSeq) else null,
            )
            // 入力が 0.5 秒止まったら保存（リアルタイム保存）
            launch {
                _state.map { it.toContent() }
                    .distinctUntilChanged()
                    .drop(1)
                    .debounce(SAVE_DEBOUNCE_MS)
                    .collect { saveLatest() }
            }
        }
        viewModelScope.launch {
            repository.imageChanges.collect { path ->
                _state.update { s ->
                    s.copy(blocks = s.blocks.map {
                        if (it is EditorBlock.Image && it.path == path) it.copy(version = it.version + 1) else it
                    })
                }
            }
        }
    }

    fun onTitleChange(title: String) {
        _state.update { it.copy(title = title.replace("\n", " ")) }
    }

    fun onTextChange(blockId: Long, text: String, cursor: Int) {
        caretBlockId = blockId
        caretOffset = cursor
        _state.update { it.copy(blocks = ops.updateText(it.blocks, blockId, text)) }
    }

    /** カーソル移動・フォーカス取得のたびに呼ぶ（写真を入れる位置を覚えておく） */
    fun onCaret(blockId: Long, cursor: Int) {
        caretBlockId = blockId
        caretOffset = cursor
    }

    /** カーソル位置に画像を差し込み、続きを書けるよう直後の文章にフォーカスを移す */
    fun insertImage(path: String) {
        _state.update { s ->
            val r = ops.insertImage(s.blocks, caretBlockId, caretOffset, path)
            caretBlockId = r.nextTextId
            caretOffset = 0
            s.copy(
                blocks = r.blocks,
                focus = FocusRequest(r.nextTextId, 0, ++focusSeq),
                revealBlockId = r.imageId,
            )
        }
    }

    fun importImage(input: InputStream) {
        viewModelScope.launch { insertImage(repository.importImage(input)) }
    }

    fun removeImage(blockId: Long) {
        _state.update { it.copy(blocks = ops.removeImage(it.blocks, blockId)) }
    }

    fun moveImage(blockId: Long, up: Boolean) {
        _state.update { it.copy(blocks = ops.moveImage(it.blocks, blockId, up), revealBlockId = blockId) }
    }

    fun consumeFocus() {
        _state.update { it.copy(focus = null) }
    }

    fun consumeReveal() {
        _state.update { it.copy(revealBlockId = null) }
    }

    /** 画面を離れる・アプリが裏に回るときに、待たずにすぐ保存する */
    fun flush() {
        if (_state.value.loading || _state.value.deleted) return
        appScope.launch { saveLatest() }
    }

    fun deleteNote() {
        _state.update { it.copy(deleted = true) }
        appScope.launch {
            saveMutex.withLock { repository.delete(noteId) }
        }
    }

    override fun onCleared() {
        flush()
    }

    private suspend fun saveLatest() = saveMutex.withLock {
        val s = _state.value
        if (s.loading || s.deleted) return@withLock
        val content = s.toContent()
        val md = MarkdownCodec.encode(content)
        if (md == lastSaved || (lastSaved == null && content.isBlank)) return@withLock
        repository.save(noteId, content)
        lastSaved = md
    }

    private fun EditorUiState.toContent() = NoteContent(title.trim(), ops.toMarkdown(blocks))

    companion object {
        const val TITLE = -1L
        const val SAVE_DEBOUNCE_MS = 500L
    }
}
