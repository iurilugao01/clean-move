package com.cleanmove.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanmove.domain.GeoStop
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.StopCatalog
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
    val stops: List<GeoStop> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
)

private enum class RefreshStatus { Loading, Ready, Failed }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalog: LineCatalog,
    private val stopCatalog: StopCatalog,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val status = MutableStateFlow(RefreshStatus.Loading)

    val state: StateFlow<SearchUiState> = combine(
        query,
        query.flatMapLatest { catalog.observeLines(it) },
        query.flatMapLatest { stopCatalog.observeStops(it) },
        status,
    ) { q, lines, stops, s ->
        val empty = lines.isEmpty() && stops.isEmpty()
        SearchUiState(
            query = q, lines = lines, stops = stops,
            loading = s == RefreshStatus.Loading && empty,
            error = s == RefreshStatus.Failed && empty,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init { refresh() }

    fun onQueryChange(value: String) { query.value = value }

    fun retry() = refresh()

    private fun refresh() {
        viewModelScope.launch {
            status.value = RefreshStatus.Loading
            val linesOk = catalog.refreshLinesIfStale().isSuccess
            val stopsOk = stopCatalog.refreshIfStale().isSuccess
            status.value = if (linesOk || stopsOk) RefreshStatus.Ready else RefreshStatus.Failed
        }
    }
}
