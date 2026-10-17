package space.webkombinat.oshiire.ui.annotate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.webkombinat.oshiire.data.FileNoteRepository
import space.webkombinat.oshiire.data.NoteRepository
import space.webkombinat.oshiire.model.AnnotationDoc
import space.webkombinat.oshiire.model.Pt
import space.webkombinat.oshiire.model.Shape
import java.io.ByteArrayOutputStream
import java.io.File

enum class Tool { MOVE, PEN, ELLIPSE, ARROW, RECT, TEXT }

data class AnnotateUiState(
    val bitmap: Bitmap? = null,
    val shapes: List<Shape> = emptyList(),
    /** ドラッグ中の図形（確定前） */
    val drawing: Shape? = null,
    val tool: Tool = Tool.ELLIPSE,
    val color: Long = PALETTE.first(),
    val widthLevel: Int = 1,
    val selected: Int = -1,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: Boolean = false,
)

/** 赤・黄・緑・青・白・黒 */
val PALETTE = listOf(0xFFF44336, 0xFFFFC107, 0xFF4CAF50, 0xFF2196F3, 0xFFFFFFFF, 0xFF212121)

/** 線の太さ（短辺比）と、それに対応する文字の大きさ */
val STROKE_WIDTHS = listOf(0.006f, 0.012f, 0.02f)
val TEXT_SIZES = listOf(0.045f, 0.065f, 0.09f)

/**
 * 写真への書き込み。原本は IMG_x.orig.jpg に残し、書き込みは IMG_x.jpg.annot.json に保存して、
 * 焼き込んだ画像で IMG_x.jpg を上書きする（Obsidian でも書き込み入りで見え、アプリでは描き直せる）。
 */
@HiltViewModel(assistedFactory = AnnotateViewModel.Factory::class)
class AnnotateViewModel @AssistedInject constructor(
    @Assisted private val path: String,
    private val repository: NoteRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(path: String): AnnotateViewModel
    }

    private val image: File = repository.resolve(path)
    private val original = File(image.parentFile, image.nameWithoutExtension + ".orig." + image.extension)
    private val annotations = File(image.parentFile, image.name + ".annot.json")

    private val _state = MutableStateFlow(AnnotateUiState())
    val state: StateFlow<AnnotateUiState> = _state.asStateFlow()

    private val undo = ArrayDeque<List<Shape>>()
    private val redo = ArrayDeque<List<Shape>>()

    init {
        viewModelScope.launch {
            val (bitmap, shapes) = withContext(Dispatchers.IO) {
                val base = if (original.exists()) original else image
                val shapes = runCatching { AnnotationDoc.fromJson(annotations.readText()).shapes }.getOrDefault(emptyList())
                // 画面に出す分には 2048px で十分。原寸は保存するときだけ読む
                decode(base, PREVIEW_SIDE) to shapes
            }
            _state.update { it.copy(bitmap = bitmap, shapes = shapes, error = bitmap == null) }
        }
    }

    fun setTool(tool: Tool) = _state.update { it.copy(tool = tool, selected = -1) }
    /** 色を選ぶ。図形を選んでいればその色も変える */
    fun setColor(color: Long) {
        val s = _state.value
        if (s.selected in s.shapes.indices) commit(s.shapes.mapIndexed { i, sh -> if (i == s.selected) sh.recolor(color) else sh })
        _state.update { it.copy(color = color) }
    }

    fun setWidthLevel(level: Int) = _state.update { it.copy(widthLevel = level) }

    private fun strokeWidth() = STROKE_WIDTHS[_state.value.widthLevel]

    /** ドラッグ開始。座標は正規化座標 */
    fun beginDraw(p: Pt) {
        val s = _state.value
        val shape = when (s.tool) {
            Tool.PEN -> Shape.Pen(listOf(p), s.color, strokeWidth())
            Tool.ELLIPSE -> Shape.Ellipse(p, p, s.color, strokeWidth())
            Tool.ARROW -> Shape.Arrow(p, p, s.color, strokeWidth())
            Tool.RECT -> Shape.Rect(p, p, s.color, strokeWidth())
            Tool.MOVE, Tool.TEXT -> null
        }
        _state.update { it.copy(drawing = shape) }
    }

    fun drawTo(p: Pt) = _state.update { s ->
        val d = when (val cur = s.drawing) {
            is Shape.Pen -> cur.copy(points = cur.points + p)
            is Shape.Ellipse -> cur.copy(end = p)
            is Shape.Arrow -> cur.copy(end = p)
            is Shape.Rect -> cur.copy(end = p)
            else -> cur
        }
        s.copy(drawing = d)
    }

    fun endDraw() {
        val d = _state.value.drawing ?: return
        _state.update { it.copy(drawing = null) }
        commit(_state.value.shapes + d)
    }

    fun addText(at: Pt, text: String) {
        if (text.isBlank()) return
        val s = _state.value
        commit(s.shapes + Shape.Text(at, text.trim(), s.color, TEXT_SIZES[s.widthLevel]))
    }

    fun select(index: Int) = _state.update { it.copy(selected = index) }

    /** 選んだ図形を動かす。ドラッグ中は履歴に積まず、[endMove] で積む */
    private var moveStart: List<Shape>? = null

    fun moveSelected(d: Pt) {
        val s = _state.value
        if (s.selected !in s.shapes.indices) return
        if (moveStart == null) moveStart = s.shapes
        _state.update { it.copy(shapes = it.shapes.mapIndexed { i, sh -> if (i == it.selected) sh.translate(d) else sh }) }
    }

    fun endMove() {
        val before = moveStart ?: return
        moveStart = null
        undo.addLast(before)
        redo.clear()
        updateHistoryFlags()
    }

    fun deleteSelected() {
        val s = _state.value
        if (s.selected !in s.shapes.indices) return
        commit(s.shapes.filterIndexed { i, _ -> i != s.selected })
        _state.update { it.copy(selected = -1) }
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(_state.value.shapes)
        _state.update { it.copy(shapes = prev, selected = -1) }
        updateHistoryFlags()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(_state.value.shapes)
        _state.update { it.copy(shapes = next, selected = -1) }
        updateHistoryFlags()
    }

    fun save() {
        val s = _state.value
        if (s.bitmap == null) return
        _state.update { it.copy(saving = true, selected = -1) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (!original.exists()) image.copyTo(original)
                    val full = decode(original, MAX_SIDE) ?: error("decode failed")
                    val out = if (full.isMutable) full else full.copy(Bitmap.Config.ARGB_8888, true).also { full.recycle() }
                    AnnotationRenderer.draw(Canvas(out), s.shapes, out.width.toFloat(), out.height.toFloat())
                    val bytes = ByteArrayOutputStream().use { bos ->
                        out.compress(Bitmap.CompressFormat.JPEG, 92, bos)
                        bos.toByteArray()
                    }
                    out.recycle()
                    FileNoteRepository.writeAtomic(image, bytes)
                    FileNoteRepository.writeAtomic(annotations, AnnotationDoc(shapes = s.shapes).toJson().toByteArray())
                }.isSuccess
            }
            if (ok) repository.notifyImageChanged(path)
            _state.update { it.copy(saving = false, saved = ok, error = !ok) }
        }
    }

    private fun commit(shapes: List<Shape>) {
        undo.addLast(_state.value.shapes)
        redo.clear()
        _state.update { it.copy(shapes = shapes) }
        updateHistoryFlags()
    }

    private fun updateHistoryFlags() = _state.update { it.copy(canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }

    private fun Shape.recolor(c: Long): Shape = when (this) {
        is Shape.Pen -> copy(color = c)
        is Shape.Ellipse -> copy(color = c)
        is Shape.Rect -> copy(color = c)
        is Shape.Arrow -> copy(color = c)
        is Shape.Text -> copy(color = c)
    }

    private companion object {
        const val MAX_SIDE = 4096
        const val PREVIEW_SIDE = 2048

        /** EXIF の向きを反映して読み込む。大きすぎる画像は縮小する */
        fun decode(file: File, maxSide: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inMutable = true
            })
                ?: return@runCatching null
            val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                else -> return@runCatching bmp
            }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
        }.getOrNull()
    }
}
