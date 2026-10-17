package space.webkombinat.oshiire.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import space.webkombinat.oshiire.R
import space.webkombinat.oshiire.ui.annotate.AnnotateScreen
import space.webkombinat.oshiire.ui.editor.NoteEditorScreen
import space.webkombinat.oshiire.ui.list.NoteListScreen
import space.webkombinat.oshiire.ui.settings.SettingsScreen

@Serializable
data object NoteListKey : NavKey

@Serializable
data class NoteEditorKey(val id: String) : NavKey

@Serializable
data class AnnotateKey(val path: String) : NavKey

@Serializable
data object SettingsKey : NavKey

/**
 * 画面構成: ボトムナビの左「メモ」（一覧 → 編集 → 写真への書き込み）と右「設定」。
 * 戻る操作は 1 本のバックスタックで扱い、設定は一覧の上に積む。
 */
@Composable
fun AppNavigation() {
    val backStack = rememberNavBackStack(NoteListKey)
    val top = backStack.lastOrNull()
    val showBottomBar = top == NoteListKey || top == SettingsKey

    Column(Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<NoteListKey> {
                    NoteListScreen(onOpen = { id -> backStack.add(NoteEditorKey(id)) })
                }
                entry<NoteEditorKey> { key ->
                    NoteEditorScreen(
                        noteId = key.id,
                        onBack = { backStack.removeLastOrNull() },
                        onAnnotate = { path -> backStack.add(AnnotateKey(path)) },
                    )
                }
                entry<AnnotateKey> { key ->
                    AnnotateScreen(path = key.path, onBack = { backStack.removeLastOrNull() })
                }
                entry<SettingsKey> { SettingsScreen() }
            },
            modifier = Modifier.weight(1f),
        )
        AnimatedVisibility(visible = showBottomBar, enter = expandVertically(), exit = shrinkVertically()) {
            NavigationBar {
                NavigationBarItem(
                    selected = top == NoteListKey,
                    onClick = { backStack.selectTab(NoteListKey) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Notes, null) },
                    label = { Text(stringResource(R.string.nav_notes)) },
                )
                NavigationBarItem(
                    selected = top == SettingsKey,
                    onClick = { backStack.selectTab(SettingsKey) },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        }
    }
}

/** 一覧だけを残し、設定なら一覧の上に積む */
private fun NavBackStack<NavKey>.selectTab(tab: NavKey) {
    while (size > 1) removeAt(lastIndex)
    if (tab != NoteListKey) add(tab)
}
