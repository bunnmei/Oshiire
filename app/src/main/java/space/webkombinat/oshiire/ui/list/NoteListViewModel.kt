package space.webkombinat.oshiire.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.webkombinat.oshiire.data.NoteRepository
import space.webkombinat.oshiire.model.NoteSummary
import java.io.File
import javax.inject.Inject

data class NoteListUiState(
    val loading: Boolean = true,
    val notes: List<NoteSummary> = emptyList(),
)

@HiltViewModel
class NoteListViewModel @Inject constructor(
    private val repository: NoteRepository,
) : ViewModel() {

    private val loaded = MutableStateFlow(false)

    val state: StateFlow<NoteListUiState> = combine(repository.notes, loaded) { notes, done ->
        NoteListUiState(loading = !done, notes = notes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteListUiState())

    init {
        refresh()
    }

    /** 画面に戻ってきたとき（PC から vault を書き換えた場合など）に読み直す */
    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
            loaded.value = true
        }
    }

    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(repository.create()) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    fun resolve(path: String): File = repository.resolve(path)
}
