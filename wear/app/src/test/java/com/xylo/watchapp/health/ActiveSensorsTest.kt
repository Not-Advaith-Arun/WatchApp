package com.xylo.watchapp.health

import androidx.health.services.client.data.ExerciseType
import com.xylo.watchapp.data.MetricType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ActiveSensorsTest {
    @Test fun heartRateFacadeIsMappedAndUnsupportedMetricFails() = runTest {
        val subject = WearActiveMeasureProvider(object : HeartRateMeasureFacade { override suspend fun supported() = true; override suspend fun collect(durationMs: Long) = listOf(61.0, 62.0) }, clock = { 5 })
        assertEquals(2, subject.measure(MetricType.HEART_RATE, 1_000).getOrThrow().size)
        assertTrue(subject.measure(MetricType.STEPS, 1_000).isFailure)
    }
    @Test fun workoutChecksCapabilityAndLifecycle() = runTest {
        val calls = mutableListOf<String>(); val facade = object : ExerciseFacade { override suspend fun supports(type: ExerciseType)=true; override suspend fun start(type: ExerciseType){calls += "start"}; override suspend fun pause(){calls += "pause"}; override suspend fun resume(){calls += "resume"}; override suspend fun end(){calls += "end"} }
        val subject = WearWorkoutProvider(facade); subject.start(2).getOrThrow(); subject.pause().getOrThrow(); subject.resume().getOrThrow(); subject.end().getOrThrow()
        assertEquals(listOf("start", "pause", "resume", "end"), calls)
    }
}
