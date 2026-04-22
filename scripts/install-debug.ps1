[CmdletBinding()]
param(
    [string]$Serial = "emulator-5554",
    [string]$ApkPath = "",
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Users\ADMIN\AppData\Local\Android\Sdk" })
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
}
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
if (-not (Test-Path $ApkPath)) {
    Write-Host "APK not found at $ApkPath. Running assembleDebug..."
    Push-Location $repoRoot
    try {
        & .\gradlew.bat assembleDebug
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
    finally {
        Pop-Location
    }
    if (-not (Test-Path $ApkPath)) {
        Write-Error "APK not found at $ApkPath after assembleDebug"
        exit 1
    }
}

& $adb -s $Serial install -r $ApkPath
exit $LASTEXITCODE
