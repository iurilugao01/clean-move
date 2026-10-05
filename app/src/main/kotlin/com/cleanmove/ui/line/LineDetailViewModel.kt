package com.cleanmove.ui.line

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanmove.domain.FavoritesStore
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LineDetailUiState {
    data object Loading : LineDetailUiState
    data object Error : LineDetailUiState
    data class Loaded(val detail: LineDetail, val isFavorite: Boolean) : LineDetailUiState
}

@HiltViewModel
class LineDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: LineCatalog,
    private val favorites: FavoritesStore,
) : ViewModel() {
    private val code: String = checkNotNull(savedStateHandle["code"])
    private val detail = MutableStateFlow<Result<LineDetail>?>(null)

    val state: StateFlow<LineDetailUiState> = combine(detail, favorites.favoriteLines) { result, favs ->
        when {
            result == null -> LineDetailUiState.Loading
            result.isFailure -> LineDetailUiState.Error
            else -> LineDetailUiState.Loaded(result.getOrThrow(), code in favs)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LineDetailUiState.Loading)

    init { load() }

    fun retry() = load()

    fun toggleFavorite() { viewModelScope.launch { favorites.toggleLine(code) } }

    private fun load() {
        viewModelScope.launch {
            detail.value = null
            detail.value = catalog.loadDetail(code)
        }
    }
}
