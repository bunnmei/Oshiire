package space.webkombinat.oshiire.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.android.awaitFrame
import space.webkombinat.oshiire.R
import space.webkombinat.oshiire.ui.camera.CameraViewModel
import space.webkombinat.oshiire.ui.camera.FloatingCamera
import space.webkombinat.oshiire.ui.camera.FullscreenCamera
import space.webkombinat.oshiire.ui.camera.WindowMode
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: String,
    onBack: () -> Unit,
    onAnnotate: (String) -> Unit,
) {
    val vm = hiltViewModel<NoteEditorViewModel, NoteEditorViewModel.Factory>(
        key = noteId,
        creationCallback = { it.create(noteId) },
    )
    val cameraVm: CameraViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val window by cameraVm.window.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { context.contentResolver.openInputStream(it) }?.let(vm::importImage)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        val hidden = window.mode == WindowMode.HIDDEN
                        IconButton(onClick = {
                            cameraVm.updateWindow { it.copy(mode = if (hidden) WindowMode.WINDOW else WindowMode.HIDDEN) }
                        }) {
                            Icon(
                                if (hidden) Icons.Filled.PhotoCamera else Icons.Filled.NoPhotography,
                                stringResource(if (hidden) R.string.camera_show else R.string.camera_hide),
                            )
                        }
                        IconButton(onClick = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(Icons.Filled.PhotoLibrary, stringResource(R.string.editor_insert_from_gallery))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_delete_note)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = { menuOpen = false; confirmDelete = true },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
                if (!state.loading) {
                    EditorList(state, vm, resolve = cameraVm::resolve, onAnnotate = onAnnotate)
                }
                FloatingCamera(cameraVm, onCaptured = vm::insertImage)
            }
        }
        FullscreenCamera(cameraVm, onCaptured = vm::insertImage)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.editor_delete_title)) },
            text = { Text(stringResource(R.string.editor_delete_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteNote() }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun EditorList(
    state: EditorUiState,
    vm: NoteEditorViewModel,
    resolve: (String) -> File,
    onAnnotate: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val requesters = remember { HashMap<Long, FocusRequester>() }
    fun requester(id: Long) = requesters.getOrPut(id) { FocusRequester() }
    // 0 番目の項目はタイトル
    fun indexOf(id: Long) = state.blocks.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)

    LaunchedEffect(state.revealBlockId) {
        val id = state.revealBlockId ?: return@LaunchedEffect
        indexOf(id)?.let { listState.animateScrollToItem(it) }
        vm.consumeReveal()
    }
    LaunchedEffect(state.focus?.seq) {
        val f = state.focus ?: return@LaunchedEffect
        val index = if (f.blockId == NoteEditorViewModel.TITLE) 0 else indexOf(f.blockId) ?: return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
    }

    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    LazyColumn(
        state = listState,
        // 下に余白を取って、カメラ窓に隠れずに最後の行まで書けるようにする
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 320.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title") {
            val firstText = state.blocks.firstOrNull { it is EditorBlock.Text }?.id
            EditorTextField(
                text = state.title,
                focusRequester = requester(NoteEditorViewModel.TITLE),
                focus = state.focus?.takeIf { it.blockId == NoteEditorViewModel.TITLE },
                onFocusConsumed = vm::consumeFocus,
                placeholder = stringResource(R.string.editor_title_hint),
                style = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                onNext = { firstText?.let { requester(it).requestFocus() } },
                onChange = { t, _ -> vm.onTitleChange(t) },
                onCaret = {},
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        itemsIndexed(state.blocks, key = { _, b -> b.id }) { i, block ->
            when (block) {
                is EditorBlock.Text -> EditorTextField(
                    text = block.text,
                    focusRequester = requester(block.id),
                    focus = state.focus?.takeIf { it.blockId == block.id },
                    onFocusConsumed = vm::consumeFocus,
                    placeholder = if (state.blocks.size == 1) stringResource(R.string.editor_body_hint) else null,
                    style = textStyle,
                    singleLine = false,
                    onNext = {},
                    onChange = { t, c -> vm.onTextChange(block.id, t, c) },
                    onCaret = { c -> vm.onCaret(block.id, c) },
                    modifier = Modifier.heightIn(min = if (block.text.isEmpty()) 40.dp else 0.dp),
                )

                is EditorBlock.Image -> ImageBlock(
                    block = block,
                    file = resolve(block.path),
                    canMoveUp = i > 1,
                    canMoveDown = i < state.blocks.lastIndex - 1,
                    onAnnotate = { onAnnotate(block.path) },
                    onMove = { up -> vm.moveImage(block.id, up) },
                    onRemove = { vm.removeImage(block.id) },
                )
            }
        }
    }
}

/**
 * 枠のない入力欄。変換中の文字（IME の未確定文字）を消さないよう、入力中の値は手元に持ち、
 * 外から文字が変わったとき（写真の挿入で分割されたときなど）だけ合わせる。
 */
@Composable
private fun EditorTextField(
    text: String,
    focusRequester: FocusRequester,
    focus: FocusRequest?,
    onFocusConsumed: () -> Unit,
    placeholder: String?,
    style: TextStyle,
    singleLine: Boolean,
    onNext: () -> Unit,
    onChange: (String, Int) -> Unit,
    onCaret: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text) {
        value = TextFieldValue(text, TextRange(value.selection.start.coerceAtMost(text.length)))
    }
    LaunchedEffect(focus?.seq) {
        val f = focus ?: return@LaunchedEffect
        value = value.copy(selection = TextRange(f.cursor.coerceIn(0, value.text.length)))
        awaitFrame()
        runCatching { focusRequester.requestFocus() }
        onFocusConsumed()
    }
    BasicTextField(
        value = value,
        onValueChange = { v ->
            val changed = v.text != value.text
            value = v
            if (changed) onChange(v.text, v.selection.start) else onCaret(v.selection.start)
        },
        textStyle = style,
        singleLine = singleLine,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = if (singleLine) KeyboardOptions(imeAction = ImeAction.Next) else KeyboardOptions.Default,
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onCaret(value.selection.start) },
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty() && placeholder != null) {
                    Text(placeholder, style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)))
                }
                inner()
            }
        },
    )
}

@Composable
private fun ImageBlock(
    block: EditorBlock.Image,
    file: File,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onAnnotate: () -> Unit,
    onMove: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(file)
                .memoryCacheKey("${file.path}#${block.version}#${file.lastModified()}")
                .build(),
            contentDescription = block.alt.ifBlank { stringResource(R.string.editor_photo) },
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_annotate)) },
                leadingIcon = { Icon(Icons.Filled.Draw, null) },
                onClick = { menu = false; onAnnotate() },
            )
            if (canMoveUp) DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_move_up)) },
                leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                onClick = { menu = false; onMove(true) },
            )
            if (canMoveDown) DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_move_down)) },
                leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                onClick = { menu = false; onMove(false) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_remove_photo)) },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { menu = false; onRemove() },
            )
        }
    }
}
