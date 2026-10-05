package com.cleanmove.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val lines: List<Line> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
)

private enum class RefreshStatus { Loading, Ready, Failed }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val catalog: LineCatalog) : ViewModel() {
    private val query = MutableStateFlow("")
    private val status = MutableStateFlow(RefreshStatus.Loading)

    val state: StateFlow<SearchUiState> = combine(
        query,
        query.flatMapLatest { catalog.observeLines(it) },
        status,
    ) { q, lines, s ->
        SearchUiState(
            query = q,
            lines = lines,
            loading = s == RefreshStatus.Loading && lines.isEmpty(),
            error = s == RefreshStatus.Failed && lines.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init { refresh() }

    fun onQueryChange(value: String) { query.value = value }

    fun retry() = refresh()

    private fun refresh() {
        viewModelScope.launch {
            status.value = RefreshStatus.Loading
            status.value = if (catalog.refreshLinesIfStale().isSuccess) RefreshStatus.Ready else RefreshStatus.Failed
        }
    }
}
