package com.xylo.watchapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test fun converterRoundTripsEnums() {
        val converters = Converters()
        MetricType.entries.forEach { assertEquals(it, converters.metric(converters.metric(it))) }
        QueueState.entries.forEach { assertEquals(it, converters.queue(converters.queue(it))) }
    }
}
