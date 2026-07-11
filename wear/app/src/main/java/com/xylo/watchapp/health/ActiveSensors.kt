package com.xylo.watchapp.health

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.*
import com.xylo.watchapp.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await
import java.util.concurrent.CopyOnWriteArrayList

interface HeartRateMeasureFacade { suspend fun supported(): Boolean; suspend fun collect(durationMs: Long): List<Double> }
class WearHeartRateMeasureFacade(context: Context) : HeartRateMeasureFacade {
    private val client = HealthServices.getClient(context).measureClient
    override suspend fun supported() = DataType.HEART_RATE_BPM in client.getCapabilitiesAsync().await().supportedDataTypesMeasure
    override suspend fun collect(durationMs: Long): List<Double> {
        require(durationMs in 1_000..60_000); val values = CopyOnWriteArrayList<Double>()
        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit
            override fun onDataReceived(data: DataPointContainer) { values += data.getData(DataType.HEART_RATE_BPM).map { it.value } }
        }
        client.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        try { delay(durationMs) } finally { client.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback).await() }
        return values.toList()
    }
}

class WearActiveMeasureProvider(private val facade: HeartRateMeasureFacade, private val clock: () -> Long = System::currentTimeMillis) : ActiveMeasureProvider {
    override suspend fun measure(type: MetricType, durationMs: Long): Result<List<MeasurementEntity>> = runCatching {
        require(type == MetricType.HEART_RATE) { "Only heart rate is supported by Wear MeasureClient" }
        require(facade.supported()) { "Heart-rate measurement is unavailable" }
        facade.collect(durationMs).map { MeasurementEntity(type = type, value = it, unit = "{beats}/min", capturedAtEpochMs = clock(), source = "wear_health_services") }
    }
}

interface ExerciseFacade { suspend fun supports(type: ExerciseType): Boolean; suspend fun start(type: ExerciseType); suspend fun pause(); suspend fun resume(); suspend fun end() }
class WearExerciseFacade(context: Context) : ExerciseFacade {
    private val client = HealthServices.getClient(context).exerciseClient
    override suspend fun supports(type: ExerciseType) = type in client.getCapabilitiesAsync().await().supportedExerciseTypes
    override suspend fun start(type: ExerciseType) { client.startExerciseAsync(ExerciseConfig(type, setOf(DataType.HEART_RATE_BPM, DataType.STEPS), false, false)).await() }
    override suspend fun pause() { client.pauseExerciseAsync().await() }
    override suspend fun resume() { client.resumeExerciseAsync().await() }
    override suspend fun end() { client.endExerciseAsync().await() }
}
class WearWorkoutProvider(private val facade: ExerciseFacade) : WorkoutProvider {
    private var active = false
    override suspend fun start(activityType: Int) = runCatching { val type = when (activityType) { 1 -> ExerciseType.RUNNING; 2 -> ExerciseType.WALKING; 3 -> ExerciseType.BIKING; else -> error("Unsupported workout type") }; require(facade.supports(type)); facade.start(type); active = true }
    override suspend fun pause() = runCatching { check(active); facade.pause() }
    override suspend fun resume() = runCatching { check(active); facade.resume() }
    override suspend fun end() = runCatching { check(active); facade.end(); active = false }
}
