package space.webkombinat.oshiire.ui.camera

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Display
import android.view.Surface
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import kotlinx.coroutines.delay
import space.webkombinat.oshiire.camera.CameraController
import space.webkombinat.oshiire.camera.CameraUiState
import space.webkombinat.oshiire.camera.MeteringMath
import kotlin.math.roundToInt

/** 画面に表示したときのプレビューの縦横比（幅 / 高さ） */
fun previewAspect(state: CameraUiState, displayRotation: Int): Float {
    val caps = state.caps
    val lens = state.currentLens
    val buffer = state.previewSize ?: return 3f / 4f
    val rel = if (caps != null && lens != null) {
        MeteringMath.relativeRotation(caps.sensorOrientation, displayRotation, lens.isFront)
    } else 90
    return if (rel % 180 == 90) buffer.height.toFloat() / buffer.width else buffer.width.toFloat() / buffer.height
}

/**
 * カメラのプレビュー。タップでピント・明るさ合わせ、長押しで固定、ダブルタップで解除。
 * 呼び出し側で [previewAspect] の縦横比の枠に入れて使う。
 *
 * 画面遷移のアニメーション中はカメラを動かさず（遷移をカクつかせない）、遷移が終わってから開く。
 * 映像が届くまではローディングを出す。
 */
@Composable
fun CameraPreviewBox(
    state: CameraUiState,
    camera: CameraController,
    displayRotation: Int,
    grid: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = LocalNavAnimatedContentScope.current.transition
    val settled = !transition.isRunning && transition.currentState == transition.targetState
    // 一度表示したら、戻るアニメーション中も最後のフレームを見せておく
    var everSettled by remember { mutableStateOf(false) }
    SideEffect { if (settled) everSettled = true }
    val shown = settled || everSettled
    DisposableEffect(settled) {
        if (settled) camera.attachPreview()
        onDispose { if (settled) camera.detachPreview() }
    }

    var focusMark by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(focusMark, state.settings.locked) {
        if (focusMark != null && !state.settings.locked) {
            delay(1500)
            focusMark = null
        }
    }
    Box(modifier.background(Color.Black)) {
        if (shown) {
            CameraTexture(camera.previewTexture, state.previewSize, displayRotation)
        }
        if (!state.previewStreaming && settled) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
        }
        Box(
            Modifier.fillMaxSize().pointerInput(displayRotation) {
                detectTapGestures(
                    onTap = { o ->
                        focusMark = o
                        camera.focusAt(o.x / size.width, o.y / size.height, displayRotation, lock = false)
                    },
                    onLongPress = { o ->
                        focusMark = o
                        camera.focusAt(o.x / size.width, o.y / size.height, displayRotation, lock = true)
                    },
                    onDoubleTap = {
                        focusMark = null
                        camera.resetFocus()
                    },
                )
            }
        )
        if (grid) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.5f)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.dp.toPx())
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.dp.toPx())
                }
            }
        }
        focusMark?.let { o ->
            val half = with(LocalDensity.current) { 28.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((o.x - half).roundToInt(), (o.y - half).roundToInt()) }
                    .size(56.dp)
                    .border(2.dp, if (state.settings.locked) Color(0xFFFF8A65) else Color(0xFFFFD54F), RoundedCornerShape(6.dp))
            )
        }
    }
}

/**
 * カメラの SurfaceTexture を貼った TextureView。SurfaceTexture は [CameraController] のものを使い回し、
 * TextureView が消えても解放しない（次の TextureView に貼り直す）。
 */
@Composable
private fun CameraTexture(texture: SurfaceTexture, bufferSize: Size?, displayRotation: Int) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        // 何かの理由で TextureView が自前の SurfaceTexture を作ったら、カメラのものに貼り替える
                        if (st !== texture) setSurfaceTexture(texture)
                        applyCameraTransform()
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyCameraTransform()
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = st !== texture
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
                setSurfaceTexture(texture)
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> (v as TextureView).applyCameraTransform() }
            }
        },
        update = { view ->
            val params = bufferSize to displayRotation
            if (view.tag != params) {
                view.tag = params
                view.applyCameraTransform()
            }
        },
    )
}

/**
 * TextureView はセンサーの向き（端末の自然な向き基準）までは自動で直すが、画面の回転は直さないので
 * 横画面のときだけ行列で回す。
 */
@Suppress("UNCHECKED_CAST")
private fun TextureView.applyCameraTransform() {
    val (buffer, rotation) = (tag as? Pair<Size?, Int>) ?: return
    if (buffer == null || width == 0 || height == 0) return
    val w = width.toFloat()
    val h = height.toFloat()
    val m = Matrix()
    val cx = w / 2
    val cy = h / 2
    if (rotation == 90 || rotation == 270) {
        val viewRect = RectF(0f, 0f, w, h)
        val bufferRect = RectF(0f, 0f, buffer.height.toFloat(), buffer.width.toFloat())
        bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
        m.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = maxOf(h / buffer.height, w / buffer.width)
        m.postScale(scale, scale, cx, cy)
        m.postRotate(if (rotation == 90) -90f else 90f, cx, cy)
    } else if (rotation == 180) {
        m.postRotate(180f, cx, cy)
    }
    setTransform(m)
}

/**
 * 画面の回転 (0/90/180/270)。横 → 逆横の 180° 回転は構成変更として通知されないため、
 * DisplayListener で監視する。
 */
@Composable
fun rememberDisplayRotation(): Int {
    val view = LocalView.current
    val context = LocalContext.current
    var rotation by remember { mutableIntStateOf(view.display.rotationDegrees()) }
    DisposableEffect(view, context) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                rotation = view.display.rotationDegrees()
            }

            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        rotation = view.display.rotationDegrees()
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    return rotation
}

private fun Display?.rotationDegrees(): Int = when (this?.rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
