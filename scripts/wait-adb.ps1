[CmdletBinding()]
param(
    [string]$Serial = "emulator-5554",
    [int]$TimeoutSeconds = 180,
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

if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
    exit 1
}

function Invoke-Adb {
    param([string[]]$Arguments)
    & $adb @Arguments
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
Invoke-Adb @("start-server") | Out-Null

while ((Get-Date) -lt $deadline) {
    $state = (Invoke-Adb @("-s", $Serial, "get-state") 2>$null | Out-String).Trim()
    if ($state -eq "device") {
        $bootCompleted = (Invoke-Adb @("-s", $Serial, "shell", "getprop", "sys.boot_completed") 2>$null | Out-String).Trim()
        if ($bootCompleted -eq "1") {
            Write-Host "ADB_READY serial=$Serial"
            exit 0
        }
    }

    Start-Sleep -Seconds 2
}

Write-Error "Timed out waiting for adb device '$Serial' to finish booting within $TimeoutSeconds seconds."
exit 1
