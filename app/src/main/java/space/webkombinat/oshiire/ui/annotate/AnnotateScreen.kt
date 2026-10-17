package space.webkombinat.oshiire.ui.annotate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.webkombinat.oshiire.R
import space.webkombinat.oshiire.model.AnnotationGeometry
import space.webkombinat.oshiire.model.Pt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotateScreen(path: String, onBack: () -> Unit) {
    val vm = hiltViewModel<AnnotateViewModel, AnnotateViewModel.Factory>(key = path, creationCallback = { it.create(path) })
    val state by vm.state.collectAsStateWithLifecycle()
    var textAt by remember { mutableStateOf<Pt?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    val back = { if (state.canUndo) confirmDiscard = true else onBack() }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.annotate_title)) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.action_undo))
                    }
                    IconButton(onClick = vm::redo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.action_redo))
                    }
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
                    } else {
                        IconButton(onClick = vm::save, enabled = state.bitmap != null) {
                            Icon(Icons.Filled.Check, stringResource(R.string.action_save))
                        }
                    }
                },
            )
        },
        bottomBar = { Toolbar(state, vm) },
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().background(Color(0xFF1E1E1E)).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = state.bitmap
            if (bitmap == null) {
                if (state.error) Text(stringResource(R.string.annotate_load_failed), color = Color.White)
                else CircularProgressIndicator()
            } else {
                Box(Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
                    Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    DrawingLayer(state, vm, onTextAt = { textAt = it })
                }
            }
        }
    }

    textAt?.let { at ->
        TextInputDialog(
            onDismiss = { textAt = null },
            onConfirm = { text ->
                vm.addText(at, text)
                textAt = null
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.annotate_discard_title)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text(stringResource(R.string.annotate_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DrawingLayer(state: AnnotateUiState, vm: AnnotateViewModel, onTextAt: (Pt) -> Unit) {
    val gestures = when (state.tool) {
        Tool.TEXT -> Modifier.pointerInput(Tool.TEXT) {
            detectTapGestures { o -> onTextAt(Pt(o.x / size.width, o.y / size.height)) }
        }
        Tool.MOVE -> Modifier
            .pointerInput(Tool.MOVE, state.shapes) {
                detectTapGestures { o ->
                    vm.select(hit(vm.state.value, o.x, o.y, size.width.toFloat(), size.height.toFloat()))
                }
            }
            .pointerInput(Tool.MOVE, "drag") {
                detectDragGestures(
                    onDragStart = { o ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val i = hit(vm.state.value, o.x, o.y, w, h)
                        if (i >= 0) vm.select(i)
                    },
                    onDragEnd = vm::endMove,
                    onDragCancel = vm::endMove,
                ) { change, drag ->
                    change.consume()
                    vm.moveSelected(Pt(drag.x / size.width, drag.y / size.height))
                }
            }
        else -> Modifier.pointerInput(state.tool) {
            detectDragGestures(
                onDragStart = { o -> vm.beginDraw(Pt(o.x / size.width, o.y / size.height)) },
                onDragEnd = vm::endDraw,
                onDragCancel = vm::endDraw,
            ) { change, _ ->
                change.consume()
                vm.drawTo(Pt(change.position.x / size.width, change.position.y / size.height))
            }
        }
    }
    Canvas(Modifier.fillMaxSize().then(gestures)) {
        drawIntoCanvas { c ->
            val shapes = state.drawing?.let { state.shapes + it } ?: state.shapes
            AnnotationRenderer.draw(c.nativeCanvas, shapes, size.width, size.height, selected = state.selected)
        }
    }
}

private fun hit(state: AnnotateUiState, x: Float, y: Float, w: Float, h: Float): Int =
    AnnotationGeometry.hitTest(state.shapes, Pt(x, y), w, h, tolerance = minOf(w, h) * 0.04f) {
        AnnotationRenderer.textWidth(it, w, h)
    }

@Composable
private fun Toolbar(state: AnnotateUiState, vm: AnnotateViewModel) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(Tool.MOVE, Icons.Filled.PanTool, R.string.tool_move, state, vm)
                ToolButton(Tool.ELLIPSE, Icons.Filled.RadioButtonUnchecked, R.string.tool_circle, state, vm)
                ToolButton(Tool.ARROW, Icons.AutoMirrored.Filled.TrendingFlat, R.string.tool_arrow, state, vm)
                ToolButton(Tool.RECT, Icons.Filled.CropSquare, R.string.tool_rect, state, vm)
                ToolButton(Tool.PEN, Icons.Filled.Draw, R.string.tool_pen, state, vm)
                ToolButton(Tool.TEXT, Icons.Filled.TextFields, R.string.tool_text, state, vm)
                if (state.tool == Tool.MOVE && state.selected >= 0) {
                    IconButton(onClick = vm::deleteSelected) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PALETTE.forEach { c ->
                    val selected = state.color == c
                    Box(
                        Modifier
                            .size(if (selected) 34.dp else 28.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { vm.setColor(c) },
                    )
                }
                Box(Modifier.weight(1f))
                TextButton(onClick = { vm.setWidthLevel((state.widthLevel + 1) % STROKE_WIDTHS.size) }) {
                    Icon(Icons.Filled.LineWeight, null)
                    Text(
                        stringResource(
                            when (state.widthLevel) { 0 -> R.string.width_thin; 1 -> R.string.width_medium; else -> R.string.width_thick }
                        ),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(tool: Tool, icon: ImageVector, label: Int, state: AnnotateUiState, vm: AnnotateViewModel) {
    FilledIconToggleButton(checked = state.tool == tool, onCheckedChange = { vm.setTool(tool) }) {
        Icon(icon, stringResource(label))
    }
}

@Composable
private fun TextInputDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.annotate_text_title)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.focusRequester(focus))
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
