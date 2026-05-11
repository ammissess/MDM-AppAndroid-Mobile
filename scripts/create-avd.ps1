param(
    [string]$AvdName = "MDM_MANUAL_INSTALL_01_API36",
    [string]$Package = "system-images;android-36;google_apis;x86_64",
    [string]$Device = "medium_phone"
)

$ErrorActionPreference = "Stop"

$sdkRoot = $env:ANDROID_SDK_ROOT
if (-not $sdkRoot) {
    $sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
}

$avdManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\avdmanager.bat"
$sdkManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"

Write-Host ""
Write-Host "== SDK CHECK =="

if (-not (Test-Path $avdManager)) {
    throw "avdmanager not found: $avdManager"
}

if (-not (Test-Path $sdkManager)) {
    throw "sdkmanager not found: $sdkManager"
}

Write-Host ""
Write-Host "== INSTALL SYSTEM IMAGE =="

& $sdkManager $Package

Write-Host ""
Write-Host "== DELETE OLD AVD IF EXISTS =="

try {
    & $avdManager delete avd -n $AvdName
} catch {
    Write-Host "No previous AVD."
}

Write-Host ""
Write-Host "== CREATE AVD =="

"no" | & $avdManager create avd `
    -n $AvdName `
    -k $Package `
    -d $Device

Write-Host ""
Write-Host "== DONE =="

Write-Host "Created AVD: $AvdName"