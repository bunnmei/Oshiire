package space.webkombinat.oshiire.di

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import space.webkombinat.oshiire.data.DataStoreSettingsRepository
import space.webkombinat.oshiire.data.FileNoteRepository
import space.webkombinat.oshiire.data.NoteRepository
import space.webkombinat.oshiire.data.SettingsRepository
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** 画面を閉じても最後まで終わらせたい処理（メモの保存など）用のスコープ */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideCameraManager(@ApplicationContext context: Context): CameraManager =
        context.getSystemService(CameraManager::class.java)

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideNoteRepository(@ApplicationContext context: Context): NoteRepository {
        // Android/data/<package>/files/vault — USB で PC からも見える。Obsidian の vault としてそのまま開ける
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return FileNoteRepository(File(root, "vault"), Dispatchers.IO)
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        DataStoreSettingsRepository(context.settingsStore)
}
