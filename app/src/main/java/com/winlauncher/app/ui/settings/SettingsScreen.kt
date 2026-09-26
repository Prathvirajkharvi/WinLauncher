@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlauncher.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlauncher.app.LauncherApplication

@Composable
fun SettingsScreen(app: LauncherApplication) {
    var useDummy by remember { mutableStateOf(app.useDummyEngine()) }

    Scaffold(topBar = { TopAppBar(title = { Text("Global Settings") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Runtime engine", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (useDummy) "Dummy test engine (no real games)" else "Real Wine + Box64 engine")
                    Text(
                        "Switch to the dummy engine for UI/process-lifecycle testing on a device " +
                            "with no Wine/Box64 installed yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = useDummy,
                    onCheckedChange = {
                        useDummy = it
                        app.setUseDummyEngine(it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Per-game graphics/controller/runtime settings live on each game's " +
                    "Details screen. Install Wine/Box64/DXVK/VKD3D components in Runtime Manager.",
            )
            Spacer(Modifier.height(16.dp))
            Text("Open Source Licenses", style = MaterialTheme.typography.titleSmall)
            Text("See docs/OPEN_SOURCE_NOTICES.md in the project repository.")
        }
    }
}

