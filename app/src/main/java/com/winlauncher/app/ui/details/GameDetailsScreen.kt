@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlauncher.app.ui.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlauncher.app.data.db.entity.RuntimeProfile
import com.winlauncher.app.domain.graphics.GraphicsResolution
import com.winlauncher.app.domain.performance.PerformanceSnapshot
import com.winlauncher.app.domain.runtime.RuntimeStatus
import com.winlauncher.app.viewmodel.AppViewModelFactory
import com.winlauncher.app.viewmodel.GameDetailsViewModel

@Composable
fun GameDetailsScreen(factory: AppViewModelFactory, gameId: Long) {
    val viewModel: GameDetailsViewModel = viewModel(factory = factory)
    LaunchedEffect(gameId) { viewModel.load(gameId) }

    val game by viewModel.game.collectAsStateWithLifecycle()
    val runtimeProfiles by viewModel.runtimeProfiles.collectAsStateWithLifecycle()
    val resolution by viewModel.graphicsResolution.collectAsStateWithLifecycle()
    val status by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val performance by viewModel.performance.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(game?.name ?: "Game") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            resolution?.let { GraphicsBadge(it) }
            Spacer(Modifier.height(12.dp))

            RuntimeSection(
                runtimeProfiles = runtimeProfiles,
                assignedRuntimeId = game?.runtimeProfileId,
                onSelect = { viewModel.assignRuntime(it) },
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.play() }, enabled = status !is RuntimeStatus.Running) {
                    Text("Play")
                }
                OutlinedButton(onClick = { viewModel.stop() }, enabled = status is RuntimeStatus.Running) {
                    Text("Stop")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Status: ${statusLabel(status)}", style = MaterialTheme.typography.bodyMedium)
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it.userMessage, color = MaterialTheme.colorScheme.error)
            }

            if (status is RuntimeStatus.Running || status is RuntimeStatus.Initializing) {
                Spacer(Modifier.height(12.dp))
                PerformanceStrip(performance)
            }

            Spacer(Modifier.height(16.dp))
            Text("Logs", style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(logs) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeSection(
    runtimeProfiles: List<RuntimeProfile>,
    assignedRuntimeId: Long?,
    onSelect: (Long) -> Unit,
) {
    Column {
        Text("Runtime", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))

        if (runtimeProfiles.isEmpty()) {
            Text(
                "No runtime profiles yet. Create one in Runtime Manager first.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            return
        }

        val assigned = runtimeProfiles.firstOrNull { it.id == assignedRuntimeId }
        var expanded by remember { mutableStateOf(false) }

        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(assigned?.name ?: "Select a runtime")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                runtimeProfiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text("${profile.name} (${profile.cpuBackend})") },
                        onClick = {
                            expanded = false
                            onSelect(profile.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PerformanceStrip(snapshot: PerformanceSnapshot?) {
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Performance", style = MaterialTheme.typography.titleSmall)
            if (snapshot == null) {
                Text("Sampling...", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "FPS: ${snapshot.fps?.let { "%.0f".format(it) } ?: "--"}  " +
                        "Frame: ${snapshot.frameTimeMs?.let { "%.1fms".format(it) } ?: "--"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "RAM: ${snapshot.ramUsedMb}MB / ${snapshot.ramTotalMb}MB  " +
                        "CPU: ${snapshot.cpuUsagePercent?.let { "%.0f%%".format(it) } ?: "--"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "GPU: unavailable (Android exposes no reliable per-app GPU utilization API)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun GraphicsBadge(resolution: GraphicsResolution) {
    val (label, color) = when (resolution) {
        is GraphicsResolution.VulkanDxvk -> "Vulkan + DXVK" to MaterialTheme.colorScheme.primary
        is GraphicsResolution.VulkanVkd3d -> "Vulkan + VKD3D (experimental)" to MaterialTheme.colorScheme.tertiary
        is GraphicsResolution.GlesFallback -> "OpenGL ES fallback" to MaterialTheme.colorScheme.secondary
        is GraphicsResolution.Unsupported -> "Unsupported" to MaterialTheme.colorScheme.error
    }
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, color = color, style = MaterialTheme.typography.titleSmall)
            Text(resolution.reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun statusLabel(status: RuntimeStatus): String = when (status) {
    RuntimeStatus.Idle -> "Idle"
    RuntimeStatus.Initializing -> "Initializing runtime..."
    RuntimeStatus.Running -> "Running"
    is RuntimeStatus.Stopped -> "Stopped (exit code ${status.exitCode})"
    is RuntimeStatus.Failed -> "Failed: ${status.error.userMessage}"
}

