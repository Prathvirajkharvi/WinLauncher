@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlauncher.app.ui.runtime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlauncher.app.data.db.entity.CpuBackend
import com.winlauncher.app.data.db.entity.RuntimeProfile
import com.winlauncher.app.viewmodel.AppViewModelFactory
import com.winlauncher.app.viewmodel.RuntimeViewModel

@Composable
fun RuntimeManagerScreen(factory: AppViewModelFactory) {
    val viewModel: RuntimeViewModel = viewModel(factory = factory)
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Runtime Manager") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) { Text("+") }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            items(profiles, key = { it.id }) { profile ->
                RuntimeCard(
                    profile,
                    onClone = { viewModel.clone(profile) },
                    onDelete = { viewModel.delete(profile) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showCreate) {
        CreateRuntimeDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, path, backend ->
                viewModel.create(name, path, backend)
                showCreate = false
            },
        )
    }
}

@Composable
private fun RuntimeCard(profile: RuntimeProfile, onClone: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleSmall)
            Text("CPU backend: ${profile.cpuBackend}", style = MaterialTheme.typography.bodySmall)
            Text("Prefix: ${profile.winePrefixRelativePath}", style = MaterialTheme.typography.bodySmall)
            Row(modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = onClone) { Text("Clone") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun CreateRuntimeDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, CpuBackend) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("prefixes/default") }
    var backend by remember { mutableStateOf(CpuBackend.BOX64) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New runtime profile") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Prefix path") })
                Spacer(Modifier.height(8.dp))
                Row {
                    CpuBackend.entries.forEach { b ->
                        FilterChip(selected = backend == b, onClick = { backend = b }, label = { Text(b.name) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name, path, backend) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
