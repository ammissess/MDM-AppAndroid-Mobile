package com.example.mdmapplication.ui.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mdmapplication.BuildConfig
import com.example.mdmapplication.model.LauncherApp
import com.google.accompanist.drawablepainter.rememberDrawablePainter

private val LauncherBgTop = Color(0x6607101C)
private val LauncherBgMid = Color(0x990A1424)
private val LauncherBgBottom = Color(0xCC050A12)
private val LauncherGlass = Color(0xAA121C2B)
private val LauncherCard = Color(0xB0142031)
private val LauncherBorder = Color.White.copy(alpha = 0.12f)
private val LauncherText = Color(0xFFF2F7FF)
private val LauncherMuted = Color(0xFFAAB7CC)
private val LauncherAccent = Color(0xFF7CCBFF)
private val LauncherAccentSoft = Color(0xFFB8E2FF)

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

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(LauncherBgTop, LauncherBgMid, LauncherBgBottom)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                color = LauncherGlass,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, LauncherBorder, RoundedCornerShape(28.dp))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "MDM Launcher",
                                color = LauncherText,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isDeviceOwner) {
                                    "Thiết bị đang ở chế độ quản trị và launcher đang kiểm soát truy cập ứng dụng."
                                } else {
                                    "Thiết bị chưa ở chế độ Device Owner. Một số chính sách kiosk có thể chưa được áp dụng."
                                },
                                color = LauncherMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        if (BuildConfig.DEBUG) {
                            Box {
                                TextButton(
                                    onClick = { devMenuExpanded = true }
                                ) {
                                    Text("DEV", color = LauncherAccent)
                                }

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
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        InfoChip(text = if (isDeviceOwner) "Managed" else "Unmanaged")
                        InfoChip(text = "${apps.size} apps")
                        if (BuildConfig.DEBUG) {
                            InfoChip(text = "DEBUG")
                        }
                    }
                }
            }

            if (apps.isEmpty()) {
                Surface(
                    color = LauncherGlass,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, LauncherBorder, RoundedCornerShape(28.dp))
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = "Chưa có ứng dụng khả dụng",
                                color = LauncherText,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Chờ backend trả allowedApps hoặc kiểm tra profile liên kết với thiết bị.",
                                color = LauncherMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        LauncherAppCard(
                            app = app,
                            onClick = { onAppClick(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        color = LauncherAccent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.border(
            width = 1.dp,
            color = LauncherAccent.copy(alpha = 0.22f),
            shape = RoundedCornerShape(999.dp)
        )
    ) {
        Text(
            text = text,
            color = LauncherAccentSoft,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}


@Composable
private fun LauncherAppCard(
    app: LauncherApp,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Surface(
            color = Color.White.copy(alpha = 0.08f),
            shape = CircleShape,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = rememberDrawablePainter(app.icon),
                    contentDescription = app.label,
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = app.label,
            color = LauncherText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}