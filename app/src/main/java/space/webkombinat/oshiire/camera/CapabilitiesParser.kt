package space.webkombinat.oshiire.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata as M
import android.os.Build
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import space.webkombinat.oshiire.R.string as S
import space.webkombinat.oshiire.util.text
import kotlin.math.abs
import kotlin.math.sqrt

/** CameraCharacteristics から、写真撮影で使う機能だけを取り出す */
object CapabilitiesParser {

    fun enumerateLenses(manager: CameraManager): List<LensInfo> {
        val ids = manager.cameraIdList.toList()
        val result = mutableListOf<LensInfo>()
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val caps = chars.get(C.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (M.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) continue
            val isMulti = M.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
            result += lensOf(id, null, chars, isMulti)
            if (isMulti) {
                for (pid in chars.physicalCameraIds) {
                    if (pid in ids) continue // 単体で公開済み
                    val pChars = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    result += lensOf(id, pid, pChars, false)
                }
            }
        }
        // 背面 → 前面 → 外部、同じ向きはマルチ → 焦点距離順
        return result.sortedWith(
            compareBy<LensInfo> {
                when (it.facing) {
                    M.LENS_FACING_BACK -> 0
                    M.LENS_FACING_FRONT -> 1
                    else -> 2
                }
            }.thenBy { if (it.isLogicalMulti) 0 else 1 }.thenBy { it.equivalentFocalMm }
        )
    }

    private fun lensOf(id: String, physicalId: String?, chars: C, isMulti: Boolean) = LensInfo(
        cameraId = id,
        physicalId = physicalId,
        facing = chars.get(C.LENS_FACING) ?: M.LENS_FACING_EXTERNAL,
        equivalentFocalMm = equivalentFocal(chars),
        isLogicalMulti = isMulti,
    )

    /** 35mm 換算焦点距離 */
    fun equivalentFocal(chars: C): Float {
        val focal = chars.get(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return 0f
        val size = chars.get(C.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0f
        val diag = sqrt(size.width * size.width + size.height * size.height)
        if (diag <= 0f) return 0f
        return focal * 43.27f / diag
    }

    fun parse(chars: C, physicalFocals: List<Float>): PhotoCapabilities {
        val capsList = chars.get(C.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val aeModes = chars.get(C.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        val manualSensor = M.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capsList
        val manualExposure = manualSensor && M.CONTROL_AE_MODE_OFF in aeModes

        val ratioRange: Range<Float>? = if (Build.VERSION.SDK_INT >= 30) chars.get(C.CONTROL_ZOOM_RATIO_RANGE) else null
        val zoomRange = ratioRange ?: Range(1f, chars.get(C.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)

        val sceneValues = chars.get(C.CONTROL_AVAILABLE_SCENE_MODES)?.toList().orEmpty()
        val sceneModes = SCENES.filter { it.first in sceneValues }.map { (v, label) -> ModeOption(v, text(label)) }
        val awbValues = chars.get(C.CONTROL_AWB_AVAILABLE_MODES)?.toList().orEmpty()
        val awbModes = AWB.filter { it.first in awbValues }.map { (v, label) -> ModeOption(v, text(label)) }

        val map = chars.get(C.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG).orEmpty().toList()
        val jpegSize = chooseJpegSize(jpegSizes)
        val previewSizes = map?.getOutputSizes(SurfaceHolder::class.java).orEmpty().toList()

        val stab = chars.get(C.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(M.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        val active = chars.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        return PhotoCapabilities(
            sensorOrientation = chars.get(C.SENSOR_ORIENTATION) ?: 0,
            zoomRange = zoomRange,
            zoomRatioSupported = ratioRange != null,
            zoomPresets = zoomPresets(zoomRange, equivalentFocal(chars), physicalFocals),
            flashAvailable = chars.get(C.FLASH_INFO_AVAILABLE) == true,
            evRange = chars.get(C.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            evStep = chars.get(C.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
            afModes = chars.get(C.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty(),
            minFocusDistance = chars.get(C.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.takeIf { it > 0f },
            isoRange = if (manualExposure) chars.get(C.SENSOR_INFO_SENSITIVITY_RANGE) else null,
            exposureTimeRange = if (manualExposure) chars.get(C.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null,
            awbModes = awbModes,
            sceneModes = sceneModes.filter { it.value != M.CONTROL_SCENE_MODE_HDR },
            hdrSupported = M.CONTROL_SCENE_MODE_HDR in sceneValues,
            stabilization = stab,
            maxAfRegions = chars.get(C.CONTROL_MAX_REGIONS_AF) ?: 0,
            maxAeRegions = chars.get(C.CONTROL_MAX_REGIONS_AE) ?: 0,
            aeLockAvailable = chars.get(C.CONTROL_AE_LOCK_AVAILABLE) == true,
            previewSizes = previewSizes,
            jpegSize = jpegSize,
            activeArrayWidth = active?.width() ?: 0,
            activeArrayHeight = active?.height() ?: 0,
        )
    }

    /** 4:3 の中で最大の JPEG サイズ（なければ全体で最大） */
    fun chooseJpegSize(sizes: List<Size>): Size =
        sizes.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1440)

    /** 撮影サイズと同じ縦横比で、1920 幅以下の最大のプレビューサイズ */
    fun choosePreviewSize(previewSizes: List<Size>, jpeg: Size): Size {
        val aspect = jpeg.width.toFloat() / jpeg.height
        return previewSizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= 1920 }
            .maxByOrNull { it.width * it.height }
            ?: previewSizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: Size(1440, 1080)
    }

    private fun zoomPresets(zoomRange: Range<Float>, baseFocal: Float, physicalFocals: List<Float>): List<Float> {
        val presets = sortedSetOf<Float>()
        if (zoomRange.lower < 0.99f) presets += zoomRange.lower
        presets += 1f
        if (baseFocal > 0f) {
            for (f in physicalFocals) {
                val r = (f / baseFocal * 10f).toInt() / 10f
                if (r > 1.1f && zoomRange.contains(r)) presets += r
            }
        }
        if (zoomRange.contains(2f)) presets += 2f
        return presets.fold(mutableListOf()) { acc, v ->
            if (acc.none { abs(it - v) < 0.15f }) acc += v
            acc
        }
    }

    private val SCENES = listOf(
        M.CONTROL_SCENE_MODE_HDR to S.scene_hdr,
        M.CONTROL_SCENE_MODE_NIGHT to S.scene_night,
        M.CONTROL_SCENE_MODE_PORTRAIT to S.scene_portrait,
        M.CONTROL_SCENE_MODE_LANDSCAPE to S.scene_landscape,
        M.CONTROL_SCENE_MODE_ACTION to S.scene_action,
        M.CONTROL_SCENE_MODE_SPORTS to S.scene_sports,
        M.CONTROL_SCENE_MODE_SUNSET to S.scene_sunset,
        M.CONTROL_SCENE_MODE_CANDLELIGHT to S.scene_candlelight,
    )

    private val AWB = listOf(
        M.CONTROL_AWB_MODE_AUTO to S.awb_auto,
        M.CONTROL_AWB_MODE_DAYLIGHT to S.awb_daylight,
        M.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to S.awb_cloudy,
        M.CONTROL_AWB_MODE_SHADE to S.awb_shade,
        M.CONTROL_AWB_MODE_INCANDESCENT to S.awb_incandescent,
        M.CONTROL_AWB_MODE_FLUORESCENT to S.awb_fluorescent,
    )
}
