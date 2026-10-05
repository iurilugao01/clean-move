package com.cleanmove.ui.stop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Accessible
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanmove.domain.EstimatedStopArrival
import com.cleanmove.domain.LiveStopArrival
import com.cleanmove.domain.Reliability
import com.cleanmove.ui.line.formatMinuteOfDay
import com.cleanmove.ui.theme.LiveColor
import com.cleanmove.ui.theme.ScheduledColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailScreen(onBack: () -> Unit, viewModel: StopDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.stopLabel) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                SegmentedButton(
                    selected = state.mode == StopMode.LIVE,
                    onClick = { viewModel.selectMode(StopMode.LIVE) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Ao vivo") }
                SegmentedButton(
                    selected = state.mode == StopMode.ESTIMATES,
                    onClick = { viewModel.selectMode(StopMode.ESTIMATES) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Estimativas") }
            }
            if (state.autoFallback) {
                Text(
                    "Sem dados ao vivo no momento — mostrando estimativas.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.mode == StopMode.LIVE && state.liveRows.isEmpty() ->
                    EmptyState()
                state.mode == StopMode.ESTIMATES && state.estimatedRows.isEmpty() ->
                    EmptyState()
                state.mode == StopMode.LIVE -> LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.liveRows) { LiveRow(it) }
                }
                else -> LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.estimatedRows) { EstimatedRow(it) }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text("Nenhum ônibus previsto agora", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Reliability.toColor() = when (this) {
    Reliability.GREEN -> LiveColor
    Reliability.ORANGE -> ScheduledColor
    Reliability.RED -> androidx.compose.ui.graphics.Color(0xFFFF6B6B)
    Reliability.GRAY -> androidx.compose.ui.graphics.Color(0xFF6B7280)
}

@Composable
private fun LiveRow(arrival: LiveStopArrival) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(arrival.lineCode ?: arrival.rawLineLabel ?: "?", color = arrival.reliability.toColor(), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f)) {
                Text(arrival.lineName ?: "Linha ${arrival.rawLineLabel}", style = MaterialTheme.typography.bodyMedium)
                arrival.vehicleLabel?.let { Text("Veículo $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (arrival.hasAccessibility) Icon(Icons.AutoMirrored.Filled.Accessible, "Acessível", Modifier, MaterialTheme.colorScheme.onSurfaceVariant)
                    if (arrival.hasAirConditioning) Icon(Icons.Filled.AcUnit, "Ar-condicionado", Modifier, MaterialTheme.colorScheme.onSurfaceVariant)
                    if (arrival.hasWifi) Icon(Icons.Filled.Wifi, "Wifi", Modifier, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("${arrival.minutesAway} min", color = LiveColor, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun EstimatedRow(arrival: EstimatedStopArrival) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(arrival.lineCode, color = ScheduledColor, style = MaterialTheme.typography.titleMedium)
            Text(arrival.lineName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text((if (arrival.isInterpolated) "≈" else "") + formatMinuteOfDay(arrival.minuteOfDay), color = ScheduledColor)
        }
    }
}
