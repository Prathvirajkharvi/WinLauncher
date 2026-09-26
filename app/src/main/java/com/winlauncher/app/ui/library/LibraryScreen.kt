@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlauncher.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlauncher.app.data.db.entity.GameProfile
import com.winlauncher.app.viewmodel.AppViewModelFactory
import com.winlauncher.app.viewmodel.LibraryViewModel

@Composable
fun LibraryScreen(
    factory: AppViewModelFactory,
    onAddGame: () -> Unit,
    onOpenGame: (Long) -> Unit,
    onOpenRuntimeManager: () -> Unit,
    onOpenControllerManager: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(factory = factory)
    val games by viewModel.games.collectAsStateWithLifecycle()
    val runtimeNames by viewModel.runtimeNames.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game Library") },
                actions = {
                    IconButton(onClick = onOpenRuntimeManager) { Text("Runtimes") }
                    IconButton(onClick = onOpenControllerManager) { Text("Controllers") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddGame) { Icon(Icons.Default.Add, contentDescription = "Add game") }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = "",
                onValueChange = { viewModel.onSearchChanged(it) },
                label = { Text("Search games") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            if (games.isEmpty()) {
                Text("No games match. Tap + to add one.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(games, key = { it.id }) { game ->
                        GameCard(
                            game,
                            runtimeName = game.runtimeProfileId?.let { runtimeNames[it] },
                            onClick = { onOpenGame(game.id) },
                            onDelete = { viewModel.deleteGame(game) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(game: GameProfile, runtimeName: String?, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.height(190.dp)) {
        Column(modifier = Modifier.padding(12.dp).fillMaxSize()) {
            Text(game.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(
                if (game.lastPlayed != null) "Last played: recently" else "Never played",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Runtime: ${runtimeName ?: "not set"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClick) { Text("Play") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        }
    }
}
