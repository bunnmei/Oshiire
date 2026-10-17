package space.webkombinat.oshiire.ui.camera

import android.hardware.camera2.CameraMetadata
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.HdrOff
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import space.webkombinat.oshiire.R
import space.webkombinat.oshiire.camera.FlashMode
import space.webkombinat.oshiire.util.asString
import kotlin.math.abs

/**
 * 全画面のカメラ。標準カメラアプリにある操作（フラッシュ、タイマー、グリッド、HDR、露出補正、
 * ズーム、レンズ切替）と、詳細設定（ピント・ISO・シャッタースピード・ホワイトバランス・シーン）を持つ。
 * 撮った写真は窓のときと同じくメモのカーソル位置に入る。
 */
@Composable
fun FullscreenCamera(
    vm: CameraViewModel,
    onCaptured: (String) -> Unit,
) {
    val window by vm.window.collectAsStateWithLifecycle()
    if (window.mode != WindowMode.FULLSCREEN) return
    val state by vm.state.collectAsStateWithLifecycle()
    val lastShot by vm.lastShot.collectAsStateWithLifecycle()
    val rotation = rememberDisplayRotation()
    val caps = state.caps
    val s = state.settings
    var showEv by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    CameraPermissionEffect(vm)

    val close = { vm.updateWindow { it.copy(mode = WindowMode.WINDOW) } }
    BackHandler(onBack = close)

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            if (countdown == 1) vm.shoot(onCaptured)
            countdown--
        }
    }
    val shoot = {
        if (window.timerSec > 0) countdown = window.timerSec else vm.shoot(onCaptured)
    }

    // 出てくるときだけフェードさせる（消えるときは即座に消し、窓の TextureView とプレビューを取り合わない）
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(160)) }

    Column(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }.background(Color.Black).safeDrawingPadding()) {
        // 上部ツールバー
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = close) { Icon(Icons.Filled.Close, stringResource(R.string.camera_close_fullscreen), tint = Color.White) }
            if (caps?.flashAvailable == true) {
                IconButton(onClick = { vm.camera.setFlash(FlashMode.entries[(s.flash.ordinal + 1) % FlashMode.entries.size]) }) {
                    Icon(
                        when (s.flash) {
                            FlashMode.OFF -> Icons.Filled.FlashOff
                            FlashMode.AUTO -> Icons.Filled.FlashAuto
                            FlashMode.ON -> Icons.Filled.FlashOn
                            FlashMode.TORCH -> Icons.Filled.FlashlightOn
                        },
                        stringResource(R.string.camera_flash),
                        tint = if (s.flash == FlashMode.OFF) Color.White else Color(0xFFFFD54F),
                    )
                }
            }
            IconButton(onClick = {
                val next = when (window.timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
                vm.updateWindow { it.copy(timerSec = next) }
            }) {
                Icon(
                    when (window.timerSec) { 3 -> Icons.Filled.Timer3; 10 -> Icons.Filled.Timer10; else -> Icons.Filled.Timer },
                    stringResource(R.string.camera_timer),
                    tint = if (window.timerSec > 0) Color(0xFFFFD54F) else Color.White,
                )
            }
            IconButton(onClick = { vm.updateWindow { it.copy(grid = !it.grid) } }) {
                Icon(if (window.grid) Icons.Filled.Grid3x3 else Icons.Filled.GridOff, stringResource(R.string.camera_grid), tint = Color.White)
            }
            if (caps?.hdrSupported == true) {
                val hdr = s.sceneMode == CameraMetadata.CONTROL_SCENE_MODE_HDR
                IconButton(onClick = {
                    vm.camera.setSceneMode(if (hdr) CameraMetadata.CONTROL_SCENE_MODE_DISABLED else CameraMetadata.CONTROL_SCENE_MODE_HDR)
                }) {
                    Icon(if (hdr) Icons.Filled.HdrOn else Icons.Filled.HdrOff, stringResource(R.string.camera_hdr),
                        tint = if (hdr) Color(0xFFFFD54F) else Color.White)
                }
            }
            if (caps != null && caps.evRange.upper > caps.evRange.lower) {
                IconButton(onClick = { showEv = !showEv }) {
                    Icon(Icons.Filled.Exposure, stringResource(R.string.camera_ev), tint = if (showEv || s.evCompensation != 0) Color(0xFFFFD54F) else Color.White)
                }
            }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Tune, stringResource(R.string.camera_settings), tint = Color.White) }
        }

        // プレビュー
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .aspectRatio(previewAspect(state, rotation))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) vm.camera.setZoom(vm.state.value.settings.zoomRatio * zoom)
                        }
                    },
            ) {
                if (state.hasPermission) {
                    CameraPreviewBox(state, vm.camera, rotation, window.grid, Modifier.fillMaxSize())
                }
                ShotFlash(state.capturing)
                if (countdown > 0) {
                    Text("$countdown", color = Color.White, fontSize = 96.sp, modifier = Modifier.align(Alignment.Center))
                }
                if (s.locked) {
                    Text(
                        stringResource(R.string.camera_locked),
                        color = Color.Black,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                            .background(Color(0xFFFFD54F), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                state.error?.let {
                    Text(it.asString(), color = Color(0xFFFFAB91), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                }
                // ズームの倍率ボタン
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(8.dp)
                        .background(Color(0x66000000), RoundedCornerShape(50)).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    caps?.zoomPresets?.forEach { z ->
                        val selected = abs(s.zoomRatio - z) < 0.05f
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(50))
                                .background(if (selected) Color(0x55FFFFFF) else Color.Transparent)
                                .clickable { vm.camera.setZoom(z) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(formatZoom(if (selected) s.zoomRatio else z), color = if (selected) Color(0xFFFFD54F) else Color.White,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        if (showEv && caps != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("EV", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = s.evCompensation.toFloat(),
                    onValueChange = { vm.camera.setEv(it.toInt()) },
                    valueRange = caps.evRange.lower.toFloat()..caps.evRange.upper.toFloat(),
                    steps = (caps.evRange.upper - caps.evRange.lower - 1).coerceAtLeast(0),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD54F), activeTrackColor = Color(0xFFFFD54F)),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("%+.1f".format(s.evCompensation * caps.evStep), color = Color.White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(40.dp))
            }
        }

        // レンズ
        if (state.lenses.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.lenses.forEach { l ->
                    FilterChip(
                        selected = l.key == state.currentLens?.key,
                        onClick = { vm.camera.selectLens(l) },
                        label = { Text(l.label.asString()) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0x33FFFFFF), labelColor = Color.White,
                            selectedContainerColor = Color(0xFFFFD54F), selectedLabelColor = Color.Black,
                        ),
                    )
                }
            }
        }

        // シャッター列
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF)).clickable(onClick = close)) {
                lastShot?.let {
                    AsyncImage(model = vm.resolve(it), contentDescription = stringResource(R.string.camera_last_shot),
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            ShutterButton(size = 76.dp, enabled = state.cameraOpen && !state.capturing && countdown == 0, onClick = shoot)
            if (state.lenses.map { it.facing }.distinct().size > 1) {
                IconButton(onClick = vm.camera::flipLens, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Cameraswitch, stringResource(R.string.camera_flip), tint = Color.White, modifier = Modifier.size(32.dp))
                }
            } else Spacer(Modifier.size(56.dp))
        }
    }

    if (showSettings) {
        CameraSettingsSheet(state, vm.camera, onDismiss = { showSettings = false })
    }
}

fun formatZoom(z: Float): String =
    if (abs(z - z.toInt()) < 0.05f) "${z.toInt()}x" else "%.1fx".format(z)
