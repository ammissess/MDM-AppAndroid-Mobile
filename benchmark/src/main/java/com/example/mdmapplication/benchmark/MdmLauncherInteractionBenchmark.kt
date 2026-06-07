package com.example.mdmapplication.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class MdmLauncherInteractionBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun launcherOpenAndIdle_frameTiming() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingGfxInfoMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = DEFAULT_ITERATIONS,
            setupBlock = {
                pressHome()
            }
        ) {
            device.startComponentWithAmStart(LAUNCHER_ACTIVITY_COMPONENT)
            device.waitForIdle(2_000)
        }
    }

    @Test
    fun launcherPressHomeRepeated_frameTiming() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingGfxInfoMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = DEFAULT_ITERATIONS,
            setupBlock = {
                device.startComponentWithAmStart(LAUNCHER_ACTIVITY_COMPONENT)
                device.waitForIdle(1_000)
            }
        ) {
            repeat(3) {
                device.pressHome()
                device.waitForIdle(500)
                device.startComponentWithAmStart(LAUNCHER_ACTIVITY_COMPONENT)
                device.waitForIdle(500)
            }
        }
    }
}
