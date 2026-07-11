package com.xylo.watchapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xylo.watchapp.AppGraph
import com.xylo.watchapp.ble.LinkState
import com.xylo.watchapp.data.MeasurementEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class WatchUiState(val measurements: List<MeasurementEntity> = emptyList(), val link: LinkState = LinkState.Stopped, val sensorError: String? = null, val enrollmentCode: String? = null)

class WatchViewModel(private val graph: AppGraph) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)
    val uiState: StateFlow<WatchUiState> = combine(graph.measurements.latest(40), graph.gatt.state, error, graph.enrollmentCoordinator.sas, ::WatchUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchUiState())
    fun start() {
        graph.gatt.start().onFailure { error.value = it.message }
        viewModelScope.launch { runCatching { graph.health.start() }.onFailure { error.value = it.message } }
    }
    fun retry() { error.value = null; start() }
    fun measureHeartRate() { viewModelScope.launch { graph.activeMeasure.measure(com.xylo.watchapp.data.MetricType.HEART_RATE, 10_000).onSuccess { values -> values.forEach { graph.measurements.record(it) } }.onFailure { error.value = it.message } } }
    fun startWalk() { viewModelScope.launch { graph.workout.start(2).onFailure { error.value = it.message } } }
    fun endWorkout() { viewModelScope.launch { graph.workout.end().onFailure { error.value = it.message } } }
    override fun onCleared() { graph.gatt.stop(); super.onCleared() }
    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = WatchViewModel(graph) as T
    }
}
