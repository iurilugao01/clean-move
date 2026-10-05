package com.cleanmove.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanmove.domain.FavoritesStore
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    catalog: LineCatalog,
    private val favorites: FavoritesStore,
) : ViewModel() {
    val state: StateFlow<List<Line>> = combine(catalog.observeLines(""), favorites.favoriteLines) { lines, favs ->
        lines.filter { it.code in favs }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(code: String) { viewModelScope.launch { favorites.toggleLine(code) } }
}
