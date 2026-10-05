package com.cleanmove.ui

import com.cleanmove.domain.FavoritesStore
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeCatalog(
    initialLines: List<Line> = emptyList(),
    var refreshResult: Result<Unit> = Result.success(Unit),
    var detailResult: Result<LineDetail> = Result.failure(IllegalStateException("not set")),
) : LineCatalog {
    val lines = MutableStateFlow(initialLines)
    var refreshCalls = 0

    override fun observeLines(query: String): Flow<List<Line>> =
        lines.map { all -> all.filter { it.name.contains(query, true) || it.code.contains(query, true) } }

    override suspend fun refreshLinesIfStale(): Result<Unit> { refreshCalls++; return refreshResult }
    override suspend fun loadDetail(code: String): Result<LineDetail> = detailResult
}

class FakeFavorites(initial: Set<String> = emptySet()) : FavoritesStore {
    val state = MutableStateFlow(initial)
    override val favoriteLines: Flow<Set<String>> = state
    override suspend fun toggleLine(code: String) { state.value = if (code in state.value) state.value - code else state.value + code }
}
