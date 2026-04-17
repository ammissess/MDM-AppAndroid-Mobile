[CmdletBinding()]
param(
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
$emulator = Join-Path $SdkRoot "emulator\emulator.exe"
$avdManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\avdmanager.bat"
$missing = @()

if (-not (Test-Path $adb)) { $missing += $adb }
if (-not (Test-Path $emulator)) { $missing += $emulator }

Write-Host "SDK_ROOT=$SdkRoot"
Write-Host "HOME=$($env:HOME)"
Write-Host "USERPROFILE=$($env:USERPROFILE)"
Write-Host "ANDROID_SDK_HOME=$($env:ANDROID_SDK_HOME)"
Write-Host "ANDROID_USER_HOME=$($env:ANDROID_USER_HOME)"
Write-Host "ANDROID_AVD_HOME=$($env:ANDROID_AVD_HOME)"
Write-Host "ADB=$adb"
Write-Host "EMULATOR=$emulator"
Write-Host "AVDMANAGER=$avdManager"

if (Test-Path $emulator) {
    $avds = & $emulator -list-avds 2>$null
    if ($LASTEXITCODE -eq 0 -and $avds) {
        Write-Host "AVDS:"
        $avds | ForEach-Object { Write-Host " - $_" }
    } else {
        Write-Host "AVDS: none listed"
    }
}

if ($missing.Count -gt 0) {
    Write-Error ("Missing Android SDK tools: " + ($missing -join ", "))
    exit 1
}

exit 0
