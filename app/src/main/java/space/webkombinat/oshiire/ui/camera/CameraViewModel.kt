package space.webkombinat.oshiire.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.webkombinat.oshiire.camera.CameraController
import space.webkombinat.oshiire.camera.CameraUiState
import space.webkombinat.oshiire.data.NoteRepository
import javax.inject.Inject
import javax.inject.Singleton

/** フローティング窓の表示モード */
enum class WindowMode { WINDOW, MINIMIZED, FULLSCREEN, HIDDEN }

data class CameraWindowState(
    val mode: WindowMode = WindowMode.WINDOW,
    /** 親領域に対する左上の位置（px）。null = 右下に置く */
    val x: Float? = null,
    val y: Float? = null,
    /** 窓の幅（dp） */
    val widthDp: Float = 160f,
    val grid: Boolean = false,
    /** セルフタイマー（秒）。0 = なし */
    val timerSec: Int = 0,
)

/** 窓の位置・大きさなどはメモを開き直しても保つ */
@Singleton
class CameraWindowStore @Inject constructor() {
    val state = MutableStateFlow(CameraWindowState())
}

@HiltViewModel
class CameraViewModel @Inject constructor(
    val camera: CameraController,
    private val repository: NoteRepository,
    private val windowStore: CameraWindowStore,
) : ViewModel() {

    val state: StateFlow<CameraUiState> = camera.state

    val window: StateFlow<CameraWindowState> = windowStore.state.asStateFlow()

    private val _lastShot = MutableStateFlow<String?>(null)
    /** 直前に撮った写真（全画面カメラの左下に出す） */
    val lastShot: StateFlow<String?> = _lastShot.asStateFlow()

    fun updateWindow(transform: (CameraWindowState) -> CameraWindowState) = windowStore.state.update(transform)

    /** 撮影して vault に保存し、相対パスを [onSaved] に渡す */
    fun shoot(onSaved: (String) -> Unit) {
        if (state.value.capturing) return
        viewModelScope.launch {
            val (path, file) = repository.newImageFile()
            if (camera.capture(file)) {
                _lastShot.value = path
                onSaved(path)
            } else {
                file.delete()
            }
        }
    }

    fun resolve(path: String) = repository.resolve(path)
}
