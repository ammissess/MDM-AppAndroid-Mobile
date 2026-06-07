[CmdletBinding()]
param(
    [int]$StartIndex = 20,
    [int]$DeviceCount = 3,
    [int]$Api = 36,
    [int]$Iterations = 10,
    [switch]$WipeData,
    [switch]$SkipBuild,
    [string]$Serial = "emulator-5554",
    [string]$OutputDir = "benchmark-results",
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Users\ADMIN\AppData\Local\Android\Sdk" })
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $SdkRoot "platform-tools\adb.exe"
$emulator = Join-Path $SdkRoot "emulator\emulator.exe"
$gradlew = Join-Path $repoRoot "gradlew.bat"
$outputRoot = if ([System.IO.Path]::IsPathRooted($OutputDir)) { $OutputDir } else { Join-Path $repoRoot $OutputDir }
$androidEnvNames = @("HOME", "USERPROFILE", "ANDROID_USER_HOME", "ANDROID_AVD_HOME", "ANDROID_SDK_HOME", "ANDROID_SDK_ROOT", "ANDROID_HOME")
$startupAndroidEnv = @{}
foreach ($name in $androidEnvNames) {
    $startupAndroidEnv[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

if ($DeviceCount -lt 1) { throw "DeviceCount must be >= 1" }
if ($Iterations -lt 1) { throw "Iterations must be >= 1" }
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
if (-not (Test-Path $emulator)) { throw "emulator.exe not found at $emulator" }
if (-not (Test-Path $gradlew)) { throw "gradlew.bat not found at $gradlew" }

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

function Invoke-AdbText {
    param([string[]]$Arguments)
    return (Invoke-Adb $Arguments 2>&1 | Out-String).Trim()
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

function Get-AvdList {
    $list = & $emulator -list-avds 2>$null
    $fromEmulator = @()
    if ($LASTEXITCODE -eq 0 -and $null -ne $list) {
        $fromEmulator = @($list | ForEach-Object { "$_".Trim() } | Where-Object { $_ })
    }
    $fromProfile = @()
    $profileAvdHome = Join-Path $env:USERPROFILE ".android\avd"
    if (Test-Path $profileAvdHome) {
        $fromProfile = @(Get-ChildItem -Path $profileAvdHome -Filter "*.ini" -ErrorAction SilentlyContinue | ForEach-Object { $_.BaseName })
    }
    return @($fromEmulator + $fromProfile | Where-Object { $_ } | Select-Object -Unique)
}

function Ensure-Avd {
    param([string]$AvdName)
    $avds = Get-AvdList
    if ($AvdName -in $avds) {
        return "reused"
    }
    $package = "system-images;android-$Api;google_apis;x86_64"
    try {
    & (Join-Path $PSScriptRoot "create-avd.ps1") -AvdName $AvdName -Package $package | Out-Host
        $createExit = $LASTEXITCODE
    } finally {
        Restore-AndroidEnvironment
    }
    if ($createExit -ne 0) {
        throw "create-avd.ps1 failed for $AvdName"
    }
    return "created"
}

function Wait-RuntimeReady {
    & (Join-Path $PSScriptRoot "wait-adb.ps1") -Serial $Serial -TimeoutSeconds 1200 -SdkRoot $SdkRoot
    if ($LASTEXITCODE -ne 0) {
        throw "wait-adb.ps1 failed for $Serial"
    }

    $deadline = (Get-Date).AddMinutes(3)
    do {
        $boot = Invoke-AdbText @("-s", $Serial, "shell", "getprop", "sys.boot_completed")
        $pm = Invoke-AdbText @("-s", $Serial, "shell", "pm", "path", "android")
        $settings = Invoke-AdbText @("-s", $Serial, "shell", "settings", "get", "global", "device_provisioned")
        if ($boot -eq "1" -and $pm -match "^package:" -and $LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($settings)) {
            return
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    throw "Device runtime not ready after boot. boot=$boot pm=$pm settings=$settings"
}

function Set-StableMeasurementEnvironment {
    Invoke-Adb @("-s", $Serial, "shell", "svc", "power", "stayon", "true") | Out-Null
    Invoke-Adb @("-s", $Serial, "shell", "dumpsys", "battery", "set", "level", "80") | Out-Null
    Invoke-Adb @("-s", $Serial, "shell", "dumpsys", "battery", "set", "status", "2") | Out-Null
    Invoke-Adb @("-s", $Serial, "shell", "settings", "put", "global", "window_animation_scale", "1") | Out-Null
    Invoke-Adb @("-s", $Serial, "shell", "settings", "put", "global", "transition_animation_scale", "1") | Out-Null
    Invoke-Adb @("-s", $Serial, "shell", "settings", "put", "global", "animator_duration_scale", "1") | Out-Null
}

function Save-AdbEvidence {
    param([string]$DeviceOutput)

    $files = [ordered]@{
        "device-info.txt" = @("-s", $Serial, "shell", "getprop")
        "dpm-list-owners.txt" = @("-s", $Serial, "shell", "dpm", "list-owners")
        "meminfo.txt" = @("-s", $Serial, "shell", "dumpsys", "meminfo", "com.example.mdmapplication")
        "cpuinfo.txt" = @("-s", $Serial, "shell", "dumpsys", "cpuinfo")
        "gfxinfo.txt" = @("-s", $Serial, "shell", "dumpsys", "gfxinfo", "com.example.mdmapplication")
    }

    foreach ($entry in $files.GetEnumerator()) {
        $text = Invoke-AdbText $entry.Value
        Set-Content -Encoding UTF8 -Path (Join-Path $DeviceOutput $entry.Key) -Value $text
    }

    $log = Invoke-AdbText @("-s", $Serial, "logcat", "-d", "-v", "time")
    $filtered = @($log -split '\r?\n' | Where-Object {
        $_ -match "(?i)(FATAL EXCEPTION|ANR|MDM_CONFIG_FETCH|MDM_POLICY_APPLY|MDM_CMD_HANDLE|DevicePolicyHelper|LauncherViewModel)"
    })
    Set-Content -Encoding UTF8 -Path (Join-Path $DeviceOutput "logcat-filtered.txt") -Value ($filtered -join "`n")
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
    param([object]$Benchmark, [string]$MetricName, [string]$FieldName)
    if ($null -eq $Benchmark.metrics) { return "" }
    $metric = $Benchmark.metrics.PSObject.Properties[$MetricName]
    if ($null -eq $metric) { return "" }
    $field = $metric.Value.PSObject.Properties[$FieldName]
    if ($null -eq $field) { return "" }
    return $field.Value
}

function Get-Iterations {
    param([object]$Benchmark)
    if ($null -eq $Benchmark.metrics) { return "" }
    foreach ($prop in $Benchmark.metrics.PSObject.Properties) {
        $runs = $prop.Value.PSObject.Properties["runs"]
        if ($null -ne $runs) { return @($runs.Value).Count }
    }
    return ""
}

function Read-MacroRows {
    param([string]$DeviceOutput, [string]$AvdName)
    $rows = @()
    $jsonFiles = @(Get-ChildItem -Path $DeviceOutput -Recurse -File -Filter "*.json" -ErrorAction SilentlyContinue)
    foreach ($file in $jsonFiles) {
        try {
            $json = Get-Content -Raw -Path $file.FullName | ConvertFrom-Json
        } catch {
            continue
        }
        if ($null -eq $json.benchmarks) { continue }
        foreach ($benchmark in @($json.benchmarks)) {
            $name = if ($benchmark.name) { $benchmark.name } elseif ($benchmark.methodName) { $benchmark.methodName } else { $file.BaseName }
            $startupMode = if ($name -match "(?i)cold") { "cold" } elseif ($name -match "(?i)warm") { "warm" } elseif ($name -match "(?i)hot") { "hot" } else { "" }
            $rows += [pscustomobject]@{
                avdName = $AvdName
                testName = $name
                startupMode = $startupMode
                timeToInitialDisplayMsMedian = Get-Metric -Benchmark $benchmark -MetricName "timeToInitialDisplayMs" -FieldName "median"
                frameDurationCpuMsMedian = $(if (Get-Metric -Benchmark $benchmark -MetricName "frameDurationCpuMs" -FieldName "median") { Get-Metric -Benchmark $benchmark -MetricName "frameDurationCpuMs" -FieldName "median" } else { Get-Metric -Benchmark $benchmark -MetricName "gfxFrameTime50thPercentileMs" -FieldName "median" })
                frameOverrunMsMedian = Get-Metric -Benchmark $benchmark -MetricName "frameOverrunMs" -FieldName "median"
                iterations = Get-Iterations -Benchmark $benchmark
                resultFile = $file.FullName
            }
        }
    }
    return $rows
}

function Read-ResourceRow {
    param([string]$DeviceOutput, [string]$AvdName)
    $meminfo = Get-Content -Raw -Path (Join-Path $DeviceOutput "meminfo.txt") -ErrorAction SilentlyContinue
    $cpuinfo = Get-Content -Raw -Path (Join-Path $DeviceOutput "cpuinfo.txt") -ErrorAction SilentlyContinue
    $gfxinfo = Get-Content -Raw -Path (Join-Path $DeviceOutput "gfxinfo.txt") -ErrorAction SilentlyContinue
    $log = Get-Content -Raw -Path (Join-Path $DeviceOutput "logcat-filtered.txt") -ErrorAction SilentlyContinue

    $pss = ""
    if ($meminfo -match "(?m)^\s*TOTAL\s+(\d+)") { $pss = $Matches[1] }
    $cpuLine = ""
    $cpuMatch = [regex]::Match($cpuinfo, "(?mi)^.*com\.example\.mdmapplication.*$")
    if ($cpuMatch.Success) { $cpuLine = $cpuMatch.Value.Trim() }
    $janky = ""
    if ($gfxinfo -match "(?i)Janky frames:\s+(\d+)") { $janky = $Matches[1] }
    $crashCount = @($log -split '\r?\n' | Where-Object { $_ -match "(?i)FATAL EXCEPTION" }).Count
    $anrCount = @($log -split '\r?\n' | Where-Object { $_ -match "(?i)\bANR\b" }).Count

    return [pscustomobject]@{
        avdName = $AvdName
        pssKb = $pss
        cpuProcessSnapshot = $cpuLine
        jankyFrames = $janky
        crashCount = $crashCount
        anrCount = $anrCount
    }
}

function Write-SuiteSummary {
    param(
        [object[]]$DeviceRows,
        [object[]]$MacroRows,
        [object[]]$ResourceRows,
        [object[]]$Notes
    )

    $path = Join-Path $outputRoot "SUMMARY.md"
    $lines = @()
    $lines += "# MDM Performance Benchmark Suite"
    $lines += ""
    $lines += "Generated: $(Get-Date -Format s)"
    $lines += ""
    $lines += "## Table 1: AVD/device info"
    $lines += ""
    $lines += "| avdName | api | serial | model | sdk | device owner status | backend reachable |"
    $lines += "| --- | ---: | --- | --- | --- | --- | --- |"
    foreach ($row in $DeviceRows) {
        $lines += "| $($row.avdName) | $($row.api) | $($row.serial) | $($row.model) | $($row.sdk) | $($row.deviceOwnerStatus) | $($row.backendReachable) |"
    }
    if ($DeviceRows.Count -eq 0) {
        $lines += "| N/A |  |  |  |  |  |  |"
    }
    $lines += ""
    $lines += "## Table 2: Macrobenchmark startup/frame"
    $lines += ""
    $lines += "| avdName | testName | startupMode | timeToInitialDisplayMs median | frameDurationCpuMs/gfxFrameTime50thMs median | frameOverrunMs median | iterations | result file |"
    $lines += "| --- | --- | --- | ---: | ---: | ---: | ---: | --- |"
    foreach ($row in $MacroRows) {
        $lines += "| $($row.avdName) | $($row.testName) | $($row.startupMode) | $($row.timeToInitialDisplayMsMedian) | $($row.frameDurationCpuMsMedian) | $($row.frameOverrunMsMedian) | $($row.iterations) | ``$($row.resultFile)`` |"
    }
    if ($MacroRows.Count -eq 0) {
        $lines += "| N/A | N/A |  |  |  |  |  | No parsed Macrobenchmark JSON |"
    }
    $lines += ""
    $lines += "## Table 3: ADB resource evidence"
    $lines += ""
    $lines += "| avdName | RAM/PSS after startup KB | CPU process snapshot | gfxinfo janky frames | crash count | ANR count |"
    $lines += "| --- | ---: | --- | ---: | ---: | ---: |"
    foreach ($row in $ResourceRows) {
        $cpu = ($row.cpuProcessSnapshot -replace "\|", "/")
        $lines += "| $($row.avdName) | $($row.pssKb) | $cpu | $($row.jankyFrames) | $($row.crashCount) | $($row.anrCount) |"
    }
    if ($ResourceRows.Count -eq 0) {
        $lines += "| N/A |  |  |  |  |  |"
    }
    $lines += ""
    $lines += "## Table 4: Stability notes"
    $lines += ""
    $lines += "| avdName | command/policy-state | manual-only items | blocker |"
    $lines += "| --- | --- | --- | --- |"
    foreach ($note in $Notes) {
        $lines += "| $($note.avdName) | $($note.commandPolicyState) | $($note.manualOnly) | $($note.blocker) |"
    }
    if ($Notes.Count -eq 0) {
        $lines += "| N/A | Not run | Device Owner/kiosk manual runtime proof remains separate | No suite rows |"
    }
    Set-Content -Encoding UTF8 -Path $path -Value ($lines -join "`n")
    return $path
}

if (-not $SkipBuild) {
    Invoke-Gradle @("assembleDebug")
}

try {
    & (Join-Path $PSScriptRoot "check-android-env.ps1") -SdkRoot $SdkRoot
    $checkExit = $LASTEXITCODE
} finally {
    Restore-AndroidEnvironment
}
if ($checkExit -ne 0) {
    throw "check-android-env.ps1 failed"
}

$deviceRows = @()
$macroRows = @()
$resourceRows = @()
$notes = @()

for ($offset = 0; $offset -lt $DeviceCount; $offset++) {
    $index = $StartIndex + $offset
    $avdName = "MDM_MANUAL_INSTALL_{0:D2}_API{1}" -f $index, $Api
    $deviceOutput = Join-Path $outputRoot $avdName
    New-Item -ItemType Directory -Force -Path $deviceOutput | Out-Null

    $blocker = ""
    $avdStatus = ""
    try {
        $avdStatus = Ensure-Avd -AvdName $avdName

        $provisionArgs = @{
            AvdName = $avdName
            Serial = $Serial
            SdkRoot = $SdkRoot
            SkipBuild = $true
        }
        if ($WipeData) {
            $provisionArgs.WipeData = $true
        }
        try {
            & (Join-Path $PSScriptRoot "provision-emulator-device-owner.ps1") @provisionArgs
            $provisionExit = $LASTEXITCODE
        } finally {
            Restore-AndroidEnvironment
        }
        if ($provisionExit -ne 0) {
            throw "provision-emulator-device-owner.ps1 failed exit=$provisionExit"
        }

        Wait-RuntimeReady
        Set-StableMeasurementEnvironment
        Invoke-Adb @("-s", $Serial, "logcat", "-c") | Out-Null

        if (-not (Test-Path (Join-Path $repoRoot "benchmark\build.gradle.kts"))) {
            throw "BLOCKED: benchmark module does not exist"
        }

        try {
            & (Join-Path $PSScriptRoot "run-macrobenchmark.ps1") -Serial $Serial -OutputDir $deviceOutput -SkipBuild -Iterations $Iterations -SdkRoot $SdkRoot
            $macroExit = $LASTEXITCODE
        } finally {
            Restore-AndroidEnvironment
        }
        if ($macroExit -ne 0) {
            throw "run-macrobenchmark.ps1 failed exit=$macroExit"
        }

        try {
            & (Join-Path $PSScriptRoot "benchmark-launchers.ps1") -Serial $Serial -Iterations $Iterations -OutputDir $deviceOutput -SdkRoot $SdkRoot
            $adbBenchmarkExit = $LASTEXITCODE
        } finally {
            Restore-AndroidEnvironment
        }
        if ($adbBenchmarkExit -ne 0) {
            throw "benchmark-launchers.ps1 failed exit=$adbBenchmarkExit"
        }

        Save-AdbEvidence -DeviceOutput $deviceOutput
    } catch {
        $blocker = $_.Exception.Message
        Set-Content -Encoding UTF8 -Path (Join-Path $deviceOutput "BLOCKED.txt") -Value $blocker
    }

    $model = Invoke-AdbText @("-s", $Serial, "shell", "getprop", "ro.product.model")
    $sdk = Invoke-AdbText @("-s", $Serial, "shell", "getprop", "ro.build.version.sdk")
    $owners = Invoke-AdbText @("-s", $Serial, "shell", "dpm", "list-owners")
    $ownerStatus = if ($owners -match "com\.example\.mdmapplication") { "MDM Device Owner present" } elseif ($owners) { ($owners -replace '\r?\n', " / ") } else { "unknown" }
    $backendReachable = "not checked"

    $deviceRows += [pscustomobject]@{
        avdName = "$avdName ($avdStatus)"
        api = $Api
        serial = $Serial
        model = $model
        sdk = $sdk
        deviceOwnerStatus = $ownerStatus
        backendReachable = $backendReachable
    }
    $macroRows += @(Read-MacroRows -DeviceOutput $deviceOutput -AvdName $avdName)
    if (Test-Path (Join-Path $deviceOutput "meminfo.txt")) {
        $resourceRows += Read-ResourceRow -DeviceOutput $deviceOutput -AvdName $avdName
    }
    $notes += [pscustomobject]@{
        avdName = $avdName
        commandPolicyState = "Not exercised by benchmark suite"
        manualOnly = "Device Owner/kiosk acceptance still needs runtime-specific proof"
        blocker = $(if ($blocker) { $blocker } else { "" })
    }
}

$summary = Write-SuiteSummary -DeviceRows $deviceRows -MacroRows $macroRows -ResourceRows $resourceRows -Notes $notes
Write-Host "Performance benchmark suite summary: $summary"
