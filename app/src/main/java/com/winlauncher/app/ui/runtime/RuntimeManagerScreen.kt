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
import com.winlauncher.app.domain.runtime.LaunchPreflight
import com.winlauncher.app.domain.runtime.RuntimeComponent
import com.winlauncher.app.domain.runtime.RuntimeComponentStatus
import com.winlauncher.app.domain.runtime.RuntimeInstallationStatus
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
    var pendingReplaceConfirm by remember { mutableStateOf<RuntimeComponentStatus?>(null) }
    var pendingRemoveConfirm by remember { mutableStateOf<RuntimeComponentStatus?>(null) }
    var replaceExisting by remember { mutableStateOf(false) }

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
                replaceExisting = replaceExisting,
            )
        }
        replaceExisting = false
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
                        if (status.installed) {
                            pendingReplaceConfirm = status
                        } else {
                            pendingImportComponent = status.component
                            pickImportFile.launch(arrayOf("*/*"))
                        }
                    },
                    onRemove = { pendingRemoveConfirm = status },
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
                    profile = profile,
                    installStatus = installStatus,
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

    pendingReplaceConfirm?.let { status ->
        AlertDialog(
            onDismissRequest = { pendingReplaceConfirm = null },
            title = { Text("Replace ${status.component.displayName}?") },
            text = {
                Text(
                    "This removes the currently installed version (${status.version}) and installs " +
                        "the new package in its place. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    replaceExisting = true
                    pendingImportComponent = status.component
                    pendingReplaceConfirm = null
                    pickImportFile.launch(arrayOf("*/*"))
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pendingReplaceConfirm = null }) { Text("Cancel") } },
        )
    }

    pendingRemoveConfirm?.let { status ->
        AlertDialog(
            onDismissRequest = { pendingRemoveConfirm = null },
            title = { Text("Remove ${status.component.displayName}?") },
            text = {
                Text(
                    "This deletes the installed version (${status.version}) from the app's runtime " +
                        "directory. Stop any running game first -- this doesn't stop one for you.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    installViewModel.remove(status.component)
                    pendingRemoveConfirm = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoveConfirm = null }) { Text("Cancel") } },
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
private fun ComponentRow(status: RuntimeComponentStatus, onImport: () -> Unit, onRemove: () -> Unit) {
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
                if (status.installed && status.architecture != "n/a") {
                    Text("Architecture: ${status.architecture}", style = MaterialTheme.typography.bodySmall)
                }
                if (status.guestLibraryCount > 0) {
                    Text(
                        "${status.guestLibraryCount} x86 guest librar${if (status.guestLibraryCount == 1) "y" else "ies"} bundled",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(status.path, style = MaterialTheme.typography.bodySmall)
            }
            if (status.installed) {
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            TextButton(onClick = onImport) { Text(if (status.installed) "Replace" else "Import") }
        }
    }
}

@Composable
private fun RuntimeCard(
    profile: RuntimeProfile,
    installStatus: RuntimeInstallationStatus,
    onClone: () -> Unit,
    onDelete: () -> Unit,
) {
    // LaunchPreflight is the single source of truth for what this profile's backend needs --
    // no separate "Wine + Box64" assumption here. NATIVE_ARM profiles correctly show ready
    // once Wine alone is installed; BOX86 profiles correctly ask for Box86, not Box64.
    val cpuBackend = remember(profile.cpuBackend) {
        runCatching { CpuBackend.valueOf(profile.cpuBackend) }.getOrNull()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleSmall)
            Text("CPU backend: ${profile.cpuBackend}", style = MaterialTheme.typography.bodySmall)
            Text("Prefix: ${profile.winePrefixRelativePath}", style = MaterialTheme.typography.bodySmall)

            if (cpuBackend != null) {
                val ready = LaunchPreflight.isLaunchable(installStatus, cpuBackend)
                Spacer(Modifier.height(4.dp))
                if (ready) {
                    Text(
                        "Ready to launch",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    val missing = LaunchPreflight.missingComponents(
                        installStatus,
                        LaunchPreflight.requiredComponents(cpuBackend),
                    )
                    Text(
                        "Cannot launch yet:\n" +
                            missing.joinToString("\n") { "${it.displayName.substringBefore(" (")} is missing." },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

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

