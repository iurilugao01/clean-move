package com.cleanmove.ui.stop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanmove.domain.DayType
import com.cleanmove.domain.EstimatedArrivals
import com.cleanmove.domain.EstimatedStopArrival
import com.cleanmove.domain.LiveArrivals
import com.cleanmove.domain.LiveStopArrival
import com.cleanmove.domain.StopCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

enum class StopMode { LIVE, ESTIMATES }

data class StopDetailUiState(
    val stopLabel: String = "",
    val mode: StopMode = StopMode.LIVE,
    val autoFallback: Boolean = false,
    val loading: Boolean = true,
    val liveRows: List<LiveStopArrival> = emptyList(),
    val estimatedRows: List<EstimatedStopArrival> = emptyList(),
)

fun currentDayType(dayOfWeek: DayOfWeek): DayType = when (dayOfWeek) {
    DayOfWeek.SATURDAY -> DayType.SATURDAY
    DayOfWeek.SUNDAY -> DayType.SUNDAY_HOLIDAY
    else -> DayType.WEEKDAY
}

@HiltViewModel
class StopDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val liveArrivals: LiveArrivals,
    private val estimatedArrivals: EstimatedArrivals,
    private val stopCatalog: StopCatalog,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")) },
) : ViewModel() {
    private val stopId: Int = checkNotNull(savedStateHandle["stopId"])
    private val _state = MutableStateFlow(StopDetailUiState())
    val state: StateFlow<StopDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stop = stopCatalog.stop(stopId)
            _state.update { it.copy(stopLabel = stop?.street ?: stop?.code ?: "Ponto $stopId") }
            load()
        }
    }

    fun selectMode(mode: StopMode) {
        if (mode == _state.value.mode && !_state.value.autoFallback) return
        _state.update { it.copy(mode = mode, autoFallback = false, loading = true) }
        viewModelScope.launch { loadFor(mode) }
    }

    private suspend fun load() = loadFor(StopMode.LIVE)

    private suspend fun loadFor(mode: StopMode) {
        if (mode == StopMode.LIVE) {
            val result = liveArrivals.forStop(stopId, clock().toInstant().toEpochMilli())
            val rows = result.getOrNull()
            if (result.isFailure || rows.isNullOrEmpty()) {
                _state.update { it.copy(mode = StopMode.ESTIMATES, autoFallback = true) }
                loadEstimates()
            } else {
                _state.update { it.copy(loading = false, liveRows = rows) }
            }
        } else {
            loadEstimates()
        }
    }

    private suspend fun loadEstimates() {
        val now = clock()
        val rows = estimatedArrivals.forStop(stopId, now.hour * 60 + now.minute, currentDayType(now.dayOfWeek))
        _state.update { it.copy(loading = false, estimatedRows = rows) }
    }
}
