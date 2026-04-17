[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AvdName,
    [string]$Serial = "emulator-5554",
    [int]$TimeoutSeconds = 240,
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

if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
    exit 1
}
if (-not (Test-Path $emulator)) {
    Write-Error "emulator not found at $emulator"
    exit 1
}

$state = (& $adb -s $Serial get-state 2>$null | Out-String).Trim()
$bootCompleted = (& $adb -s $Serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
if ($state -eq "device" -and $bootCompleted -eq "1") {
    Write-Host "EMULATOR_READY serial=$Serial already booted"
    exit 0
}

$stdoutLog = Join-Path $repoRoot "emulator-ticket9.out.log"
$stderrLog = Join-Path $repoRoot "emulator-ticket9.err.log"

Start-Process -FilePath $emulator `
    -ArgumentList @("-avd", $AvdName, "-netdelay", "none", "-netspeed", "full") `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog | Out-Null

& (Join-Path $PSScriptRoot "wait-adb.ps1") -Serial $Serial -TimeoutSeconds $TimeoutSeconds -SdkRoot $SdkRoot
exit $LASTEXITCODE
