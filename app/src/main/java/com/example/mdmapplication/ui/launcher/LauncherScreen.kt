/*
package com.example.mdmapplication.ui.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mdmapplication.BuildConfig
import com.example.mdmapplication.model.LauncherApp
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    apps: List<LauncherApp>,
    isDeviceOwner: Boolean,
    onAppClick: (String) -> Unit,
    onClearPersistentHome: () -> Unit,
    onApplyKioskHome: () -> Unit,
    onExitLockTask: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("MDM Launcher") }) }
    ) { padding ->

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

            // ===== DEBUG CONTROL PANEL =====
            if (BuildConfig.DEBUG) {
                Card(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Dev Control Panel",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = if (isDeviceOwner) "Device Owner: YES" else "Device Owner: NO",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onClearPersistentHome,
                                enabled = isDeviceOwner
                            ) {
                                Text("Clear HOME")
                            }
                            Button(
                                onClick = onApplyKioskHome,
                                enabled = isDeviceOwner
                            ) {
                                Text("Apply Kiosk+HOME")
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onExitLockTask) {
                                Text("Exit LockTask")
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Tip: Clear HOME giúp Android Studio kill/re-run dễ hơn.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ===== APP GRID =====
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(apps) { app ->
                    Column(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .clickable { onAppClick(app.packageName) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = rememberDrawablePainter(app.icon),
                            contentDescription = app.label,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(app.label)
                    }
                }
            }
        }
    }
}
*/

//Screen moi

package com.example.mdmapplication.ui.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mdmapplication.BuildConfig
import com.example.mdmapplication.model.LauncherApp
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    apps: List<LauncherApp>,
    isDeviceOwner: Boolean,
    onAppClick: (String) -> Unit,
    onClearPersistentHome: () -> Unit,
    onApplyKioskHome: () -> Unit,
    onExitLockTask: () -> Unit
) {
    var devMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("MDM Launcher", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = if (isDeviceOwner) "Device Owner • Managed" else "Not Device Owner",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    // Không show nút thừa; chỉ để menu ẩn trong DEBUG để dev khi cần.
                    if (BuildConfig.DEBUG) {
                        TextButton(onClick = { devMenuExpanded = true }) { Text("DEV") }
                        DropdownMenu(
                            expanded = devMenuExpanded,
                            onDismissRequest = { devMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Exit LockTask") },
                                onClick = {
                                    devMenuExpanded = false
                                    onExitLockTask()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear persistent HOME") },
                                enabled = isDeviceOwner,
                                onClick = {
                                    devMenuExpanded = false
                                    onClearPersistentHome()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Apply kiosk policy now") },
                                enabled = isDeviceOwner,
                                onClick = {
                                    devMenuExpanded = false
                                    onApplyKioskHome()
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->

        val contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp
        )

        if (apps.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No apps allowed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Chờ backend trả config/allowedApps hoặc kiểm tra profile.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(apps, key = { it.packageName }) { app ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 116.dp)
                        .clickable { onAppClick(app.packageName) },
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            tonalElevation = 2.dp,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    painter = rememberDrawablePainter(app.icon),
                                    contentDescription = app.label,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
