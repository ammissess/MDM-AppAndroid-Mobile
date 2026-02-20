//package com.example.mdmapplication.ui.launcher
//
//import androidx.compose.foundation.Image
//import androidx.compose.foundation.clickable
//import androidx.compose.foundation.layout.*
//import androidx.compose.foundation.lazy.grid.GridCells
//import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
//import androidx.compose.foundation.lazy.grid.items   // ← DÒNG QUAN TRỌNG
//import androidx.compose.material3.*
//import androidx.compose.runtime.Composable
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.unit.dp
//import com.example.mdmapplication.model.LauncherApp
//import com.google.accompanist.drawablepainter.rememberDrawablePainter
//
//@OptIn(ExperimentalMaterial3Api::class)
//@Composable
//fun LauncherScreen(
//    apps: List<LauncherApp>,
//    onAppClick: (String) -> Unit
//) {
//    Scaffold(
//        topBar = {
//            TopAppBar(title = { Text("MDM Launcher") })
//        }
//    ) { padding ->
//        LazyVerticalGrid(
//            columns = GridCells.Fixed(3),
//            modifier = Modifier
//                .padding(padding)
//                .padding(16.dp)
//        ) {
//            items(apps) { app ->
//                Column(
//                    modifier = Modifier
//                        .padding(8.dp)
//                        .fillMaxWidth()
//                        .clickable { onAppClick(app.packageName) },
//                    horizontalAlignment = Alignment.CenterHorizontally
//                ) {
//                    Image(
//                        painter = rememberDrawablePainter(app.icon),
//                        contentDescription = app.label,
//                        modifier = Modifier.size(64.dp)
//                    )
//                    Spacer(modifier = Modifier.height(8.dp))
//                    Text(app.label)
//                }
//            }
//        }
//    }
//}


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
