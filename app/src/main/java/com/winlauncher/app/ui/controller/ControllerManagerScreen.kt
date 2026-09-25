@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlauncher.app.ui.controller

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlauncher.app.domain.controller.ConnectedGamepad
import com.winlauncher.app.viewmodel.AppViewModelFactory
import com.winlauncher.app.viewmodel.ControllerViewModel

@Composable
fun ControllerManagerScreen(factory: AppViewModelFactory) {
    val viewModel: ControllerViewModel = viewModel(factory = factory)
    val gamepads by viewModel.connectedGamepads.collectAsStateWithLifecycle()
    val liveState by viewModel.liveState.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Controller Manager") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Physical controllers", style = MaterialTheme.typography.titleSmall)
            if (gamepads.isEmpty()) {
                Text("None detected. Connect a Bluetooth/USB pad and press any button.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(gamepads, key = { it.deviceId }) { GamepadRow(it) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Live input state (physical or touch, same pipeline)", style = MaterialTheme.typography.titleSmall)
            Text(
                "LX=${"%.2f".format(liveState.leftStickX)} LY=${"%.2f".format(liveState.leftStickY)}  " +
                    "RX=${"%.2f".format(liveState.rightStickX)} RY=${"%.2f".format(liveState.rightStickY)}",
            )
            Text(
                "A=${liveState.buttonA} B=${liveState.buttonB} X=${liveState.buttonX} Y=${liveState.buttonY} " +
                    "LB=${liveState.bumperLeft} RB=${liveState.bumperRight}",
            )

            Spacer(Modifier.height(24.dp))
            Text("Touch overlay preview", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            TouchOverlay(inputMapper = viewModel.inputMapper, modifier = Modifier.fillMaxWidth().height(260.dp))
        }
    }
}

@Composable
private fun GamepadRow(gamepad: ConnectedGamepad) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(gamepad.name, modifier = Modifier.weight(1f))
        Text(if (gamepad.isBluetooth) "Bluetooth" else "USB", style = MaterialTheme.typography.bodySmall)
    }
}
