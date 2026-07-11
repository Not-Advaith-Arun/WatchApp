package com.xylo.watchapp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.xylo.watchapp.ui.WatchApp
import com.xylo.watchapp.ui.WatchViewModel

class MainActivity : ComponentActivity() {
    private val graph get() = (application as WatchApplication).graph
    private val viewModel by viewModels<WatchViewModel> { WatchViewModel.Factory(graph) }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) viewModel.start()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
        setContent { WatchApp(viewModel) }
    }
}
