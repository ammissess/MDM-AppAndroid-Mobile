[CmdletBinding()]
param(
    [string]$Serial = "emulator-5554",
    [switch]$RunConnectedTests,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [int]$EvidenceWaitSeconds = 12,
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Users\ADMIN\AppData\Local\Android\Sdk" })
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$defaultAvdHome = Join-Path $repoRoot ".android-home\avd"
$env:HOME = $repoRoot
$env:USERPROFILE = $repoRoot
$env:ANDROID_USER_HOME = if ($env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME } else { Join-Path $repoRoot ".android-home" }
$env:ANDROID_SDK_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { if (Test-Path $defaultAvdHome) { $defaultAvdHome } else { Join-Path $repoRoot ".android-avd" } }
$adb = Join-Path $SdkRoot "platform-tools\adb.exe"
$gradle = Join-Path $repoRoot "gradlew.bat"
$appId = "com.example.mdmapplication"
$activity = "com.example.mdmapplication.ui.launcher.LauncherActivity"
$evidenceFile = Join-Path $repoRoot "ticket9-android-harness.log"
$env:GRADLE_USER_HOME = Join-Path $repoRoot ".gradle-home"

if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
    exit 1
}
if (-not (Test-Path $gradle)) {
    Write-Error "gradlew.bat not found at $gradle"
    exit 1
}

function Invoke-Adb {
    param([string[]]$Arguments)
    & $adb @Arguments
}

if (-not $SkipBuild) {
    Push-Location $repoRoot
    try {
        Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue
        & $gradle assembleDebug
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

        & $gradle testDebugUnitTest
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

        if ($RunConnectedTests) {
            & $gradle connectedDebugAndroidTest
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        }
    } finally {
        Pop-Location
    }
}

$env:ANDROID_SDK_HOME = $env:ANDROID_USER_HOME
& (Join-Path $PSScriptRoot "wait-adb.ps1") -Serial $Serial -SdkRoot $SdkRoot
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not $SkipInstall) {
    & (Join-Path $PSScriptRoot "install-debug.ps1") -Serial $Serial -SdkRoot $SdkRoot
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Invoke-Adb @("-s", $Serial, "logcat", "-c") | Out-Null
Invoke-Adb @("-s", $Serial, "shell", "am", "start", "-n", "$appId/$activity") | Out-Null
Start-Sleep -Seconds $EvidenceWaitSeconds

$logs = Invoke-Adb @(
    "-s", $Serial,
    "logcat", "-d",
    "LauncherActivity:I",
    "LauncherViewModel:I",
    "FirebaseWakeupMsgSvc:I",
    "DeviceRuntimeIdentity:I",
    "*:S"
)

$patterns = @(
    "runtime wake-up accepted",
    "refresh start",
    "register result",
    "fetch config success",
    "fcm token sync result",
    "onNewToken received",
    "getDeviceCode source"
)

$evidence = New-Object System.Collections.Generic.List[string]
foreach ($line in $logs) {
    foreach ($pattern in $patterns) {
        if ($line -like "*$pattern*") {
            $evidence.Add($line)
            break
        }
    }
}

if ($evidence.Count -eq 0) {
    Write-Warning "No targeted Android evidence lines were captured. Check backend availability and app state."
} else {
    Write-Host "ANDROID_EVIDENCE:"
    $evidence | Select-Object -Last 20 | ForEach-Object { Write-Host $_ }
}

@(
    "serial=$Serial"
    "connectedTests=$($RunConnectedTests.IsPresent)"
    "evidenceCount=$($evidence.Count)"
    $evidence | Select-Object -Last 20
) | Set-Content -Path $evidenceFile

Write-Host "EVIDENCE_FILE=$evidenceFile"
Write-Host "HARNESS_COMPLETE serial=$Serial connectedTests=$($RunConnectedTests.IsPresent)"
exit 0
