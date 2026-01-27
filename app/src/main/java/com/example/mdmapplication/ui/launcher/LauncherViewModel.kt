package com.example.mdmapplication.ui.launcher
import androidx.lifecycle.ViewModel

class LauncherViewModel : ViewModel() {
    val allowedApps = listOf(
        "com.android.settings",
        "com.android.chrome"
    )
}
