package space.webkombinat.oshiire.ui.camera

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
 * カメラのプレビュー。タップでピント・明るさ合わせ、長押しで固定。
 * 呼び出し側で [previewAspect] の縦横比の枠に入れて使う。
 */
@Composable
fun CameraPreviewBox(
    state: CameraUiState,
    camera: CameraController,
    displayRotation: Int,
    grid: Boolean,
    modifier: Modifier = Modifier,
) {
    var focusMark by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(focusMark, state.settings.locked) {
        if (focusMark != null && !state.settings.locked) {
            delay(1500)
            focusMark = null
        }
    }
    Box(modifier) {
        CameraSurface(
            bufferSize = state.previewSize,
            onSurfaceReady = { s, size -> camera.setPreviewSurface(s, size) },
            onSurfaceDestroyed = { camera.setPreviewSurface(null, null) },
        )
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
 * Camera2 のプレビュー先 SurfaceView。
 * バッファサイズは [bufferSize]（センサー向き）に固定し、表示の回転は SurfaceView/カメラ側に任せる。
 */
@Composable
private fun CameraSurface(
    bufferSize: Size?,
    onSurfaceReady: (Surface, Size) -> Unit,
    onSurfaceDestroyed: () -> Unit,
) {
    val ready by rememberUpdatedState(onSurfaceReady)
    val destroyed by rememberUpdatedState(onSurfaceDestroyed)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        ready(holder.surface, Size(width, height))
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        destroyed()
                    }
                })
            }
        },
        update = { view ->
            if (bufferSize != null && view.tag != bufferSize) {
                view.tag = bufferSize
                view.holder.setFixedSize(bufferSize.width, bufferSize.height)
            }
        },
    )
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
