package space.webkombinat.oshiire.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.webkombinat.oshiire.R
import kotlin.math.roundToInt

/**
 * 編集画面に重ねて表示する小さなカメラ窓。
 * ドラッグで移動、ピンチでズーム、右下の角で大きさ変更、タップでピント合わせ。
 * 親の Box いっぱいに置く（窓の外のタッチは下の画面に届く）。
 */
@Composable
fun FloatingCamera(
    vm: CameraViewModel,
    onCaptured: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window by vm.window.collectAsStateWithLifecycle()
    if (window.mode == WindowMode.HIDDEN || window.mode == WindowMode.FULLSCREEN) return
    val state by vm.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val rotation = rememberDisplayRotation()
    CameraPermissionEffect(vm)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val parentW = constraints.maxWidth.toFloat()
        val parentH = constraints.maxHeight.toFloat()
        val margin = with(density) { 12.dp.toPx() }

        if (window.mode == WindowMode.MINIMIZED) {
            val sizePx = with(density) { BUBBLE.toPx() }
            val x = (window.x ?: (parentW - sizePx - margin)).coerceIn(0f, (parentW - sizePx).coerceAtLeast(0f))
            val y = (window.y ?: (parentH - sizePx - margin)).coerceIn(0f, (parentH - sizePx).coerceAtLeast(0f))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(BUBBLE)
                    .pointerInput(parentW, parentH) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            vm.updateWindow { it.copy(x = x + drag.x, y = y + drag.y) }
                        }
                    }
                    .clickable { vm.updateWindow { it.copy(mode = WindowMode.WINDOW) } },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CameraAlt, stringResource(R.string.camera_restore), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            return@BoxWithConstraints
        }

        val aspect = previewAspect(state, rotation)
        val maxW = with(density) { (parentW * 0.9f).toDp() }
        val wDp = window.widthDp.dp.coerceIn(MIN_WIDTH, maxOf(MIN_WIDTH, maxW))
        val wPx = with(density) { wDp.toPx() }
        val hPx = (wPx / aspect).coerceAtMost(parentH * 0.8f)
        val x = (window.x ?: (parentW - wPx - margin)).coerceIn(0f, (parentW - wPx).coerceAtLeast(0f))
        val y = (window.y ?: (parentH - hPx - margin)).coerceIn(0f, (parentH - hPx).coerceAtLeast(0f))

        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(wDp, with(density) { hPx.toDp() })
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                // 1 本指で移動、2 本指でズーム
                .pointerInput(parentW, parentH, wPx, hPx) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) vm.camera.setZoom(vm.state.value.settings.zoomRatio * zoom)
                        vm.updateWindow {
                            val cx = it.x ?: x
                            val cy = it.y ?: y
                            it.copy(
                                x = (cx + pan.x).coerceIn(0f, (parentW - wPx).coerceAtLeast(0f)),
                                y = (cy + pan.y).coerceIn(0f, (parentH - hPx).coerceAtLeast(0f)),
                            )
                        }
                    }
                },
        ) {
            if (!state.hasPermission) {
                PermissionPrompt(Modifier.fillMaxSize(), onGranted = vm.camera::onPermissionGranted)
            } else {
                CameraPreviewBox(state, vm.camera, rotation, window.grid, Modifier.fillMaxSize())
            }
            ShotFlash(state.capturing)

            // 上: 最小化・全画面・切り替え
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SmallRoundButton(Icons.Filled.Remove, stringResource(R.string.camera_minimize)) {
                    vm.updateWindow { it.copy(mode = WindowMode.MINIMIZED) }
                }
                if (state.lenses.map { it.facing }.distinct().size > 1) {
                    SmallRoundButton(Icons.Filled.Cameraswitch, stringResource(R.string.camera_flip)) { vm.camera.flipLens() }
                }
                SmallRoundButton(Icons.Filled.OpenInFull, stringResource(R.string.camera_fullscreen)) {
                    vm.updateWindow { it.copy(mode = WindowMode.FULLSCREEN) }
                }
            }
            // 下: シャッター
            ShutterButton(
                size = 48.dp,
                enabled = state.cameraOpen && !state.capturing,
                onClick = { vm.shoot(onCaptured) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
            // 右下: 大きさ変更
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            vm.updateWindow { it.copy(widthDp = (it.widthDp + drag.x / density.density).coerceIn(MIN_WIDTH.value, 600f)) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.CloseFullscreen, stringResource(R.string.camera_resize), tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** カメラ権限を確認し、まだなら一度だけ尋ねる */
@Composable
fun CameraPermissionEffect(vm: CameraViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.camera.onPermissionGranted()
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.camera.onPermissionGranted() else if (!permissionAsked) {
            permissionAsked = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
}

private var permissionAsked = false

@Composable
private fun PermissionPrompt(modifier: Modifier, onGranted: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted()
    }
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.camera_permission_required),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.camera_permission_grant)) }
    }
}

@Composable
fun ShutterButton(size: Dp, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .border(3.dp, Color.White, CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClickLabel = stringResource(R.string.camera_shutter), onClick = onClick),
    )
}

@Composable
fun SmallRoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0x88000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** 撮影した瞬間に画面を白く光らせる */
@Composable
fun ShotFlash(capturing: Boolean) {
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(capturing) {
        if (capturing) scope.launch {
            alpha.snapTo(0.7f)
            alpha.animateTo(0f)
        }
    }
    if (alpha.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = alpha.value)))
}

private val BUBBLE = 56.dp
private val MIN_WIDTH = 120.dp
