package com.winlauncher.app.ui.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlauncher.app.domain.graphics.GraphicsResolution
import com.winlauncher.app.domain.runtime.RuntimeStatus
import com.winlauncher.app.viewmodel.AppViewModelFactory
import com.winlauncher.app.viewmodel.GameDetailsViewModel

@Composable
fun GameDetailsScreen(factory: AppViewModelFactory, gameId: Long) {
    val viewModel: GameDetailsViewModel = viewModel(factory = factory)
    LaunchedEffect(gameId) { viewModel.load(gameId) }

    val game by viewModel.game.collectAsStateWithLifecycle()
    val resolution by viewModel.graphicsResolution.collectAsStateWithLifecycle()
    val status by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(game?.name ?: "Game") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            resolution?.let { GraphicsBadge(it) }
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
