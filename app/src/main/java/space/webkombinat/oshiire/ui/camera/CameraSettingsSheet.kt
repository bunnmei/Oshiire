package space.webkombinat.oshiire.ui.camera

import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.webkombinat.oshiire.R
import space.webkombinat.oshiire.camera.CameraController
import space.webkombinat.oshiire.camera.CameraUiState
import space.webkombinat.oshiire.util.asString
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/** 詳細設定（ピント・露出・ホワイトバランス・シーン）。端末が対応している項目だけ出す */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CameraSettingsSheet(state: CameraUiState, camera: CameraController, onDismiss: () -> Unit) {
    val caps = state.caps ?: return onDismiss()
    val s = state.settings
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ピント
            caps.minFocusDistance?.let { minFocus ->
                SwitchRow(stringResource(R.string.camera_manual_focus), s.manualFocus != null) { on ->
                    camera.setManualFocus(if (on) minFocus / 2 else null)
                }
                s.manualFocus?.let { d ->
                    Text(stringResource(R.string.camera_focus_distance, formatFocus(d)), style = MaterialTheme.typography.bodySmall)
                    // 左 = 遠く (0 diopter)、右 = 近く
                    Slider(value = d, onValueChange = camera::setManualFocus, valueRange = 0f..minFocus)
                }
            }

            // 露出
            val isoRange = caps.isoRange
            val expRange = caps.exposureTimeRange
            if (isoRange != null && expRange != null) {
                val manual = s.manualIso != null && s.manualExposureNs != null
                SwitchRow(stringResource(R.string.camera_manual_exposure), manual) { on ->
                    if (on) camera.setManualExposure(isoRange.clamp(200), expRange.clamp(16_666_666L)) else camera.setManualExposure(null, null)
                }
                if (manual) {
                    Text("ISO ${s.manualIso}", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = s.manualIso!!.toFloat(),
                        onValueChange = { camera.setManualExposure(it.toInt(), s.manualExposureNs) },
                        valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
                    )
                    // シャッタースピードは対数で動かす
                    val lo = ln(expRange.lower.toDouble())
                    val hi = ln(minOf(expRange.upper, 1_000_000_000L).toDouble())
                    Text(stringResource(R.string.camera_shutter_speed, formatShutter(s.manualExposureNs!!)), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = ((ln(s.manualExposureNs.toDouble()) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f),
                        onValueChange = { t -> camera.setManualExposure(s.manualIso, exp(lo + (hi - lo) * t).roundToLong()) },
                    )
                }
            }

            // ホワイトバランス
            if (caps.awbModes.size > 1) {
                Text(stringResource(R.string.camera_white_balance), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    caps.awbModes.forEach { m ->
                        FilterChip(selected = s.awbMode == m.value, onClick = { camera.setAwbMode(m.value) }, label = { Text(m.label.asString()) })
                    }
                }
            }

            // シーン
            if (caps.sceneModes.isNotEmpty()) {
                Text(stringResource(R.string.camera_scene), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = s.sceneMode == CameraMetadata.CONTROL_SCENE_MODE_DISABLED,
                        onClick = { camera.setSceneMode(CameraMetadata.CONTROL_SCENE_MODE_DISABLED) },
                        label = { Text(stringResource(R.string.scene_none)) },
                    )
                    caps.sceneModes.forEach { m ->
                        FilterChip(selected = s.sceneMode == m.value, onClick = { camera.setSceneMode(m.value) }, label = { Text(m.label.asString()) })
                    }
                }
            }

            Text(
                stringResource(
                    R.string.camera_info,
                    state.currentLens?.label?.asString() ?: "-",
                    "${caps.jpegSize.width}×${caps.jpegSize.height}",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.camera_gesture_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

fun formatShutter(ns: Long): String =
    if (ns >= 500_000_000L) "%.1fs".format(ns / 1e9) else "1/${(1e9 / ns).roundToLong()}"

/** diopter → 距離表示 */
fun formatFocus(d: Float): String = if (d <= 0.01f) "∞" else "%.2fm".format(1f / d)
