package com.winlauncher.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlauncher.app.viewmodel.AppViewModelFactory
import com.winlauncher.app.viewmodel.LibraryViewModel

@Composable
fun HomeScreen(
    factory: AppViewModelFactory,
    onOpenLibrary: () -> Unit,
    onOpenGame: (Long) -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(factory = factory)
    val games by viewModel.games.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("WinLauncher") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Recent games", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            if (games.isEmpty()) {
                EmptyState(onOpenLibrary)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(games.take(10)) { game ->
                        Card(
                            modifier = Modifier
                                .width(160.dp)
                                .height(200.dp),
                            onClick = { onOpenGame(game.id) },
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp).fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(game.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                Text(
                                    if (game.lastPlayed != null) "Recently played" else "Never played",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenLibrary) { Text("Open full library") }
            }
        }
    }
}

@Composable
private fun EmptyState(onOpenLibrary: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No games yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Add a Windows game to get started.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenLibrary) { Text("Go to Library") }
    }
}
