@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlauncher.app.ui.runtime

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlauncher.app.data.db.entity.CpuBackend
import com.winlauncher.app.data.db.entity.RuntimeProfile
import com.winlauncher.app.domain.runtime.RuntimeComponent
import com.winlauncher.app.domain.runtime.RuntimeComponentStatus
import com.winlauncher.app.viewmodel.AppViewModelFactory
import com.winlauncher.app.viewmodel.RuntimeInstallationViewModel
import com.winlauncher.app.viewmodel.RuntimeViewModel

@Composable
fun RuntimeManagerScreen(factory: AppViewModelFactory) {
    val viewModel: RuntimeViewModel = viewModel(factory = factory)
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    val installViewModel: RuntimeInstallationViewModel = viewModel(factory = factory)
    val installStatus by installViewModel.status.collectAsStateWithLifecycle()
    val importError by installViewModel.importError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showCreate by remember { mutableStateOf(false) }
    var pendingImportComponent by remember { mutableStateOf<RuntimeComponent?>(null) }

    val pickImportFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val component = pendingImportComponent
        pendingImportComponent = null
        if (uri != null && component != null) {
            val displayName = queryDisplayName(context, uri)
            installViewModel.import(
                component = component,
                uri = uri,
                displayFileName = displayName,
                versionLabel = null,
                contentResolver = context.contentResolver,
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Runtime Manager") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) { Text("+") }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item {
                Text("Installed components", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Runtime directory: ${installStatus.runtimeRootPath}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!installStatus.readyForLaunch) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Wine and Box64 are required before a real launch will work.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                importError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Import failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
            }
            items(installStatus.components, key = { it.component.name }) { status ->
                ComponentRow(
                    status = status,
                    onImport = {
                        pendingImportComponent = status.component
                        pickImportFile.launch(arrayOf("*/*"))
                    },
                )
                Spacer(Modifier.height(4.dp))
            }

            item {
                Spacer(Modifier.height(20.dp))
                Text("Runtime profiles", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
            }
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

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun ComponentRow(status: RuntimeComponentStatus, onImport: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(status.component.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (status.installed) "Installed \u00b7 ${status.version}" else "Not installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onImport) { Text(if (status.installed) "Replace" else "Import") }
        }
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

