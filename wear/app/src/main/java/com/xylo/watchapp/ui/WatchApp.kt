package com.xylo.watchapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.xylo.watchapp.ble.LinkState
import com.xylo.watchapp.data.MetricType

private val OledBlack = Color.Black
private val Mint = Color(0xFF73F7A5)
private val Muted = Color(0xFF9A9A9F)

@Composable fun WatchApp(viewModel: WatchViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MaterialTheme {
        Column(
            Modifier.fillMaxSize().background(OledBlack).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("WATCH", color = Muted, fontSize = 11.sp)
            state.enrollmentCode?.let { Text("MATCH  $it", color = Mint, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp))
            Metric("HEART RATE", state.measurements.firstOrNull { it.type == MetricType.HEART_RATE }?.value?.toInt()?.toString() ?: "—", "BPM")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SmallMetric("STEPS", state.measurements.firstOrNull { it.type == MetricType.STEPS }?.value?.toInt()?.toString() ?: "—")
                SmallMetric("LINK", when (state.link) { is LinkState.Connected -> "LIVE"; is LinkState.Advertising -> "READY"; else -> "OFF" })
            }
            Button(onClick = viewModel::measureHeartRate) { Text("Measure HR") }
            Button(onClick = viewModel::startWalk) { Text("Start walk") }
            Button(onClick = viewModel::endWorkout) { Text("End workout") }
            state.sensorError?.let {
                Spacer(Modifier.height(10.dp)); Text(it, color = Color(0xFFFF827E), fontSize = 11.sp)
                Button(onClick = viewModel::retry) { Text("Retry") }
            }
        }
    }
}

@Composable private fun Metric(label: String, value: String, unit: String) {
    Text(label, color = Muted, fontSize = 11.sp)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.width(4.dp)); Text(unit, color = Mint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable private fun SmallMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = Muted, fontSize = 9.sp); Text(value, color = Mint, fontSize = 16.sp) }
}
