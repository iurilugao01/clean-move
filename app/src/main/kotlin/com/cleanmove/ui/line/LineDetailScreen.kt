package com.cleanmove.ui.line

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanmove.domain.DayType
import com.cleanmove.domain.LineDetail
import com.cleanmove.domain.ScheduledDeparture
import com.cleanmove.ui.theme.ScheduledColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineDetailScreen(onBack: () -> Unit, viewModel: LineDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loaded = state as? LineDetailUiState.Loaded
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.detail?.line?.name ?: "Linha") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                actions = {
                    if (loaded != null) IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            if (loaded.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (loaded.isFavorite) "Remover dos favoritos" else "Favoritar",
                            tint = if (loaded.isFavorite) ScheduledColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                LineDetailUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                LineDetailUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Não foi possível carregar a linha.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = viewModel::retry) { Text("Tentar novamente") }
                    }
                }
                is LineDetailUiState.Loaded -> LineDetailContent(s.detail)
            }
        }
    }
}

private fun DayType.label() = when (this) {
    DayType.WEEKDAY -> "Dias úteis"
    DayType.SATURDAY -> "Sábado"
    DayType.SUNDAY_HOLIDAY -> "Domingos e feriados"
    DayType.ATYPICAL -> "Atípicos entre feriados"
    DayType.OTHER -> "Outros"
}

fun formatMinuteOfDay(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

@Composable
private fun LineDetailContent(detail: LineDetail) {
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Horários de saída", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (detail.schedules.isEmpty()) {
            item { Text("Sem horários programados disponíveis.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            val groups = detail.schedules.groupBy { it.dayType to it.direction }.toList()
                .sortedWith(compareBy({ it.first.first }, { it.first.second }))
            items(groups, key = { "${it.first.first}-${it.first.second}" }) { (key, departures) ->
                ScheduleGroup(key.first, departures)
            }
        }
        item { Text("Itinerário", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
        if (detail.itinerary.isEmpty()) {
            item { Text("Itinerário indisponível.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            val byDirection = detail.itinerary.groupBy { it.direction }.toSortedMap()
            byDirection.forEach { (direction, steps) ->
                item(key = "dir-$direction") {
                    Text("Sentido $direction", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                items(steps, key = { "step-${it.direction}-${it.sequence}" }) { Text(it.street, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item { Box(Modifier.padding(bottom = 24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleGroup(dayType: DayType, departures: List<ScheduledDeparture>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dayType.label(), style = MaterialTheme.typography.labelLarge)
            Text("de ${departures.first().terminalName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            departures.forEach { Text(formatMinuteOfDay(it.minuteOfDay), color = ScheduledColor) }
        }
    }
}
