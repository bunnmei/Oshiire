package space.webkombinat.oshiire.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import space.webkombinat.oshiire.data.NoteRepository
import space.webkombinat.oshiire.data.SettingsRepository
import space.webkombinat.oshiire.data.ThemeMode
import space.webkombinat.oshiire.model.NoteContent
import space.webkombinat.oshiire.model.NoteSummary
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun themeMode_defaultsToSystem_andCanBeChanged() = runTest(dispatcher) {
        val settings = FakeSettings()
        val vm = SettingsViewModel(settings, FakeNotes())
        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)

        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)) {
            vm.setThemeMode(mode)
            advanceUntilIdle()
            assertEquals(mode, vm.themeMode.value)
            assertEquals(mode, settings.mode.value)
        }
    }

    @Test
    fun vaultPath_isShown() {
        val vm = SettingsViewModel(FakeSettings(), FakeNotes())
        assertEquals(File("/tmp/vault").absolutePath, vm.vaultPath)
    }

    private class FakeSettings : SettingsRepository {
        val mode = MutableStateFlow(ThemeMode.SYSTEM)
        override val themeMode = mode
        override suspend fun setThemeMode(mode: ThemeMode) {
            this.mode.value = mode
        }
    }

    private class FakeNotes : NoteRepository {
        override val notes: StateFlow<List<NoteSummary>> = MutableStateFlow(emptyList())
        override val imageChanges: SharedFlow<String> = MutableSharedFlow()
        override val vaultDir = File("/tmp/vault")
        override suspend fun refresh() = Unit
        override suspend fun create() = ""
        override suspend fun load(id: String) = null
        override suspend fun save(id: String, content: NoteContent) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun restore(id: String) = Unit
        override fun resolve(path: String) = File(path)
        override suspend fun newImageFile() = "" to File("")
        override suspend fun importImage(input: InputStream) = ""
        override suspend fun notifyImageChanged(path: String) = Unit
        override suspend fun imageVersion(path: String) = 0L
    }
}
