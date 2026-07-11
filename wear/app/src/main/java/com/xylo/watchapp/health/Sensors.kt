package com.xylo.watchapp.health

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.xylo.watchapp.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await

data class SensorCapabilities(val passiveHeartRate: Boolean, val passiveSteps: Boolean, val activeHeartRate: Boolean, val samsungRawTypes: Set<RawMetricType>)

interface PassiveSensorProvider { suspend fun capabilities(): SensorCapabilities; suspend fun start(); suspend fun stop() }
interface ActiveMeasureProvider { suspend fun measure(type: MetricType, durationMs: Long): Result<List<MeasurementEntity>> }
interface WorkoutProvider { suspend fun start(activityType: Int): Result<Unit>; suspend fun pause(): Result<Unit>; suspend fun resume(): Result<Unit>; suspend fun end(): Result<Unit> }
interface RawSensorProvider { val supported: Set<RawMetricType>; suspend fun samples(type: RawMetricType, durationMs: Long, onBatch: suspend (ShortArray) -> Unit): Result<Unit> }

class WearHealthServicesProvider(context: Context) : PassiveSensorProvider {
    private val client = HealthServices.getClient(context).passiveMonitoringClient
    override suspend fun capabilities(): SensorCapabilities {
        val caps = client.getCapabilitiesAsync().await()
        return SensorCapabilities(DataType.HEART_RATE_BPM in caps.supportedDataTypesPassiveMonitoring, DataType.STEPS_DAILY in caps.supportedDataTypesPassiveMonitoring, true, emptySet())
    }
    override suspend fun start() {
        val config = PassiveListenerConfig.builder().setDataTypes(setOf(DataType.HEART_RATE_BPM, DataType.STEPS_DAILY)).build()
        client.setPassiveListenerServiceAsync(PassiveDataService::class.java, config).await()
    }
    override suspend fun stop() { client.clearPassiveListenerServiceAsync().await() }
}

class PassiveDataService : PassiveListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { MeasurementRepository(WatchDatabase.get(applicationContext)) }
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        super.onNewDataPointsReceived(dataPoints)
        val now = System.currentTimeMillis()
        dataPoints.getData(DataType.HEART_RATE_BPM).forEach { point ->
            scope.launch { repository.record(MeasurementEntity(type = MetricType.HEART_RATE, value = point.value, unit = "{beats}/min", capturedAtEpochMs = now, source = "wear_health_services")) }
        }
        dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let { point ->
            scope.launch { repository.record(MeasurementEntity(type = MetricType.STEPS, value = point.value.toDouble(), unit = "{count}", capturedAtEpochMs = now, durationMillis = 86_400_000, source = "wear_health_services")) }
        }
    }
}

/** Boundary implemented by the separately licensed Samsung Health Sensor SDK build. */
interface SamsungTrackerBridge {
    fun isSdkPresent(): Boolean
    fun supportedTrackers(): Set<String>
    suspend fun track(tracker: String, durationMs: Long, onBatch: suspend (ShortArray) -> Unit): Result<Unit>
}

class NoOpSamsungTrackerBridge : SamsungTrackerBridge {
    override fun isSdkPresent() = false
    override fun supportedTrackers() = emptySet<String>()
    override suspend fun track(tracker: String, durationMs: Long, onBatch: suspend (ShortArray) -> Unit) = Result.failure<Unit>(UnsupportedOperationException("Samsung Health Sensor SDK is not installed"))
}

class SamsungRawSensorProvider(private val bridge: SamsungTrackerBridge) : RawSensorProvider {
    private val mapping = mapOf(RawMetricType.PPG_GREEN to "PPG_CONTINUOUS", RawMetricType.ECG to "ECG", RawMetricType.ACCELEROMETER to "ACCELEROMETER_CONTINUOUS")
    override val supported: Set<RawMetricType> get() = if (bridge.isSdkPresent()) mapping.filterValues { it in bridge.supportedTrackers() }.keys else emptySet()
    override suspend fun samples(type: RawMetricType, durationMs: Long, onBatch: suspend (ShortArray) -> Unit): Result<Unit> {
        require(durationMs in 1_000..300_000) { "duration must be 1-300 seconds" }
        val tracker = mapping[type] ?: return Result.failure(UnsupportedOperationException("Unsupported raw metric"))
        return bridge.track(tracker, durationMs, onBatch)
    }
}
