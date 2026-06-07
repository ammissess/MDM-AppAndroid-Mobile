# MDM Android Macrobenchmark

This module measures Android MDM Agent startup and launcher frame timing for the graduation report. It does not change Device Owner, kiosk, command, policy, API, or backend logic.

## What Is Measured

- `MdmLauncherStartupBenchmark`: cold, warm, and hot startup for `MainActivity` and `LauncherActivity`.
- `MdmLauncherInteractionBenchmark`: frame timing while opening the launcher and repeatedly returning home.
- `MdmRuntimeWakeBenchmark`: startup/frame timing for the runtime wake intent used by MDM wake-up flows.
- `scripts/benchmark-launchers.ps1`: ADB `am start -W` startup comparison for MDM activities and the resolved HOME launcher, such as Nexus Launcher when available.

When the app is Device Owner and the default HOME launcher, Android can report `LaunchState: UNKNOWN` for direct `startActivityAndWait()` launches. `MainActivity` also immediately forwards to `LauncherActivity`, so it does not draw its own screen. The benchmark tests therefore start activities with UiAutomator shell `am start -W` inside the measured block while keeping Macrobenchmark metrics; the companion ADB script keeps raw startup timings for report comparison. Frame timing uses `FrameTimingGfxInfoMetric` because Perfetto `FrameTimingMetric` produced no expected/actual frame slices on this HOME/Device Owner emulator path.

## How To Run

From the Android project root:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\scripts\run-macrobenchmark.ps1 -Serial emulator-5554
```

For a full AVD provisioning and benchmark pass:

```powershell
.\scripts\run-performance-benchmark-suite.ps1 -StartIndex 20 -DeviceCount 3 -Api 36 -Iterations 10 -WipeData
```

## Conditions

- Run on a connected emulator/device with API 24 or newer.
- The benchmark target uses the app `benchmark` build type, signed with the debug key and non-debuggable.
- Backend is not required for startup/frame measurements, but runtime UI may show setup/error state if no backend/profile is available.
- Device Owner/kiosk behavior still requires separate runtime evidence; Macrobenchmark only measures launch and frame timing.

## Result Files

- Raw Macrobenchmark artifacts: `benchmark/build/outputs/**`
- Aggregated files copied by scripts: `benchmark-results/`
- Report table: `benchmark-results/SUMMARY.md`

## Metrics For The Report

- `timeToInitialDisplayMs`: startup time until the first frame is displayed.
- `timeToFullDisplayMs`: startup time until full display, when Android reports it.
- `frameDurationCpuMs`: CPU time spent producing frames when Perfetto frame timing is available.
- `gfxFrameTime50thPercentileMs`: GfxInfo fallback frame time used on Device Owner/HOME emulator paths.
- `frameOverrunMs`: how far frames miss or beat the expected frame deadline when Perfetto frame timing is available; high positive values suggest jank.
- `gfxFrameJankPercent`: GfxInfo fallback jank percentage.

Use the medians from `benchmark-results/SUMMARY.md` for the main report table and keep min/max as supporting evidence.

## Limits

Macrobenchmark does not replace CPU, RAM, network, or Device Owner/kiosk verification. Use Android Studio Profiler, `dumpsys meminfo`, `dumpsys cpuinfo`, `dumpsys gfxinfo`, and manual runtime tests for those sections.
