package com.cleanmove.domain

import kotlinx.coroutines.flow.Flow

interface LineCatalog {
    fun observeLines(query: String): Flow<List<Line>>
    suspend fun refreshLinesIfStale(): Result<Unit>
    suspend fun loadDetail(code: String): Result<LineDetail>
}

interface FavoritesStore {
    val favoriteLines: Flow<Set<String>>
    suspend fun toggleLine(code: String)
}
