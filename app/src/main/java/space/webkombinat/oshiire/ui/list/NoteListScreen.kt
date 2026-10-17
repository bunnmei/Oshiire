package space.webkombinat.oshiire.ui.list

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import space.webkombinat.oshiire.R
import space.webkombinat.oshiire.model.NoteSummary
import java.io.File
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    onOpen: (String) -> Unit,
    vm: NoteListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.list_deleted)
    val undoLabel = stringResource(R.string.action_undo)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        // 下はボトムナビが受け持つ
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.create(onOpen) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.list_new_note)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loading && state.notes.isEmpty()) {
            EmptyState(Modifier.padding(padding).fillMaxSize())
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.notes, key = { it.id }) { note ->
                    val dismiss = rememberSwipeToDismissBoxState()
                    SwipeToDismissBox(
                        state = dismiss,
                        enableDismissFromStartToEnd = false,
                        onDismiss = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                vm.delete(note.id)
                                scope.launch {
                                    val r = snackbar.showSnackbar(deletedMessage, actionLabel = undoLabel, withDismissAction = true)
                                    if (r == SnackbarResult.ActionPerformed) vm.restore(note.id)
                                }
                            }
                        },
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        },
                    ) {
                        NoteRow(note, vm::resolve, onClick = { onOpen(note.id) })
                    }
                }
            }
        }
    }
}

/** YouTube の一覧のように、左にサムネイル、右にタイトル・冒頭文・日時 */
@Composable
private fun NoteRow(note: NoteSummary, resolve: (String) -> File, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
        ) {
            if (note.thumbnail != null) {
                val file = resolve(note.thumbnail)
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(file)
                        .memoryCacheKey("${file.path}#${note.thumbnailVersion}")
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                Placeholder(note.id)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                note.title.ifBlank { stringResource(R.string.list_untitled) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.excerpt.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    note.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                DateUtils.formatDateTime(
                    context, note.updatedAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 写真がないメモのサムネイル。メモごとに色を変えて見分けやすくする */
@Composable
private fun Placeholder(seed: String) {
    val colors = listOf(
        Color(0xFF5C6BC0), Color(0xFF26A69A), Color(0xFFEF6C00), Color(0xFF8D6E63),
        Color(0xFF7E57C2), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFEC407A),
    )
    val c = colors[seed.hashCode().absoluteValue % colors.size]
    Box(Modifier.fillMaxSize().background(c.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Outlined.Notes, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.PhotoCamera, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.list_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.list_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
