@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlauncher.app.ui.controller

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlauncher.app.data.db.entity.ControllerProfile
import com.winlauncher.app.domain.controller.ConnectedGamepad
import com.winlauncher.app.viewmodel.AppViewModelFactory
import com.winlauncher.app.viewmodel.ControllerViewModel

@Composable
fun ControllerManagerScreen(factory: AppViewModelFactory) {
    val viewModel: ControllerViewModel = viewModel(factory = factory)
    val gamepads by viewModel.connectedGamepads.collectAsStateWithLifecycle()
    val liveState by viewModel.liveState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Controller Manager") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) { Text("+") }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("Physical controllers", style = MaterialTheme.typography.titleSmall)
            if (gamepads.isEmpty()) {
                Text("None detected. Connect a Bluetooth/USB pad and press any button.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(gamepads, key = { it.deviceId }) { GamepadRow(it) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Controller profiles", style = MaterialTheme.typography.titleSmall)
            if (profiles.isEmpty()) {
                Text("No profiles yet. Tap + to create one (deadzone, sensitivity, touch opacity).")
            } else {
                profiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        isActive = profile.id == activeProfileId,
                        onSelect = { viewModel.setActive(profile.id, profile) },
                        onDelete = { viewModel.delete(profile) },
                    )
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
            TouchOverlay(
                inputMapper = viewModel.inputMapper,
                opacity = activeProfile?.touchOpacity ?: 0.6f,
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
        }
    }

    if (showCreate) {
        CreateControllerProfileDialog(
            onDismiss = { showCreate = false },
            onCreate = { profile ->
                viewModel.save(profile)
                showCreate = false
            },
        )
    }
}

@Composable
private fun GamepadRow(gamepad: ConnectedGamepad) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(gamepad.name, modifier = Modifier.weight(1f))
        Text(if (gamepad.isBluetooth) "Bluetooth" else "USB", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProfileRow(
    profile: ControllerProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(profile.name + if (isActive) " (active)" else "")
            Text(
                "deadzone ${"%.2f".format(profile.deadzone)} \u00b7 sensitivity ${"%.2f".format(profile.sensitivity)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onSelect, enabled = !isActive) { Text("Select") }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}

@Composable
private fun CreateControllerProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (ControllerProfile) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var deadzone by remember { mutableStateOf(0.15f) }
    var sensitivity by remember { mutableStateOf(1.0f) }
    var touchOpacity by remember { mutableStateOf(0.6f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New controller profile") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Spacer(Modifier.height(12.dp))
                Text("Deadzone: ${"%.2f".format(deadzone)}", style = MaterialTheme.typography.bodySmall)
                Slider(value = deadzone, onValueChange = { deadzone = it }, valueRange = 0f..0.5f)
                Text("Sensitivity: ${"%.2f".format(sensitivity)}", style = MaterialTheme.typography.bodySmall)
                Slider(value = sensitivity, onValueChange = { sensitivity = it }, valueRange = 0.5f..2f)
                Text("Touch overlay opacity: ${"%.2f".format(touchOpacity)}", style = MaterialTheme.typography.bodySmall)
                Slider(value = touchOpacity, onValueChange = { touchOpacity = it }, valueRange = 0.2f..1f)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onCreate(
                        ControllerProfile(
                            name = name,
                            deadzone = deadzone,
                            sensitivity = sensitivity,
                            touchOpacity = touchOpacity,
                        ),
                    )
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

