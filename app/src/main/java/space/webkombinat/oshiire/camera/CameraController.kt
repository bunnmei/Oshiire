package space.webkombinat.oshiire.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.MediaActionSound
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import space.webkombinat.oshiire.R.string as S
import space.webkombinat.oshiire.util.UiText
import space.webkombinat.oshiire.util.raw
import space.webkombinat.oshiire.util.text
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Camera2 による写真撮影。すべての状態は専用の "Camera" スレッド上でのみ変更する。
 * UI からの呼び出しはスレッドセーフ（内部でポストする）。
 *
 * プレビューの映像は [previewTexture]（アプリで 1 つだけの SurfaceTexture）に流す。画面側の TextureView は
 * これを貼って表示するだけなので、窓 ⇔ 全画面の切り替えではカメラを開き直さない。
 * 表示している画面がなくなると、少し待ってからカメラスレッドで閉じる（UI スレッドは待たせない）。
 */
@Singleton
class CameraController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: CameraManager,
) {
    private val thread = HandlerThread("Camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val sound by lazy { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    /** プレビュー先。作成時は GL コンテキストに未接続なので、どの TextureView にも貼れる */
    val previewTexture: SurfaceTexture = SurfaceTexture(false)
    private val previewSurface = Surface(previewTexture)

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    // ---- 以下 Camera スレッド専用 ----
    private var hasPermission = false
    private var lenses: List<LensInfo> = emptyList()
    private var lens: LensInfo? = null
    private var caps: PhotoCapabilities? = null
    private var physicalKeys: Set<CaptureRequest.Key<*>> = emptySet()
    private var usePhysicalStream = false

    private var device: CameraDevice? = null
    private var openToken = 0
    private var opening = false
    private var session: CameraCaptureSession? = null
    private var sessionSurfaces: List<Surface> = emptyList()
    private var sessionGen = 0
    private val closeActions = HashMap<CameraCaptureSession, MutableList<() -> Unit>>()

    private var settings = PhotoSettings()
    private var previewSize: Size? = null
    /** プレビューを表示している画面の数 */
    private var attachedViews = 0
    private var previewWanted = false
    /** 今のセッションで最初のフレームが届いたか（届くまで画面にローディングを出す） */
    private var streaming = false
    private var reader: ImageReader? = null
    private var pending: PendingCapture? = null
    private var error: UiText? = null

    /** 端末の物理的な向き (OrientationEventListener 基準, 0/90/180/270)。-1 = 不明 */
    private var deviceOrientation = -1

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(deg: Int) {
            if (deg == ORIENTATION_UNKNOWN) return
            val bucket = ((deg + 45) / 90 * 90) % 360
            post { deviceOrientation = bucket }
        }
    }

    private enum class Stage { WAIT_AF, WAIT_PRECAPTURE, WAIT_NON_PRECAPTURE, CAPTURING }

    private class PendingCapture(
        val file: File,
        val result: CompletableDeferred<Boolean>,
        val triggeredAf: Boolean,
    ) {
        var stage = Stage.CAPTURING
        var stageStartedAt = SystemClock.elapsedRealtime()
    }

    // =========================================================== 公開 API

    fun onPermissionGranted() = post {
        if (hasPermission) return@post
        hasPermission = true
        lenses = runCatching { CapabilitiesParser.enumerateLenses(manager) }.getOrElse {
            error = text(S.error_enumerate_cameras, it.message ?: it.toString())
            emptyList()
        }
        (lenses.firstOrNull { it.facing == CameraMetadata.LENS_FACING_BACK } ?: lenses.firstOrNull())
            ?.let { selectLensInternal(it) }
        publish()
    }

    fun selectLens(target: LensInfo) = post {
        if (target.key == lens?.key) return@post
        selectLensInternal(target)
    }

    /** 背面 ⇔ 前面 */
    fun flipLens() = post {
        val cur = lens ?: return@post
        lenses.firstOrNull { it.facing != cur.facing }?.let { selectLensInternal(it) }
    }

    /** プレビューを表示する画面が現れた */
    fun attachPreview() = post {
        attachedViews++
        handler.removeCallbacks(closeIfUnused)
        if (!previewWanted) {
            previewWanted = true
            ensureCamera()
        }
    }

    /**
     * プレビューを表示する画面が消えた。窓 ⇔ 全画面の切り替えのようにすぐ次の画面が来ることがあるので、
     * 少し待ってから閉じる。
     */
    fun detachPreview() = post {
        attachedViews = (attachedViews - 1).coerceAtLeast(0)
        if (attachedViews == 0) {
            handler.removeCallbacks(closeIfUnused)
            handler.postDelayed(closeIfUnused, CLOSE_DELAY_MS)
        }
    }

    private val closeIfUnused = Runnable {
        if (attachedViews == 0 && previewWanted) {
            previewWanted = false
            runCatching { ensureCamera() }.onFailure { Log.w(TAG, "close failed", it) }
        }
    }

    fun setZoom(ratio: Float) = updateSettings { s -> s.copy(zoomRatio = caps?.zoomRange?.clamp(ratio) ?: ratio) }
    fun setFlash(mode: FlashMode) = updateSettings { it.copy(flash = mode) }
    fun setEv(ev: Int) = updateSettings { it.copy(evCompensation = caps?.evRange?.clamp(ev) ?: ev) }
    fun setManualFocus(diopter: Float?) = updateSettings { it.copy(manualFocus = diopter, locked = false) }
    fun setManualExposure(iso: Int?, exposureNs: Long?) = updateSettings { it.copy(manualIso = iso, manualExposureNs = exposureNs) }
    fun setAwbMode(mode: Int) = updateSettings { it.copy(awbMode = mode) }
    fun setSceneMode(mode: Int) = updateSettings { it.copy(sceneMode = mode) }

    /** プレビュー上の正規化座標をタップ → その位置でピント・明るさを合わせる。[lock] なら固定する */
    fun focusAt(nx: Float, ny: Float, displayRotationDeg: Int, lock: Boolean) = post {
        val c = caps ?: return@post
        val l = lens ?: return@post
        val p = MeteringMath.viewToSensor(nx, ny, c.sensorOrientation, displayRotationDeg, l.isFront)
        settings = settings.copy(meteringPoint = p, locked = lock, manualFocus = null)
        applyRepeating()
        triggerAf()
        publish()
    }

    /** タップ測光と固定を解除してコンティニュアス AF に戻す */
    fun resetFocus() = post {
        settings = settings.copy(meteringPoint = null, locked = false)
        cancelAf()
        applyRepeating()
        publish()
    }

    /** 撮影して [file] に JPEG を書く。成功したら true */
    suspend fun capture(file: File): Boolean {
        val result = CompletableDeferred<Boolean>()
        post { startCapture(file, result) }
        return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { result.await() } ?: run {
            post { finishCapture(false) }
            false
        }
    }

    // =========================================================== 内部処理

    private fun post(block: () -> Unit) {
        handler.post {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "camera op failed", e)
                error = raw(e.message ?: e.toString())
                publish()
            }
        }
    }

    private fun updateSettings(transform: (PhotoSettings) -> PhotoSettings) = post {
        settings = transform(settings)
        applyRepeating()
        publish()
    }

    private fun selectLensInternal(target: LensInfo) {
        finishCapture(false)
        closeCamera()
        lens = target
        error = null
        val logical = manager.getCameraCharacteristics(target.cameraId)
        val capsChars = target.physicalId?.let { manager.getCameraCharacteristics(it) } ?: logical
        val physicalFocals = if (target.isLogicalMulti) {
            logical.physicalCameraIds.mapNotNull { pid ->
                runCatching { CapabilitiesParser.equivalentFocal(manager.getCameraCharacteristics(pid)) }.getOrNull()
            }.filter { it > 0f }
        } else emptyList()
        val parsed = CapabilitiesParser.parse(capsChars, physicalFocals)
        caps = parsed
        physicalKeys = if (target.physicalId != null) logical.availablePhysicalCameraRequestKeys.orEmpty().toSet() else emptySet()
        usePhysicalStream = target.physicalId != null
        // フラッシュの設定はレンズを替えても引き継ぐ
        settings = PhotoSettings(
            zoomRatio = parsed.zoomRange.clamp(1f),
            flash = if (parsed.flashAvailable) settings.flash else FlashMode.OFF,
        )
        previewSize = CapabilitiesParser.choosePreviewSize(parsed.previewSizes, parsed.jpegSize).also {
            previewTexture.setDefaultBufferSize(it.width, it.height)
        }
        reader?.let { old -> releaseAfterSessionClosed { old.close() } }
        reader = ImageReader.newInstance(parsed.jpegSize.width, parsed.jpegSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r -> onJpeg(r) }, handler)
        }
        ensureCamera()
        publish()
    }

    private fun releaseAfterSessionClosed(action: () -> Unit) {
        val s = session
        if (s == null) action() else closeActions.getOrPut(s) { mutableListOf() } += action
    }

    private fun runCloseActions(s: CameraCaptureSession) {
        closeActions.remove(s)?.forEach { runCatching(it) }
    }

    private fun ensureCamera() {
        val needed = hasPermission && lens != null && previewWanted
        when {
            needed && device == null && !opening -> openCamera()
            !needed && (device != null || opening) -> closeCamera()
            device != null -> createSession()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val l = lens ?: return
        opening = true
        val token = ++openToken
        try {
            manager.openCamera(l.cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (token != openToken) {
                        d.close(); return
                    }
                    opening = false
                    device = d
                    orientationListener.enable()
                    publish()
                    createSession()
                }

                override fun onDisconnected(d: CameraDevice) = onDeviceGone(d, token, text(S.error_camera_disconnected))
                override fun onError(d: CameraDevice, code: Int) = onDeviceGone(d, token, text(S.error_camera, code))
            })
        } catch (e: Exception) {
            opening = false
            error = text(S.error_open_camera, e.message ?: e.toString())
            publish()
        }
    }

    private fun onDeviceGone(d: CameraDevice, token: Int, message: UiText) {
        d.close()
        if (token != openToken) return
        opening = false
        if (device === d) {
            device = null
            session = null
            sessionSurfaces = emptyList()
        }
        finishCapture(false)
        error = message
        publish()
    }

    private fun closeCamera() {
        openToken++
        opening = false
        streaming = false
        finishCapture(false)
        session?.let { runCatching { it.close() } }
        session = null
        sessionSurfaces = emptyList()
        device?.close()
        device = null
        orientationListener.disable()
        publish()
    }

    private fun createSession() {
        val d = device ?: return
        val r = reader
        val gen = ++sessionGen
        session?.let { runCatching { it.close() } }
        session = null
        sessionSurfaces = emptyList()
        streaming = false
        if (r == null) return
        val surfaces = listOf(previewSurface, r.surface)
        val physicalId = lens?.physicalId?.takeIf { usePhysicalStream }
        val outputs = surfaces.map { s ->
            OutputConfiguration(s).also { oc -> physicalId?.let { oc.setPhysicalCameraId(it) } }
        }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != sessionGen || device !== d) {
                        s.close(); return
                    }
                    session = s
                    sessionSurfaces = surfaces
                    error = null
                    applyRepeating()
                    publish()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen == sessionGen) {
                        if (physicalId != null) {
                            // 物理カメラ単体での出力に失敗したら論理カメラとして再試行
                            usePhysicalStream = false
                            createSession()
                        } else {
                            error = text(S.error_session)
                        }
                        publish()
                    }
                    runCloseActions(s)
                }

                override fun onClosed(s: CameraCaptureSession) = runCloseActions(s)
            },
        )
        if (physicalId != null && Build.VERSION.SDK_INT >= 29) {
            val supported = runCatching { d.isSessionConfigurationSupported(config) }.getOrDefault(true)
            if (!supported) {
                usePhysicalStream = false
                createSession()
                return
            }
        }
        d.createCaptureSession(config)
    }

    private fun newRequestBuilder(d: CameraDevice, template: Int): CaptureRequest.Builder {
        val pid = lens?.physicalId?.takeIf { usePhysicalStream }
        return if (pid != null) d.createCaptureRequest(template, setOf(pid)) else d.createCaptureRequest(template)
    }

    private fun <T> CaptureRequest.Builder.put(key: CaptureRequest.Key<T>, value: T) {
        set(key, value)
        val pid = lens?.physicalId?.takeIf { usePhysicalStream } ?: return
        if (key in physicalKeys) runCatching { setPhysicalCameraKey(key, value, pid) }
    }

    /** プレビュー用（[still] なら静止画用）のリクエスト */
    private fun buildRequest(still: Boolean = false, configure: CaptureRequest.Builder.() -> Unit = {}): CaptureRequest? {
        val d = device ?: return null
        if (sessionSurfaces.isEmpty()) return null
        val b = newRequestBuilder(d, if (still) CameraDevice.TEMPLATE_STILL_CAPTURE else CameraDevice.TEMPLATE_PREVIEW)
        if (still) sessionSurfaces.forEach(b::addTarget) else b.addTarget(sessionSurfaces.first())
        applySettings(b, still)
        b.configure()
        return b.build()
    }

    private fun applyRepeating() {
        val s = session ?: return
        val req = buildRequest() ?: return
        try {
            s.setRepeatingRequest(req, captureCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "setRepeatingRequest failed", e)
        }
    }

    private fun afMode(): Int {
        val c = caps ?: return CameraMetadata.CONTROL_AF_MODE_OFF
        val s = settings
        return when {
            s.manualFocus != null && c.minFocusDistance != null -> CameraMetadata.CONTROL_AF_MODE_OFF
            s.meteringPoint != null && CameraMetadata.CONTROL_AF_MODE_AUTO in c.afModes -> CameraMetadata.CONTROL_AF_MODE_AUTO
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in c.afModes -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CameraMetadata.CONTROL_AF_MODE_AUTO in c.afModes -> CameraMetadata.CONTROL_AF_MODE_AUTO
            else -> CameraMetadata.CONTROL_AF_MODE_OFF
        }
    }

    private fun manualExposure(): Boolean =
        caps?.isoRange != null && settings.manualIso != null && settings.manualExposureNs != null

    private fun triggerAf() {
        val s = session ?: return
        if (afMode() == CameraMetadata.CONTROL_AF_MODE_OFF) return
        cancelAf()
        buildRequest { put(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START) }
            ?.let { runCatching { s.capture(it, captureCallback, handler) } }
    }

    private fun cancelAf() {
        val s = session ?: return
        if (afMode() == CameraMetadata.CONTROL_AF_MODE_OFF) return
        buildRequest { put(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL) }
            ?.let { runCatching { s.capture(it, captureCallback, handler) } }
    }

    private fun applySettings(b: CaptureRequest.Builder, still: Boolean) {
        val c = caps ?: return
        val s = settings

        // シーン（HDR・夜景など）
        if (s.sceneMode != CameraMetadata.CONTROL_SCENE_MODE_DISABLED) {
            b.put(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
            b.put(CaptureRequest.CONTROL_SCENE_MODE, s.sceneMode)
        } else {
            b.put(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        }

        // フォーカス
        val af = afMode()
        b.put(CaptureRequest.CONTROL_AF_MODE, af)
        if (af == CameraMetadata.CONTROL_AF_MODE_OFF && c.minFocusDistance != null) {
            b.put(CaptureRequest.LENS_FOCUS_DISTANCE, (s.manualFocus ?: 0f).coerceIn(0f, c.minFocusDistance))
        }

        // 露出とフラッシュ
        if (manualExposure()) {
            b.put(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            c.isoRange?.let { b.put(CaptureRequest.SENSOR_SENSITIVITY, it.clamp(s.manualIso!!)) }
            c.exposureTimeRange?.let { r ->
                val exp = r.clamp(s.manualExposureNs!!)
                b.put(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                b.put(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exp, 33_333_333L))
            }
        } else {
            val aeMode = when {
                !c.flashAvailable -> CameraMetadata.CONTROL_AE_MODE_ON
                s.flash == FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                s.flash == FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            b.put(CaptureRequest.CONTROL_AE_MODE, aeMode)
            b.put(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.evRange.clamp(s.evCompensation))
            if (c.aeLockAvailable) b.put(CaptureRequest.CONTROL_AE_LOCK, s.locked)
        }
        if (c.flashAvailable) {
            b.put(
                CaptureRequest.FLASH_MODE,
                if (s.flash == FlashMode.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
            )
        }

        b.put(CaptureRequest.CONTROL_AWB_MODE, s.awbMode)
        if (c.stabilization) {
            b.put(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }

        // ズーム
        var crop: android.graphics.Rect? = null
        if (c.zoomRatioSupported && Build.VERSION.SDK_INT >= 30) {
            b.put(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoomRange.clamp(s.zoomRatio))
        } else if (c.activeArrayWidth > 0 && s.zoomRatio > 1f) {
            crop = MeteringMath.cropForZoom(c.activeArrayWidth, c.activeArrayHeight, s.zoomRatio)
            b.put(CaptureRequest.SCALER_CROP_REGION, crop)
        }

        // タップ測光領域
        s.meteringPoint?.let { p ->
            if (c.activeArrayWidth > 0) {
                val rect = MeteringMath.meteringRect(p, c.activeArrayWidth, c.activeArrayHeight, crop)
                if (c.maxAfRegions > 0) b.put(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
                if (c.maxAeRegions > 0) b.put(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
            }
        }

        if (still) {
            b.put(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            b.put(CaptureRequest.JPEG_QUALITY, 92.toByte())
        }
    }

    /** センサーの向きと端末の持ち方から、写真を正立させる回転 */
    private fun jpegOrientation(): Int {
        val c = caps ?: return 0
        val dev = deviceOrientation.takeIf { it >= 0 } ?: orientationFromDisplay()
        return if (lens?.isFront == true) (c.sensorOrientation - dev + 360) % 360 else (c.sensorOrientation + dev) % 360
    }

    private fun orientationFromDisplay(): Int {
        val rot = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        val deg = when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (360 - deg) % 360
    }

    // ---- 撮影シーケンス: AF ロック → AE プリキャプチャ（フラッシュ時）→ 静止画撮影

    private fun startCapture(file: File, result: CompletableDeferred<Boolean>) {
        val s = session
        if (s == null || pending != null) {
            result.complete(false)
            return
        }
        val needAf = !settings.locked && afMode().let {
            it == CameraMetadata.CONTROL_AF_MODE_AUTO || it == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        val p = PendingCapture(file, result, triggeredAf = needAf)
        pending = p
        publish()
        if (needAf) {
            setStage(p, Stage.WAIT_AF)
            buildRequest { put(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START) }
                ?.let { s.capture(it, captureCallback, handler) }
        } else {
            precaptureOrShoot(p)
        }
    }

    private fun precaptureOrShoot(p: PendingCapture) {
        val needAe = !manualExposure() && !settings.locked && caps?.flashAvailable == true &&
            (settings.flash == FlashMode.AUTO || settings.flash == FlashMode.ON)
        val s = session ?: return finishCapture(false)
        if (needAe) {
            setStage(p, Stage.WAIT_PRECAPTURE)
            buildRequest {
                put(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }?.let { s.capture(it, captureCallback, handler) }
        } else {
            shoot(p)
        }
    }

    private fun shoot(p: PendingCapture) {
        val s = session ?: return finishCapture(false)
        setStage(p, Stage.CAPTURING)
        val req = buildRequest(still = true) ?: return finishCapture(false)
        s.capture(req, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                runCatching { sound.play(MediaActionSound.SHUTTER_CLICK) }
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                error = text(S.error_capture)
                finishCapture(false)
            }
        }, handler)
    }

    private fun setStage(p: PendingCapture, stage: Stage) {
        p.stage = stage
        p.stageStartedAt = SystemClock.elapsedRealtime()
    }

    private fun onJpeg(r: ImageReader) {
        val image = runCatching { r.acquireNextImage() }.getOrNull() ?: return
        image.use { img ->
            val p = pending ?: return
            val ok = runCatching {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                p.file.writeBytes(bytes)
            }.isSuccess
            if (!ok) error = text(S.error_capture)
            finishCapture(ok)
        }
    }

    private fun finishCapture(ok: Boolean) {
        val p = pending ?: return
        pending = null
        p.result.complete(ok)
        if (p.triggeredAf && !settings.locked) cancelAf()
        applyRepeating()
        publish()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (!streaming && s === session) {
                streaming = true
                publish()
            }
            val p = pending ?: return
            val elapsed = SystemClock.elapsedRealtime() - p.stageStartedAt
            when (p.stage) {
                Stage.WAIT_AF -> {
                    val af = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (af == null || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED || elapsed > AF_TIMEOUT_MS
                    ) precaptureOrShoot(p)
                }

                Stage.WAIT_PRECAPTURE -> {
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED
                    ) setStage(p, Stage.WAIT_NON_PRECAPTURE)
                    else if (elapsed > AE_TIMEOUT_MS) shoot(p)
                }

                Stage.WAIT_NON_PRECAPTURE -> {
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE || elapsed > AE_TIMEOUT_MS) shoot(p)
                }

                Stage.CAPTURING -> Unit
            }
        }
    }

    private fun publish() {
        _state.value = CameraUiState(
            hasPermission = hasPermission,
            lenses = lenses,
            currentLens = lens,
            caps = caps,
            settings = settings,
            previewSize = previewSize,
            cameraOpen = device != null,
            previewStreaming = streaming && session != null,
            capturing = pending != null,
            error = error,
        )
    }

    private companion object {
        const val TAG = "CameraController"
        const val AF_TIMEOUT_MS = 1500L
        const val AE_TIMEOUT_MS = 1500L
        const val CAPTURE_TIMEOUT_MS = 8000L
        const val CLOSE_DELAY_MS = 600L
    }
}
