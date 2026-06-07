[CmdletBinding()]
param(
    [string]$Serial = "emulator-5554",
    [int]$Iterations = 10,
    [string]$OutputDir = "benchmark-results",
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Users\ADMIN\AppData\Local\Android\Sdk" })
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $SdkRoot "platform-tools\adb.exe"
$outputRoot = if ([System.IO.Path]::IsPathRooted($OutputDir)) { $OutputDir } else { Join-Path $repoRoot $OutputDir }

if (-not (Test-Path $adb)) {
    throw "adb not found at $adb"
}
if ($Iterations -lt 1) {
    throw "Iterations must be >= 1"
}

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

function Invoke-Adb {
    param([string[]]$Arguments)
    & $adb @Arguments
}

function Test-DeviceReady {
    $state = (Invoke-Adb @("-s", $Serial, "get-state") 2>$null | Out-String).Trim()
    return $state -eq "device"
}

function Get-StartMetric {
    param(
        [string]$Text,
        [string]$Name
    )
    $match = [regex]::Match($Text, "(?m)^\s*$([regex]::Escape($Name)):\s*(\d+)")
    if ($match.Success) {
        return [int]$match.Groups[1].Value
    }
    return $null
}

function Get-Median {
    param([object[]]$Values)
    $numbers = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ } | Sort-Object)
    if ($numbers.Count -eq 0) {
        return ""
    }
    $mid = [int][Math]::Floor($numbers.Count / 2)
    if ($numbers.Count % 2 -eq 1) {
        return [Math]::Round($numbers[$mid], 2)
    }
    return [Math]::Round(($numbers[$mid - 1] + $numbers[$mid]) / 2, 2)
}

function Resolve-VerifiedComponent {
    param([string[]]$Candidates)

    foreach ($component in $Candidates) {
        $result = (Invoke-Adb @("-s", $Serial, "shell", "cmd", "package", "resolve-activity", "--brief", "-n", $component) 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $result -match [regex]::Escape(($component -split "/")[0])) {
            return $component
        }
    }
    return ""
}

function Resolve-HomeComponent {
    $result = (Invoke-Adb @("-s", $Serial, "shell", "cmd", "package", "resolve-activity", "--brief", "android.intent.action.MAIN", "-c", "android.intent.category.HOME") 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($result)) {
        return ""
    }
    $lines = @($result -split '\r?\n' | ForEach-Object { $_.Trim() } | Where-Object { $_ -match "/" })
    if ($lines.Count -gt 0) {
        return $lines[$lines.Count - 1]
    }
    return ""
}

function Measure-Component {
    param(
        [string]$TargetName,
        [string]$Component
    )

    $rows = @()
    for ($i = 1; $i -le $Iterations; $i++) {
        Invoke-Adb @("-s", $Serial, "shell", "input", "keyevent", "KEYCODE_HOME") | Out-Null
        Start-Sleep -Milliseconds 500
        $output = (Invoke-Adb @("-s", $Serial, "shell", "am", "start", "-W", "-n", $Component) 2>&1 | Out-String).Trim()
        $exit = $LASTEXITCODE
        $rows += [pscustomobject]@{
            target = $TargetName
            component = $Component
            iteration = $i
            thisTimeMs = Get-StartMetric -Text $output -Name "ThisTime"
            totalTimeMs = Get-StartMetric -Text $output -Name "TotalTime"
            waitTimeMs = Get-StartMetric -Text $output -Name "WaitTime"
            exitCode = $exit
            raw = ($output -replace '\r?\n', " | ")
        }
    }
    return $rows
}

if (-not (Test-DeviceReady)) {
    throw "ADB device '$Serial' is not ready"
}

$targets = @(
    [pscustomobject]@{ Name = "MDM MainActivity"; Component = "com.example.mdmapplication/.MainActivity" },
    [pscustomobject]@{ Name = "MDM LauncherActivity"; Component = "com.example.mdmapplication/.ui.launcher.LauncherActivity" }
)

$nexus = Resolve-VerifiedComponent -Candidates @(
    "com.google.android.apps.nexuslauncher/.NexusLauncherActivity",
    "com.google.android.apps.nexuslauncher/.Launcher"
)
if (-not [string]::IsNullOrWhiteSpace($nexus)) {
    $targets += [pscustomobject]@{ Name = "Nexus Launcher"; Component = $nexus }
} else {
    $home = Resolve-HomeComponent
    if (-not [string]::IsNullOrWhiteSpace($home) -and $home -notmatch "^com\.example\.mdmapplication/") {
        $targets += [pscustomobject]@{ Name = "Resolved HOME Launcher"; Component = $home }
    }
}

$allRows = @()
foreach ($target in $targets) {
    $allRows += Measure-Component -TargetName $target.Name -Component $target.Component
}

$csvPath = Join-Path $outputRoot "adb-launcher-startup.csv"
$mdPath = Join-Path $outputRoot "adb-launcher-startup.md"
$allRows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $csvPath

$summaryRows = @()
foreach ($group in ($allRows | Group-Object target)) {
    $ok = @($group.Group | Where-Object { $_.exitCode -eq 0 })
    $summaryRows += [pscustomobject]@{
        Target = $group.Name
        Iterations = $group.Count
        Success = $ok.Count
        TotalTimeMedianMs = Get-Median ($ok | ForEach-Object { $_.totalTimeMs })
        WaitTimeMedianMs = Get-Median ($ok | ForEach-Object { $_.waitTimeMs })
        MinTotalTimeMs = (($ok | ForEach-Object { $_.totalTimeMs } | Where-Object { $null -ne $_ } | Measure-Object -Minimum).Minimum)
        MaxTotalTimeMs = (($ok | ForEach-Object { $_.totalTimeMs } | Where-Object { $null -ne $_ } | Measure-Object -Maximum).Maximum)
    }
}

$lines = @()
$lines += "# ADB Launcher Startup Benchmark"
$lines += ""
$lines += "| Target | Iterations | Success | TotalTime median ms | WaitTime median ms | Min total ms | Max total ms |"
$lines += "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"
foreach ($row in $summaryRows) {
    $lines += "| $($row.Target) | $($row.Iterations) | $($row.Success) | $($row.TotalTimeMedianMs) | $($row.WaitTimeMedianMs) | $($row.MinTotalTimeMs) | $($row.MaxTotalTimeMs) |"
}
$lines += ""
$lines += "Raw CSV: ``adb-launcher-startup.csv``"
Set-Content -Encoding UTF8 -Path $mdPath -Value ($lines -join "`n")

Write-Host "ADB launcher benchmark written:"
Write-Host "  $csvPath"
Write-Host "  $mdPath"
