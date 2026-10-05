package com.cleanmove.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanmove.domain.GeoStop
import com.cleanmove.domain.Line

@Composable
fun SearchScreen(onLineClick: (String) -> Unit, onStopClick: (Int) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text("Buscar linha ou ponto") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        )
        when {
            state.loading -> Centered { CircularProgressIndicator() }
            state.error -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Não foi possível carregar os dados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = viewModel::retry) { Text("Tentar novamente") }
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Linhas", style = MaterialTheme.typography.titleMedium) }
                if (state.lines.isEmpty()) item { Text("Nenhuma linha encontrada", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else items(state.lines, key = { "line-${it.code}" }) { LineRow(it, onLineClick) }

                item { Text("Pontos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
                if (state.stops.isEmpty()) item { Text("Nenhum ponto encontrado", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else items(state.stops, key = { "stop-${it.id}" }) { StopRow(it, onStopClick) }
            }
        }
    }
}

@Composable
fun LineRow(line: Line, onClick: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable { onClick(line.code) },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(line.code, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            Text(line.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun StopRow(stop: GeoStop, onClick: (Int) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable { onClick(stop.id) },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stop.street ?: stop.code ?: "Ponto ${stop.id}", style = MaterialTheme.typography.bodyLarge)
            stop.municipality?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
