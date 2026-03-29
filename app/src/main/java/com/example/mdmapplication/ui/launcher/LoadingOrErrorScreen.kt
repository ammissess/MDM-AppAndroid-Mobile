package com.example.mdmapplication.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val LoadingBgTop = Color(0x3306101E)
private val LoadingBgMid = Color(0x220A1324)
private val LoadingBgBottom = Color(0x4403070D)
private val LoadingGlass = Color(0x99101827)
private val LoadingBorder = Color.White.copy(alpha = 0.12f)
private val LoadingText = Color(0xFFF2F7FF)
private val LoadingMuted = Color(0xFFAAB7CC)

@Composable
fun LoadingOrErrorScreen(
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    val title = when {
        loading -> "Đang tải launcher"
        error != null -> "Không thể tải launcher"
        else -> "Đang chuẩn bị launcher"
    }

    val message = when {
        loading -> "Thiết bị đang đồng bộ trạng thái với MDM server..."
        error != null -> error
        else -> "Launcher chưa nhận đủ state để hiển thị danh sách ứng dụng."
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(LoadingBgTop, LoadingBgMid, LoadingBgBottom)
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = LoadingGlass,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .border(1.dp, LoadingBorder, RoundedCornerShape(28.dp))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = title,
                        color = LoadingText,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = message ?: "Không có thông tin lỗi",
                        color = if (error != null) Color(0xFFFF9AA6) else LoadingMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    if (loading) {
                        CircularProgressIndicator()
                    }

                    if (!loading) {
                        Button(onClick = onRetry) {
                            Text("Thử lại")
                        }
                    }
                }
            }
        }
    }
}