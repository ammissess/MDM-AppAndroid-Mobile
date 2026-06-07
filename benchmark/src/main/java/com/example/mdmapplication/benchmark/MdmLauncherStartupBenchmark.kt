package com.example.mdmapplication.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class MdmLauncherStartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup_mainActivity() = measureStartup(
        startupMode = StartupMode.COLD,
        componentName = MAIN_ACTIVITY_COMPONENT
    )

    @Test
    fun warmStartup_mainActivity() = measureStartup(
        startupMode = StartupMode.WARM,
        componentName = MAIN_ACTIVITY_COMPONENT
    )

    @Test
    fun hotStartup_mainActivity() = measureStartup(
        startupMode = StartupMode.HOT,
        componentName = MAIN_ACTIVITY_COMPONENT
    )

    @Test
    fun coldStartup_launcherActivityHome() = measureStartup(
        startupMode = StartupMode.COLD,
        componentName = LAUNCHER_ACTIVITY_COMPONENT
    )

    @Test
    fun warmStartup_launcherActivityHome() = measureStartup(
        startupMode = StartupMode.WARM,
        componentName = LAUNCHER_ACTIVITY_COMPONENT
    )

    @Test
    fun hotStartup_launcherActivityHome() = measureStartup(
        startupMode = StartupMode.HOT,
        componentName = LAUNCHER_ACTIVITY_COMPONENT
    )

    private fun measureStartup(
        startupMode: StartupMode,
        componentName: String,
        iterations: Int = DEFAULT_ITERATIONS
    ) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingGfxInfoMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = startupMode,
            iterations = iterations,
            setupBlock = {
                pressHome()
            }
        ) {
            device.startComponentWithAmStart(componentName)
        }
    }
}
