@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlauncher.app.ui.addgame

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlauncher.app.domain.graphics.DirectXTarget
import com.winlauncher.app.viewmodel.AddGameViewModel
import com.winlauncher.app.viewmodel.AppViewModelFactory

@Composable
fun AddGameScreen(factory: AppViewModelFactory, onSaved: () -> Unit) {
    val viewModel: AddGameViewModel = viewModel(factory = factory)
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var executableUri by remember { mutableStateOf<String?>(null) }
    var workingDirUri by remember { mutableStateOf<String?>(null) }
    var directXTarget by remember { mutableStateOf(DirectXTarget.DX9_10_11) }

    val error by viewModel.error.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    LaunchedEffect(saved) { if (saved) onSaved() }

    val pickExe = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            executableUri = uri.toString()
            if (name.isBlank()) {
                name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".exe") ?: "New Game"
            }
        }
    }

    val pickWorkingDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            workingDirUri = uri.toString()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Add Game") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Game name") },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = { pickExe.launch(arrayOf("application/octet-stream", "application/x-msdownload", "*/*")) }) {
                Text(if (executableUri == null) "Select .exe" else "Executable selected \u2713")
            }
            executableUri?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }

            Button(onClick = { pickWorkingDir.launch(null) }) {
                Text(if (workingDirUri == null) "Select working directory (optional)" else "Working dir selected \u2713")
            }

            Text("DirectX target", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DirectXTarget.entries.forEach { target ->
                    FilterChip(
                        selected = directXTarget == target,
                        onClick = { directXTarget = target },
                        label = { Text(target.name) },
                    )
                }
            }

            error?.let { Text(it.userMessage, color = MaterialTheme.colorScheme.error) }

            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    viewModel.save(
                        name = name,
                        executableUri = executableUri ?: "",
                        workingDirectoryUri = workingDirUri,
                        iconUri = null,
                        directXTarget = directXTarget,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save game") }
        }
    }
}
