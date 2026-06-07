[CmdletBinding()]
param(
    [string]$Serial = "emulator-5554",
    [string]$OutputDir = "benchmark-results",
    [switch]$SkipBuild,
    [int]$Iterations = 10,
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Users\ADMIN\AppData\Local\Android\Sdk" })
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $SdkRoot "platform-tools\adb.exe"
$gradlew = Join-Path $repoRoot "gradlew.bat"
$outputRoot = if ([System.IO.Path]::IsPathRooted($OutputDir)) { $OutputDir } else { Join-Path $repoRoot $OutputDir }
$benchmarkOutput = Join-Path $repoRoot "benchmark\build\outputs"
$androidEnvNames = @("HOME", "USERPROFILE", "ANDROID_USER_HOME", "ANDROID_AVD_HOME", "ANDROID_SDK_HOME", "ANDROID_SDK_ROOT", "ANDROID_HOME")
$startupAndroidEnv = @{}
foreach ($name in $androidEnvNames) {
    $startupAndroidEnv[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
if (-not (Test-Path $gradlew)) { throw "gradlew.bat not found at $gradlew" }
if (-not (Test-Path (Join-Path $repoRoot "benchmark\build.gradle.kts"))) { throw "BLOCKED: benchmark module does not exist" }

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

function Invoke-Adb {
    param([string[]]$Arguments)
    & $adb @Arguments
}

function Restore-AndroidEnvironment {
    foreach ($name in $androidEnvNames) {
        [Environment]::SetEnvironmentVariable($name, $startupAndroidEnv[$name], "Process")
        if ([string]::IsNullOrEmpty($startupAndroidEnv[$name])) {
            Remove-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue
        } else {
            Set-Item -LiteralPath "Env:\$name" -Value $startupAndroidEnv[$name]
        }
    }
}

function Invoke-Gradle {
    param([string[]]$Arguments)
    Push-Location $repoRoot
    try {
        & $gradlew @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed: $($Arguments -join ' ') exit=$LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Assert-DeviceReady {
    $state = (Invoke-Adb @("-s", $Serial, "get-state") 2>$null | Out-String).Trim()
    if ($state -ne "device") {
        throw "ADB device '$Serial' is not ready. state='$state'"
    }
}

function Get-Median {
    param([object[]]$Values)
    $numbers = @($Values | Where-Object { $null -ne $_ -and "$_" -ne "" } | ForEach-Object { [double]$_ } | Sort-Object)
    if ($numbers.Count -eq 0) { return "" }
    $mid = [int][Math]::Floor($numbers.Count / 2)
    if ($numbers.Count % 2 -eq 1) { return [Math]::Round($numbers[$mid], 2) }
    return [Math]::Round(($numbers[$mid - 1] + $numbers[$mid]) / 2, 2)
}

function Get-Metric {
    param(
        [pscustomobject]$Benchmark,
        [string]$Name
    )
    if ($null -eq $Benchmark.metrics) { return $null }
    $prop = $Benchmark.metrics.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-MetricField {
    param(
        [object]$Metric,
        [string]$Field
    )
    if ($null -eq $Metric) { return "" }
    $prop = $Metric.PSObject.Properties[$Field]
    if ($null -ne $prop) { return $prop.Value }
    return ""
}

function Get-IterationCount {
    param([object]$Metric)
    if ($null -eq $Metric) { return "" }
    $runs = $Metric.PSObject.Properties["runs"]
    if ($null -ne $runs -and $null -ne $runs.Value) {
        return @($runs.Value).Count
    }
    return ""
}

function Read-BenchmarkRows {
    param([string[]]$JsonPaths)
    $rows = @()
    foreach ($path in $JsonPaths) {
        try {
            $json = Get-Content -Raw -Path $path | ConvertFrom-Json
        } catch {
            continue
        }
        $benchmarks = @()
        if ($null -ne $json.benchmarks) {
            $benchmarks = @($json.benchmarks)
        }
        foreach ($benchmark in $benchmarks) {
            $name = if ($benchmark.name) { $benchmark.name } elseif ($benchmark.methodName) { $benchmark.methodName } else { [System.IO.Path]::GetFileNameWithoutExtension($path) }
            $className = if ($benchmark.className) { $benchmark.className } else { "" }
            $fullName = if ($className) { "$className.$name" } else { $name }
            $startupMode = if ($name -match "(?i)cold") { "cold" } elseif ($name -match "(?i)warm") { "warm" } elseif ($name -match "(?i)hot") { "hot" } else { "" }
            $target = if ($name -match "(?i)mainActivity") { "MDM MainActivity" } elseif ($name -match "(?i)launcher|runtimeWake") { "MDM LauncherActivity" } else { "MDM Agent" }
            $tti = Get-Metric -Benchmark $benchmark -Name "timeToInitialDisplayMs"
            $ttf = Get-Metric -Benchmark $benchmark -Name "timeToFullDisplayMs"
            $cpu = Get-Metric -Benchmark $benchmark -Name "frameDurationCpuMs"
            $overrun = Get-Metric -Benchmark $benchmark -Name "frameOverrunMs"
            $gfxFrame50 = Get-Metric -Benchmark $benchmark -Name "gfxFrameTime50thPercentileMs"
            $gfxFrame90 = Get-Metric -Benchmark $benchmark -Name "gfxFrameTime90thPercentileMs"
            $gfxFrame95 = Get-Metric -Benchmark $benchmark -Name "gfxFrameTime95thPercentileMs"
            $gfxJank = Get-Metric -Benchmark $benchmark -Name "gfxFrameJankPercent"
            $rows += [pscustomobject]@{
                testName = $fullName
                target = $target
                startupMode = $startupMode
                timeToInitialDisplayMedian = Get-MetricField -Metric $tti -Field "median"
                timeToInitialDisplayMin = Get-MetricField -Metric $tti -Field "minimum"
                timeToInitialDisplayMax = Get-MetricField -Metric $tti -Field "maximum"
                timeToFullDisplayMedian = Get-MetricField -Metric $ttf -Field "median"
                frameDurationCpuMedian = $(if ($cpu) { Get-MetricField -Metric $cpu -Field "median" } else { Get-MetricField -Metric $gfxFrame50 -Field "median" })
                frameOverrunMedian = Get-MetricField -Metric $overrun -Field "median"
                frameOverrunP90 = $(if ($overrun) { Get-MetricField -Metric $overrun -Field "P90" } else { Get-MetricField -Metric $gfxFrame90 -Field "median" })
                frameOverrunP95 = $(if ($overrun) { Get-MetricField -Metric $overrun -Field "P95" } else { Get-MetricField -Metric $gfxFrame95 -Field "median" })
                gfxFrameJankPercentMedian = Get-MetricField -Metric $gfxJank -Field "median"
                frameMetricSource = $(if ($cpu -or $overrun) { "FrameTimingMetric" } else { "FrameTimingGfxInfoMetric" })
                iterations = (Get-IterationCount -Metric $(if ($tti) { $tti } elseif ($overrun) { $overrun } elseif ($cpu) { $cpu } else { $gfxFrame50 }))
                resultFile = (Resolve-Path -Path $path).Path
            }
        }
    }
    return $rows
}

function Copy-BenchmarkArtifacts {
    if (-not (Test-Path $benchmarkOutput)) { return @() }
    $artifactDir = Join-Path $outputRoot "macrobenchmark-artifacts"
    New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
    $patterns = @("*.json", "*.trace", "*.perfetto-trace", "*.txt", "*.html")
    $copied = @()
    foreach ($pattern in $patterns) {
        foreach ($file in (Get-ChildItem -Path $benchmarkOutput -Recurse -File -Filter $pattern -ErrorAction SilentlyContinue)) {
            $relative = $file.FullName.Substring($benchmarkOutput.Length).TrimStart("\", "/")
            $destination = Join-Path $artifactDir $relative
            New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
            $copied += $destination
        }
    }
    return $copied
}

function Write-Summary {
    param(
        [object[]]$Rows,
        [string[]]$Artifacts,
        [int]$CrashCount,
        [int]$AnrCount
    )
    $path = Join-Path $outputRoot "SUMMARY.md"
    $lines = @()
    $lines += "# MDM Android Benchmark Summary"
    $lines += ""
    $lines += "Generated: $(Get-Date -Format s)"
    $lines += "Serial: ``$Serial``"
    $lines += ""
    $lines += "## Table 1: Startup comparison"
    $lines += ""
    $lines += "| Target | Startup mode | timeToInitialDisplayMs median | timeToFullDisplayMs median | min | max | iterations | notes |"
    $lines += "| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |"
    foreach ($row in ($Rows | Where-Object { $_.timeToInitialDisplayMedian -ne "" })) {
        $lines += "| $($row.target) | $($row.startupMode) | $($row.timeToInitialDisplayMedian) | $($row.timeToFullDisplayMedian) | $($row.timeToInitialDisplayMin) | $($row.timeToInitialDisplayMax) | $($row.iterations) | Macrobenchmark |"
    }
    if (-not ($Rows | Where-Object { $_.timeToInitialDisplayMedian -ne "" })) {
        $lines += "| N/A | N/A |  |  |  |  |  | No parsed startup result |"
    }
    $lines += ""
    $lines += "## Table 2: Frame timing"
    $lines += ""
    $lines += "| Target | Scenario | frameDurationCpuMs or gfxFrameTime50thMs median | frameOverrunMs median | frameOverrunMs P90 or gfxFrameTime90thMs | frameOverrunMs P95 or gfxFrameTime95thMs | jank/frame issue |"
    $lines += "| --- | --- | ---: | ---: | ---: | ---: | --- |"
    foreach ($row in ($Rows | Where-Object { $_.frameDurationCpuMedian -ne "" -or $_.frameOverrunMedian -ne "" })) {
        $issue = if ($row.frameMetricSource -eq "FrameTimingGfxInfoMetric") { "GfxInfo jank median=$($row.gfxFrameJankPercentMedian)%" } elseif ($row.frameOverrunP95 -ne "" -and [double]$row.frameOverrunP95 -gt 0) { "Check positive P95 overrun" } else { "" }
        $lines += "| $($row.target) | $($row.testName) | $($row.frameDurationCpuMedian) | $($row.frameOverrunMedian) | $($row.frameOverrunP90) | $($row.frameOverrunP95) | $issue |"
    }
    if (-not ($Rows | Where-Object { $_.frameDurationCpuMedian -ne "" -or $_.frameOverrunMedian -ne "" })) {
        $lines += "| N/A | N/A |  |  |  |  | No parsed frame result |"
    }
    $lines += ""
    $lines += "## Table 3: Stability notes"
    $lines += ""
    $lines += "| Item | Value |"
    $lines += "| --- | --- |"
    $lines += "| Crash count from filtered logcat | $CrashCount |"
    $lines += "| ANR count from filtered logcat | $AnrCount |"
    $lines += "| Benchmark failures | $(if ($Rows.Count -eq 0) { 'No parsed result or benchmark did not run' } else { 'See Gradle output if non-zero' }) |"
    $lines += "| Manual verification required | Device Owner/kiosk runtime behavior |"
    $lines += ""
    $lines += "## Result files"
    foreach ($artifact in $Artifacts) {
        $lines += "- ``$artifact``"
    }
    if ($Artifacts.Count -eq 0) {
        $lines += "- No benchmark artifacts copied."
    }
    Set-Content -Encoding UTF8 -Path $path -Value ($lines -join "`n")
    return $path
}

Assert-DeviceReady
Invoke-Adb @("-s", $Serial, "logcat", "-c") | Out-Null

if (-not $SkipBuild) {
    Invoke-Gradle @(":app:assembleDebug")
}

try {
    & (Join-Path $PSScriptRoot "install-debug.ps1") -Serial $Serial -SdkRoot $SdkRoot
    $installExit = $LASTEXITCODE
} finally {
    Restore-AndroidEnvironment
}
if ($installExit -ne 0) {
    throw "install-debug.ps1 failed with exit=$installExit"
}

Push-Location $repoRoot
try {
    & $gradlew ":benchmark:tasks" "--all" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle task lookup failed for :benchmark"
    }
    & $gradlew ":benchmark:connectedCheck"
    if ($LASTEXITCODE -ne 0) {
        throw ":benchmark:connectedCheck failed with exit=$LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$artifacts = @(Copy-BenchmarkArtifacts)
$jsonFiles = @($artifacts | Where-Object { $_ -like "*.json" })
$rows = @(Read-BenchmarkRows -JsonPaths $jsonFiles)
$logText = (Invoke-Adb @("-s", $Serial, "logcat", "-d", "-v", "time") 2>$null | Out-String)
$fatalLines = @($logText -split '\r?\n' | Where-Object { $_ -match "(?i)FATAL EXCEPTION" })
$anrLines = @($logText -split '\r?\n' | Where-Object { $_ -match "(?i)\bANR\b" })
$summaryPath = Write-Summary -Rows $rows -Artifacts $artifacts -CrashCount $fatalLines.Count -AnrCount $anrLines.Count

Write-Host "Macrobenchmark summary: $summaryPath"
