package space.webkombinat.oshiire.camera

import android.graphics.PointF
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import space.webkombinat.oshiire.R.string as S
import space.webkombinat.oshiire.util.UiText
import space.webkombinat.oshiire.util.text

/**
 * 選択可能なレンズ（カメラ）。
 * 論理マルチカメラの物理カメラが cameraIdList に公開されていない場合は
 * [physicalId] を指定して論理カメラ経由で使う。
 */
data class LensInfo(
    val cameraId: String,
    val physicalId: String?,
    val facing: Int,
    val equivalentFocalMm: Float,
    val isLogicalMulti: Boolean,
) {
    val key: String get() = if (physicalId == null) cameraId else "$cameraId/$physicalId"
    val isFront: Boolean get() = facing == CameraMetadata.LENS_FACING_FRONT

    val label: UiText
        get() {
            val facingLabel = when (facing) {
                CameraMetadata.LENS_FACING_FRONT -> text(S.lens_front)
                CameraMetadata.LENS_FACING_BACK -> text(S.lens_back)
                else -> text(S.lens_external)
            }
            val focal = if (equivalentFocalMm > 0f) "${equivalentFocalMm.toInt()}mm" else "#$cameraId"
            return if (isLogicalMulti) text(S.lens_label_multi, facingLabel, focal) else text(S.lens_label, facingLabel, focal)
        }
}

data class ModeOption(val value: Int, val label: UiText)

data class PhotoCapabilities(
    val sensorOrientation: Int,
    val zoomRange: Range<Float>,
    /** true = CONTROL_ZOOM_RATIO を使用、false = SCALER_CROP_REGION */
    val zoomRatioSupported: Boolean,
    val zoomPresets: List<Float>,
    val flashAvailable: Boolean,
    val evRange: Range<Int>,
    val evStep: Float,
    val afModes: List<Int>,
    /** null = 固定焦点。値は diopter (1/m) */
    val minFocusDistance: Float?,
    /** null = マニュアル露出に非対応 */
    val isoRange: Range<Int>?,
    val exposureTimeRange: Range<Long>?,
    val awbModes: List<ModeOption>,
    val sceneModes: List<ModeOption>,
    val hdrSupported: Boolean,
    val stabilization: Boolean,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
    val aeLockAvailable: Boolean,
    val previewSizes: List<Size>,
    val jpegSize: Size,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
)

enum class FlashMode { OFF, AUTO, ON, TORCH }

data class PhotoSettings(
    val zoomRatio: Float = 1f,
    val flash: FlashMode = FlashMode.OFF,
    val evCompensation: Int = 0,
    /** 正規化座標 (0..1, センサー基準) でのタップ測光点。null = 全体 */
    val meteringPoint: PointF? = null,
    /** 長押しでピント・明るさを固定中 */
    val locked: Boolean = false,
    /** null = オートフォーカス */
    val manualFocus: Float? = null,
    /** null = 自動露出 */
    val manualIso: Int? = null,
    val manualExposureNs: Long? = null,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    /** CONTROL_SCENE_MODE。DISABLED なら通常撮影 */
    val sceneMode: Int = CameraMetadata.CONTROL_SCENE_MODE_DISABLED,
)

data class CameraUiState(
    val hasPermission: Boolean = false,
    val lenses: List<LensInfo> = emptyList(),
    val currentLens: LensInfo? = null,
    val caps: PhotoCapabilities? = null,
    val settings: PhotoSettings = PhotoSettings(),
    val previewSize: Size? = null,
    val cameraOpen: Boolean = false,
    /** プレビューの映像が届いている */
    val previewStreaming: Boolean = false,
    val capturing: Boolean = false,
    val error: UiText? = null,
)
