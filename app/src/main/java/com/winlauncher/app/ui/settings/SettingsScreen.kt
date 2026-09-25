@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlauncher.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    Scaffold(topBar = { TopAppBar(title = { Text("Global Settings") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Per-game graphics/controller/runtime settings live on each game's " +
                "Details screen. This screen is reserved for app-wide defaults " +
                "(default runtime profile, default FPS cap, telemetry opt-in) " +
                "once the real runtime is integrated.")
            Spacer(Modifier.height(16.dp))
            Text("Open Source Licenses", style = MaterialTheme.typography.titleSmall)
            Text("See docs/OPEN_SOURCE_NOTICES.md in the project repository.")
        }
    }
}
