package com.example.mdmapplication.ui.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(apps: List<String>) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MDM Launcher") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Allowed applications:")
            Spacer(modifier = Modifier.height(8.dp))

            apps.forEach {
                Text("• $it")
            }
        }
    }
}
