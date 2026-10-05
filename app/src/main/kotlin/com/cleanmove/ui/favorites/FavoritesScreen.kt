package com.cleanmove.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanmove.ui.search.LineRow
import com.cleanmove.ui.theme.ScheduledColor

@Composable
fun FavoritesScreen(onLineClick: (String) -> Unit, viewModel: FavoritesViewModel = hiltViewModel()) {
    val lines by viewModel.state.collectAsStateWithLifecycle()
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nenhum favorito ainda.\nToque na estrela de uma linha para salvá-la.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lines, key = { it.code }) { line ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { LineRow(line, onLineClick) }
                    IconButton(onClick = { viewModel.remove(line.code) }) {
                        Icon(Icons.Filled.Star, contentDescription = "Remover dos favoritos", tint = ScheduledColor)
                    }
                }
            }
        }
    }
}
