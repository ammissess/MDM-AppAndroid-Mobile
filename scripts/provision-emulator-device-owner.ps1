[CmdletBinding()]
param(
    [string]$AvdName = "",
    [string]$Serial = "emulator-5554",
    [switch]$WipeData,
    [switch]$RebootAfterSetup,
    [switch]$SkipBuild,
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Users\ADMIN\AppData\Local\Android\Sdk" })
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $SdkRoot "platform-tools\adb.exe"
$emulator = Join-Path $SdkRoot "emulator\emulator.exe"
$gradlew = Join-Path $repoRoot "gradlew.bat"
$apkPath = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
$ownerComponent = "com.example.mdmapplication/.device.MyDeviceAdminReceiver"
$ownerPackage = "com.example.mdmapplication"
$ownerReceiver = "MyDeviceAdminReceiver"
$launcherComponent = "com.example.mdmapplication/.ui.launcher.LauncherActivity"
$logRoot = if ($env:TEMP) { $env:TEMP } else { [System.IO.Path]::GetTempPath() }
$safeSerialForLog = $Serial -replace "[^A-Za-z0-9_.-]", "_"
$emulatorStdoutLog = Join-Path $logRoot "mdm-emulator-provisioning-$safeSerialForLog.out.log"
$emulatorStderrLog = Join-Path $logRoot "mdm-emulator-provisioning-$safeSerialForLog.err.log"
$emulatorAvdHome = $null
$androidStudioAvdHome = "C:\Users\ADMIN\.android\avd"
$androidEnvNames = @(
    "HOME",
    "USERPROFILE",
    "ANDROID_USER_HOME",
    "ANDROID_AVD_HOME",
    "ANDROID_SDK_HOME",
    "ANDROID_SDK_ROOT",
    "ANDROID_HOME"
)
$startupAndroidEnv = @{}
foreach ($name in $androidEnvNames) {
    $startupAndroidEnv[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}
$startupWindowsUserProfile = $startupAndroidEnv["USERPROFILE"]
if ([string]::IsNullOrWhiteSpace($startupWindowsUserProfile) -or -not (Test-Path $startupWindowsUserProfile)) {
    $startupWindowsUserProfile = [Environment]::GetFolderPath("UserProfile")
}

$result = [ordered]@{
    AvdBoot = "not requested"
    Adb = "not checked"
    ApkInstall = "not run"
    DeviceOwner = "not run"
    AppStart = "not run"
    Reboot = "not requested"
}

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "== $Message =="
}

function Write-ProvisioningSummary {
    Write-Host ""
    Write-Host "== Provisioning result =="
    Write-Host "AVD boot result: $($result.AvdBoot)"
    Write-Host "adb status: $($result.Adb)"
    Write-Host "APK install result: $($result.ApkInstall)"
    Write-Host "Device Owner result: $($result.DeviceOwner)"
    Write-Host "app start result: $($result.AppStart)"
    Write-Host "reboot result: $($result.Reboot)"
    Write-Host "next manual step: open web dashboard, link profile, send refresh_config/sync_config if needed."
}

function Stop-WithMessage {
    param(
        [string]$Message,
        [int]$Code = 1
    )
    Write-Host ""
    Write-Host $Message -ForegroundColor Yellow
    Write-ProvisioningSummary
    exit $Code
}

function Invoke-Adb {
    param([string[]]$Arguments)
    & $adb @Arguments
}

function Invoke-AdbCaptured {
    param(
        [string[]]$Arguments,
        [int]$TimeoutSeconds = 30
    )

    Restore-AndroidEnvironment
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        $process = Start-Process -FilePath $adb `
            -ArgumentList $Arguments `
            -RedirectStandardOutput $stdoutFile `
            -RedirectStandardError $stderrFile `
            -NoNewWindow `
            -PassThru

        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try {
                $process.Kill()
            } catch {
            }

            return [pscustomobject]@{
                ExitCode = 124
                Stdout = ""
                Stderr = "adb command timed out after $TimeoutSeconds seconds: $($Arguments -join ' ')"
            }
        }

        $process.WaitForExit()
        $stdout = Get-Content -LiteralPath $stdoutFile -Raw -ErrorAction SilentlyContinue
        $stderr = Get-Content -LiteralPath $stderrFile -Raw -ErrorAction SilentlyContinue
        if ($null -eq $stdout) {
            $stdout = ""
        }
        if ($null -eq $stderr) {
            $stderr = ""
        }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Stdout = ([string]$stdout).Trim()
            Stderr = ([string]$stderr).Trim()
        }
    } catch {
        return [pscustomobject]@{
            ExitCode = 1
            Stdout = ""
            Stderr = $_.Exception.Message
        }
    } finally {
        Remove-Item -LiteralPath $stdoutFile, $stderrFile -ErrorAction SilentlyContinue
    }
}

function Format-CapturedValue {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return "<empty>"
    }
    return $Value
}

function Require-ExitCode {
    param([string]$Action)
    if ($LASTEXITCODE -ne 0) {
        throw "$Action failed with exit code $LASTEXITCODE"
    }
}

function Get-FirstRootCause {
    param([string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return "<no output>"
    }

    $lines = @($Text -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $whatWentWrongIndex = [Array]::IndexOf($lines, "* What went wrong:")
    if ($whatWentWrongIndex -ge 0) {
        for ($i = $whatWentWrongIndex + 1; $i -lt $lines.Count; $i++) {
            if ($lines[$i].StartsWith("* ")) {
                break
            }
            if ($lines[$i]) {
                return $lines[$i]
            }
        }
    }

    $causedBy = $lines | Where-Object { $_ -match "^(Caused by:|>\s+)" } | Select-Object -First 1
    if ($causedBy) {
        return $causedBy
    }

    return ($lines | Select-Object -First 1)
}

function Set-ProcessEnvironmentVariable {
    param(
        [string]$Name,
        [AllowNull()][string]$Value
    )

    if ([string]::IsNullOrEmpty($Value)) {
        [Environment]::SetEnvironmentVariable($Name, $null, "Process")
        Remove-Item -LiteralPath "Env:\$Name" -ErrorAction SilentlyContinue
        return
    }

    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Restore-AndroidEnvironment {
    foreach ($name in $androidEnvNames) {
        Set-ProcessEnvironmentVariable -Name $name -Value $startupAndroidEnv[$name]
    }
}

function Set-GradleAndroidEnvironment {
    Restore-AndroidEnvironment

    if (-not [string]::IsNullOrWhiteSpace($startupWindowsUserProfile) -and (Test-Path $startupWindowsUserProfile)) {
        Set-ProcessEnvironmentVariable -Name "USERPROFILE" -Value $startupWindowsUserProfile
    }

    Set-ProcessEnvironmentVariable -Name "ANDROID_SDK_ROOT" -Value $SdkRoot
    Set-ProcessEnvironmentVariable -Name "ANDROID_HOME" -Value $SdkRoot
    Set-ProcessEnvironmentVariable -Name "ANDROID_USER_HOME" -Value $null
    Set-ProcessEnvironmentVariable -Name "ANDROID_SDK_HOME" -Value $null
    Set-ProcessEnvironmentVariable -Name "ANDROID_AVD_HOME" -Value $null
}

function Test-IsRepoAvdHome {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $false
    }

    $normalized = [System.IO.Path]::GetFullPath($Path).TrimEnd("\")
    $repoAvdHomes = @(
        (Join-Path $repoRoot ".android-home\avd"),
        (Join-Path $repoRoot ".android-avd"),
        (Join-Path $repoRoot ".android\avd")
    ) | ForEach-Object { [System.IO.Path]::GetFullPath($_).TrimEnd("\") }

    return $normalized -in $repoAvdHomes
}

function Get-PreferredAndroidStudioAvdHome {
    if (Test-Path $androidStudioAvdHome) {
        return $androidStudioAvdHome
    }

    $profileAvdHome = Join-Path ([Environment]::GetFolderPath("UserProfile")) ".android\avd"
    if (Test-Path $profileAvdHome) {
        return $profileAvdHome
    }

    return ""
}

function Get-AvdHomeCandidates {
    $candidates = New-Object System.Collections.Generic.List[string]

    $preferredAvdHome = Get-PreferredAndroidStudioAvdHome
    if (-not [string]::IsNullOrWhiteSpace($preferredAvdHome)) {
        $candidates.Add($preferredAvdHome)
    }

    $startupAvdHome = $startupAndroidEnv["ANDROID_AVD_HOME"]
    if (
        -not [string]::IsNullOrWhiteSpace($startupAvdHome) -and
        -not (Test-IsRepoAvdHome $startupAvdHome)
    ) {
        $candidates.Add($startupAvdHome)
    }

    $startupUserProfile = $startupAndroidEnv["USERPROFILE"]
    if (-not [string]::IsNullOrWhiteSpace($startupUserProfile)) {
        $candidates.Add((Join-Path $startupUserProfile ".android\avd"))
    }

    $startupHome = $startupAndroidEnv["HOME"]
    if (-not [string]::IsNullOrWhiteSpace($startupHome)) {
        $candidates.Add((Join-Path $startupHome ".android\avd"))
    }

    return @($candidates | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_) -and -not (Test-IsRepoAvdHome $_)
    } | Select-Object -Unique)
}

function Invoke-EmulatorListAvds {
    param([string]$AvdHome = "")

    Restore-AndroidEnvironment
    $previousAvdHome = [Environment]::GetEnvironmentVariable("ANDROID_AVD_HOME", "Process")
    try {
        if (-not [string]::IsNullOrWhiteSpace($AvdHome)) {
            Set-ProcessEnvironmentVariable -Name "ANDROID_AVD_HOME" -Value $AvdHome
        }
        $avds = & $emulator -list-avds 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $avds) {
            return @()
        }
        return @($avds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() })
    } finally {
        Set-ProcessEnvironmentVariable -Name "ANDROID_AVD_HOME" -Value $previousAvdHome
        Restore-AndroidEnvironment
    }
}

function Resolve-EmulatorAvdHome {
    param([string]$RequiredAvdName)

    $allAvds = New-Object System.Collections.Generic.List[string]

    foreach ($candidate in Get-AvdHomeCandidates) {
        if (-not (Test-Path $candidate)) {
            continue
        }
        $candidateAvds = Invoke-EmulatorListAvds -AvdHome $candidate
        $candidateAvds | ForEach-Object { $allAvds.Add($_) }
        if ($RequiredAvdName -in $candidateAvds) {
            $script:emulatorAvdHome = $candidate
            return @($candidateAvds)
        }
    }

    if (-not (Test-IsRepoAvdHome $startupAndroidEnv["ANDROID_AVD_HOME"])) {
        $defaultAvds = Invoke-EmulatorListAvds
        $defaultAvds | ForEach-Object { $allAvds.Add($_) }
        if ($RequiredAvdName -in $defaultAvds) {
            $script:emulatorAvdHome = $startupAndroidEnv["ANDROID_AVD_HOME"]
            return $defaultAvds
        }
    }

    $script:emulatorAvdHome = $null
    return @($allAvds | Select-Object -Unique)
}

function Get-AdbTransportState {
    $devicesOutput = Invoke-Adb @("devices") 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $devicesOutput) {
        return ""
    }

    $escapedSerial = [regex]::Escape($Serial)
    foreach ($line in $devicesOutput) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^$escapedSerial\s+(\S+)") {
            return $Matches[1]
        }
    }
    return ""
}

function Ensure-DeviceNotOffline {
    $state = Get-AdbTransportState
    if ($state -ne "offline") {
        return $state
    }

    Write-Host "ADB shows $Serial as offline. Reconnecting offline transports..."
    Invoke-Adb @("reconnect", "offline") | Out-Host
    Start-Sleep -Seconds 3
    $state = Get-AdbTransportState
    if ($state -eq "offline") {
        $result.Adb = "$Serial offline"
        Stop-WithMessage "ADB still shows '$Serial' as offline. Cold boot or wipe the emulator, then rerun provisioning."
    }
    return $state
}

function Start-ProvisioningEmulator {
    param([bool]$WithWipe)

    if ([string]::IsNullOrWhiteSpace($AvdName)) {
        Stop-WithMessage "No adb device '$Serial' is online. Provide -AvdName to let this script boot the emulator."
    }

    if (-not (Test-Path $emulator)) {
        Stop-WithMessage "emulator.exe not found at $emulator"
    }

    $args = @("-avd", $AvdName, "-netdelay", "none", "-netspeed", "full")
    if ($WithWipe) {
        $args += @("-wipe-data", "-no-snapshot-load")
    }
    if ($Serial -match "^emulator-(\d+)$") {
        $args += @("-port", $Matches[1])
    }

    if ($WithWipe) {
        Write-Host "starting clean emulator..."
    } else {
        Write-Host "starting emulator..."
    }
    Write-Host "Starting emulator AVD '$AvdName' serial target '$Serial' wipeData=$WithWipe"
    if (-not [string]::IsNullOrWhiteSpace($script:emulatorAvdHome)) {
        Write-Host "Using Android Studio AVD home: $script:emulatorAvdHome"
    }
    Write-Host "Emulator stdout log: $emulatorStdoutLog"
    Write-Host "Emulator stderr log: $emulatorStderrLog"

    Remove-Item -LiteralPath $emulatorStdoutLog, $emulatorStderrLog -ErrorAction SilentlyContinue
    Restore-AndroidEnvironment
    if (
        -not [string]::IsNullOrWhiteSpace($script:emulatorAvdHome) -and
        (Get-Command Start-Process).Parameters.ContainsKey("Environment")
    ) {
        Start-Process -FilePath $emulator `
            -ArgumentList $args `
            -RedirectStandardOutput $emulatorStdoutLog `
            -RedirectStandardError $emulatorStderrLog `
            -WindowStyle Hidden `
            -Environment @{ ANDROID_AVD_HOME = $script:emulatorAvdHome } | Out-Null
    } else {
        $previousAvdHome = [Environment]::GetEnvironmentVariable("ANDROID_AVD_HOME", "Process")
        try {
            if (-not [string]::IsNullOrWhiteSpace($script:emulatorAvdHome)) {
                Set-ProcessEnvironmentVariable -Name "ANDROID_AVD_HOME" -Value $script:emulatorAvdHome
            }
            Start-Process -FilePath $emulator `
                -ArgumentList $args `
                -RedirectStandardOutput $emulatorStdoutLog `
                -RedirectStandardError $emulatorStderrLog `
                -WindowStyle Hidden | Out-Null
        } finally {
            Set-ProcessEnvironmentVariable -Name "ANDROID_AVD_HOME" -Value $previousAvdHome
            Restore-AndroidEnvironment
        }
    }

    if ($WithWipe) {
        $result.AvdBoot = "started $AvdName with wipe-data"
    } else {
        $result.AvdBoot = "started $AvdName"
    }
}

function Get-AvailableAvds {
    if (-not (Test-Path $emulator)) {
        return @()
    }
    return Invoke-EmulatorListAvds
}

function Wait-EmulatorPortRelease {
    param([int]$TimeoutSeconds = 15)

    if ($Serial -notmatch "^emulator-(\d+)$") {
        Start-Sleep -Seconds $TimeoutSeconds
        return
    }

    $consolePort = [int]$Matches[1]
    $adbPort = $consolePort + 1
    $ports = @($consolePort, $adbPort)
    Write-Host "waiting emulator/qemu port release for ports $($ports -join ', ')..."
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $canCheckPorts = $null -ne (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)

    if (-not $canCheckPorts) {
        Start-Sleep -Seconds $TimeoutSeconds
        return
    }

    while ((Get-Date) -lt $deadline) {
        $connections = @(Get-NetTCPConnection -LocalPort $ports -ErrorAction SilentlyContinue)
        if ($connections.Count -eq 0) {
            return
        }
        Start-Sleep -Seconds 1
    }
}

function Stop-RunningEmulatorForWipe {
    Restore-AndroidEnvironment
    $state = Get-AdbTransportState
    if ($state -eq "") {
        Wait-EmulatorPortRelease -TimeoutSeconds 15
        return
    }

    Write-Host "stopping existing emulator..."
    Write-Host "Stopping existing emulator '$Serial' before explicit wipe..."
    Invoke-Adb @("-s", $Serial, "emu", "kill") | Out-Null
    Write-Host "waiting emulator shutdown..."
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $state = Get-AdbTransportState
        if ($state -eq "") {
            Wait-EmulatorPortRelease -TimeoutSeconds 15
            return
        }
    }

    Stop-WithMessage "Existing emulator '$Serial' did not stop within 90 seconds. Close or cold boot the emulator, then rerun with -WipeData."
}

function Read-AdbState {
    Restore-AndroidEnvironment
    return (Invoke-Adb @("-s", $Serial, "get-state") 2>$null | Out-String).Trim()
}

function Test-AdbReadyRecheck {
    Restore-AndroidEnvironment
    Write-Host "Re-checking ADB after wait-adb failure before abort..."

    $devicesResult = Invoke-AdbCaptured -Arguments @("devices", "-l")
    $devicesText = $devicesResult.Stdout
    Write-Host "adb devices -l:"
    Write-Host (Format-CapturedValue $devicesText)

    $stateResult = Invoke-AdbCaptured -Arguments @("-s", $Serial, "get-state")
    $state = $stateResult.Stdout
    Write-Host "adb -s $Serial get-state: $(Format-CapturedValue $state)"

    $bootResult = Invoke-AdbCaptured -Arguments @("-s", $Serial, "shell", "getprop", "sys.boot_completed")
    $bootCompleted = $bootResult.Stdout
    Write-Host "adb -s $Serial shell getprop sys.boot_completed: $(Format-CapturedValue $bootCompleted)"

    $devBootResult = Invoke-AdbCaptured -Arguments @("-s", $Serial, "shell", "getprop", "dev.bootcomplete")
    $devBootComplete = $devBootResult.Stdout
    Write-Host "adb -s $Serial shell getprop dev.bootcomplete: $(Format-CapturedValue $devBootComplete)"

    $pmResult = Invoke-AdbCaptured -Arguments @("-s", $Serial, "shell", "pm", "path", "android")
    Write-Host "adb -s $Serial shell pm path android: $(Format-CapturedValue $pmResult.Stdout)"

    $settingsResult = Invoke-AdbCaptured -Arguments @("-s", $Serial, "shell", "settings", "get", "global", "device_provisioned")
    Write-Host "adb -s $Serial shell settings get global device_provisioned: $(Format-CapturedValue $settingsResult.Stdout)"

    return (
        $stateResult.ExitCode -eq 0 -and $state -eq "device" -and
        $bootResult.ExitCode -eq 0 -and $bootCompleted -eq "1" -and
        $devBootResult.ExitCode -eq 0 -and
        ([string]::IsNullOrWhiteSpace($devBootComplete) -or $devBootComplete -eq "1") -and
        $pmResult.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($pmResult.Stdout) -and
        $settingsResult.ExitCode -eq 0
    )
}

function Invoke-WaitAdb {
    $waitAdbScript = Join-Path $PSScriptRoot "wait-adb.ps1"
    Restore-AndroidEnvironment
    try {
        & $waitAdbScript -Serial $Serial -TimeoutSeconds 1200 -SdkRoot $SdkRoot
        Require-ExitCode "wait-adb.ps1"
    } catch {
        $waitError = $_.Exception.Message
        if (Test-AdbReadyRecheck) {
            $result.Adb = "ready after wait-adb re-check"
            Write-Host "ADB re-check passed; continuing provisioning."
            return
        }

        $result.Adb = "wait-adb failed"
        Stop-WithMessage "Timed out waiting for adb device '$Serial'. $waitError"
    }
}

function Read-BootCompleted {
    Restore-AndroidEnvironment
    return (Invoke-Adb @("-s", $Serial, "shell", "getprop", "sys.boot_completed") 2>$null | Out-String).Trim()
}

function Wait-BootCompleted {
    param([int]$TimeoutSeconds = 60)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $bootCompleted = Read-BootCompleted
        if ($bootCompleted -eq "1") {
            return "1"
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    return $bootCompleted
}

function Wait-AppProcess {
    param([int]$TimeoutSeconds = 20)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Restore-AndroidEnvironment
        $pidText = (Invoke-Adb @("-s", $Serial, "shell", "pidof", $ownerPackage) 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($pidText)) {
            return $pidText
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    return ""
}

function Test-LogcatHasFatalException {
    Restore-AndroidEnvironment
    $logcatOutput = Invoke-Adb @("-s", $Serial, "logcat", "-d", "-t", "2000") 2>&1
    $logcatText = ($logcatOutput | Out-String)
    return ($LASTEXITCODE -eq 0 -and $logcatText -match "(?i)FATAL EXCEPTION")
}

function Test-IsSameOwner {
    param([string]$OwnersText)
    if ([string]::IsNullOrWhiteSpace($OwnersText)) {
        return $false
    }
    return ($OwnersText -match [regex]::Escape($ownerComponent)) -or
        (($OwnersText -match [regex]::Escape($ownerPackage)) -and ($OwnersText -match [regex]::Escape($ownerReceiver)))
}

function Test-HasAnyOwner {
    param([string]$OwnersText)
    if ([string]::IsNullOrWhiteSpace($OwnersText)) {
        return $false
    }
    if ($OwnersText -match "(?i)\bno owners\b") {
        return $false
    }
    return ($OwnersText -match "(?i)owner") -or ($OwnersText -match "ComponentInfo\{")
}

function Get-OwnersText {
    Restore-AndroidEnvironment
    $ownersOutput = Invoke-Adb @("-s", $Serial, "shell", "dpm", "list-owners") 2>&1
    $ownersExit = $LASTEXITCODE
    $ownersText = ($ownersOutput | Out-String).Trim()
    if ($ownersExit -ne 0) {
        Stop-WithMessage "Unable to read dpm list-owners: $ownersText" $ownersExit
    }
    return $ownersText
}

function Confirm-OwnerPackageReadyAfterInstall {
    Write-Host "Verifying installed package path..."
    $packagePathResult = Invoke-AdbCaptured -Arguments @("-s", $Serial, "shell", "pm", "path", $ownerPackage)
    if ($packagePathResult.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($packagePathResult.Stdout)) {
        $result.ApkInstall = "pm path verification failed"
        $packagePathExit = if ($packagePathResult.ExitCode -ne 0) { $packagePathResult.ExitCode } else { 1 }
        Stop-WithMessage "APK install succeeded, but 'pm path $ownerPackage' failed. stdout: $(Format-CapturedValue $packagePathResult.Stdout) stderr: $(Format-CapturedValue $packagePathResult.Stderr)" $packagePathExit
    }
    Write-Host "pm path ${ownerPackage}:"
    Write-Host $packagePathResult.Stdout

    Start-Sleep -Seconds 5

    $bootResult = Invoke-AdbCaptured -Arguments @("-s", $Serial, "shell", "getprop", "sys.boot_completed")
    if ($bootResult.ExitCode -ne 0 -or $bootResult.Stdout -ne "1") {
        $result.Adb = "boot_completed after install=$(Format-CapturedValue $bootResult.Stdout)"
        $bootExit = if ($bootResult.ExitCode -ne 0) { $bootResult.ExitCode } else { 1 }
        Stop-WithMessage "Emulator '$Serial' lost boot readiness after APK install. sys.boot_completed=$(Format-CapturedValue $bootResult.Stdout) stderr=$(Format-CapturedValue $bootResult.Stderr)" $bootExit
    }
    Write-Host "boot_completed after install: 1"
}

function Set-EmulatorSetupIncompleteForDeviceOwner {
    Write-Host "Resetting emulator setup-complete flags before Device Owner setup..."
    Restore-AndroidEnvironment
    Invoke-Adb @("-s", $Serial, "shell", "settings", "put", "secure", "user_setup_complete", "0") | Out-Null
    Require-ExitCode "settings put secure user_setup_complete 0"
    Invoke-Adb @("-s", $Serial, "shell", "settings", "put", "global", "device_provisioned", "0") | Out-Null
    Require-ExitCode "settings put global device_provisioned 0"
}

function Set-EmulatorSetupCompleteAfterDeviceOwner {
    Restore-AndroidEnvironment
    Invoke-Adb @("-s", $Serial, "shell", "settings", "put", "global", "device_provisioned", "1") | Out-Null
    Invoke-Adb @("-s", $Serial, "shell", "settings", "put", "secure", "user_setup_complete", "1") | Out-Null
}

function Test-AccountManagerHasNoAccounts {
    param([pscustomobject]$AccountResult)

    if ($AccountResult.ExitCode -ne 0) {
        return $false
    }
    return ($AccountResult.Stdout -match "(?m)^\s*Accounts:\s+0\s*$")
}

function Wait-AccountManagerReadyForDeviceOwner {
    param(
        [int]$TimeoutSeconds = 120,
        [int]$StableSeconds = 30
    )

    Write-Host "Waiting for AccountManager to report zero accounts before Device Owner setup..."
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $stableSince = $null
    $lastStatusAt = Get-Date "2000-01-01"
    $lastAccountText = ""

    while ((Get-Date) -lt $deadline) {
        $accountResult = Invoke-AdbCaptured -Arguments @("-s", $Serial, "shell", "dumpsys", "account") -TimeoutSeconds 30
        $hasNoAccounts = Test-AccountManagerHasNoAccounts -AccountResult $accountResult
        $now = Get-Date

        if ($hasNoAccounts) {
            if ($null -eq $stableSince) {
                $stableSince = $now
            }

            $stableFor = [int](($now - $stableSince).TotalSeconds)
            if ($stableFor -ge $StableSeconds) {
                Write-Host "AccountManager ready: Accounts: 0 stable for ${StableSeconds}s."
                return
            }

            if (($now - $lastStatusAt).TotalSeconds -ge 15) {
                Write-Host "AccountManager reports Accounts: 0; waiting for stability ${stableFor}s/${StableSeconds}s..."
                $lastStatusAt = $now
            }
        } else {
            $stableSince = $null
            $lastAccountText = if ($accountResult.ExitCode -eq 0) { $accountResult.Stdout } else { $accountResult.Stderr }
            if (($now - $lastStatusAt).TotalSeconds -ge 15) {
                Write-Host "AccountManager not ready for Device Owner yet. exit=$($accountResult.ExitCode) detail=$(Format-CapturedValue $lastAccountText)"
                $lastStatusAt = $now
            }
        }

        Start-Sleep -Seconds 5
    }

    Stop-WithMessage "AccountManager did not report Accounts: 0 stable for ${StableSeconds}s within ${TimeoutSeconds}s. Last detail: $(Format-CapturedValue $lastAccountText)"
}

function Write-DpmSetOwnerResult {
    param([pscustomobject]$DpmResult)

    Write-Host "DPM_SET_OWNER_EXIT_CODE=$($DpmResult.ExitCode)"
    Write-Host "stdout:"
    Write-Host (Format-CapturedValue $DpmResult.Stdout)
    Write-Host "stderr:"
    Write-Host (Format-CapturedValue $DpmResult.Stderr)
}

function Invoke-DpmSetDeviceOwner {
    param([switch]$WithUser)

    $arguments = @("-s", $Serial, "shell", "dpm", "set-device-owner")
    if ($WithUser) {
        $arguments += @("--user", "0")
        Write-Host "Setting Device Owner with --user 0..."
    } else {
        Write-Host "Setting Device Owner without --user fallback..."
    }
    $arguments += $ownerComponent

    $dpmResult = Invoke-AdbCaptured -Arguments $arguments -TimeoutSeconds 60
    Write-DpmSetOwnerResult -DpmResult $dpmResult
    return $dpmResult
}

function Test-ShouldFallbackDeviceOwnerWithoutUser {
    param([pscustomobject]$DpmResult)

    $text = "$($DpmResult.Stdout)`n$($DpmResult.Stderr)"
    return ($text -match "(?i)(unknown option|usage|invalid option|too many arguments|unknown command|invalid user|unknown user|no such user|unsupported.*user|user.*unsupported|--user)")
}

function Set-DeviceOwner {
    $beforeOwners = Get-OwnersText
    Write-Host "Owners before:"
    Write-Host $(if ($beforeOwners) { $beforeOwners } else { "<empty>" })

    if (Test-IsSameOwner $beforeOwners) {
        $result.DeviceOwner = "verified: $ownerComponent (already set)"
        return
    }

    if (Test-HasAnyOwner $beforeOwners) {
        $result.DeviceOwner = "blocked by existing owner"
        Stop-WithMessage "Another Device/Profile Owner already exists. Android requires a clean/factory-reset or wiped emulator before setting Device Owner."
    }

    Set-EmulatorSetupIncompleteForDeviceOwner
    Start-Sleep -Seconds 2
    Wait-AccountManagerReadyForDeviceOwner

    $setResult = Invoke-DpmSetDeviceOwner -WithUser

    if ($setResult.ExitCode -ne 0 -and (Test-ShouldFallbackDeviceOwnerWithoutUser -DpmResult $setResult)) {
        $setResult = Invoke-DpmSetDeviceOwner
    }

    if ($setResult.ExitCode -ne 0) {
        $result.DeviceOwner = "failed"
        Stop-WithMessage "Device Owner setup failed. Last dpm exit code: $($setResult.ExitCode). stdout: $(Format-CapturedValue $setResult.Stdout) stderr: $(Format-CapturedValue $setResult.Stderr)" $setResult.ExitCode
    }

    $afterOwners = Get-OwnersText
    Write-Host "Owners after:"
    Write-Host $(if ($afterOwners) { $afterOwners } else { "<empty>" })

    if (-not (Test-IsSameOwner $afterOwners)) {
        $result.DeviceOwner = "verification failed"
        Stop-WithMessage "Device Owner verification failed. dpm list-owners does not contain $ownerComponent."
    }

    $result.DeviceOwner = "verified: $ownerComponent"
    Set-EmulatorSetupCompleteAfterDeviceOwner
}

Push-Location $repoRoot
try {
    Write-Step "Preflight"
    if (-not (Test-Path $adb)) {
        Stop-WithMessage "adb.exe not found at $adb"
    }
    if (-not [string]::IsNullOrWhiteSpace($AvdName) -and -not (Test-Path $emulator)) {
        Stop-WithMessage "emulator.exe not found at $emulator"
    }
    if (-not (Test-Path $gradlew)) {
        Stop-WithMessage "gradlew.bat not found at $gradlew"
    }
    if ($WipeData -and [string]::IsNullOrWhiteSpace($AvdName)) {
        Stop-WithMessage "-WipeData requires -AvdName so the script can boot the correct emulator from a clean state."
    }

    Write-Host "SDK root: $SdkRoot"
    Write-Host "adb path: $adb"
    Write-Host "serial: $Serial"
    Write-Host "avd name: $(if ([string]::IsNullOrWhiteSpace($AvdName)) { '<not provided>' } else { $AvdName })"
    Write-Host "wipe data: $($WipeData.IsPresent)"
    Write-Host "skip build: $($SkipBuild.IsPresent)"

    $checkEnvScript = Join-Path $PSScriptRoot "check-android-env.ps1"
    $preflightAvdHome = Get-PreferredAndroidStudioAvdHome
    try {
        if (-not [string]::IsNullOrWhiteSpace($preflightAvdHome)) {
            Set-ProcessEnvironmentVariable -Name "ANDROID_AVD_HOME" -Value $preflightAvdHome
        }
        & $checkEnvScript -SdkRoot $SdkRoot
        $checkEnvExit = $LASTEXITCODE
    } finally {
        Restore-AndroidEnvironment
    }
    if ($checkEnvExit -ne 0) {
        Stop-WithMessage "check-android-env.ps1 failed with exit code $checkEnvExit." $checkEnvExit
    }

    if (-not [string]::IsNullOrWhiteSpace($AvdName)) {
        $availableAvds = Resolve-EmulatorAvdHome -RequiredAvdName $AvdName
        $availableText = if ($availableAvds.Count -gt 0) { $availableAvds -join ", " } else { "<none>" }
        $avdHomeText = if ([string]::IsNullOrWhiteSpace($script:emulatorAvdHome)) { "<default environment>" } else { $script:emulatorAvdHome }
        Write-Host "Emulator AVDS: $availableText"
        Write-Host "Selected AVD home: $avdHomeText"
        if ($AvdName -notin $availableAvds) {
            $result.AvdBoot = "AVD not found"
            Stop-WithMessage "AVD '$AvdName' was not found for this Android home. Available AVDs: $availableText"
        }
    }

    Write-Step "Emulator / ADB"
    Restore-AndroidEnvironment
    Invoke-Adb @("start-server") | Out-Null
    $state = Ensure-DeviceNotOffline
    if ($WipeData) {
        Stop-RunningEmulatorForWipe
        Start-ProvisioningEmulator -WithWipe $true
    } elseif ($state -eq "device") {
        $result.AvdBoot = "already running"
    } else {
        Start-ProvisioningEmulator -WithWipe $false
    }

    Invoke-WaitAdb
    $state = Ensure-DeviceNotOffline
    if ($state -ne "device") {
        $result.Adb = "not ready: $state"
        Stop-WithMessage "ADB transport '$Serial' is not ready. Current state: '$state'."
    }

    $bootCompleted = Wait-BootCompleted -TimeoutSeconds 60
    if ($bootCompleted -ne "1") {
        $result.Adb = "boot_completed=$bootCompleted"
        Stop-WithMessage "Emulator '$Serial' is online but sys.boot_completed is '$bootCompleted', expected '1'."
    }
    $result.Adb = "$Serial device, boot_completed=1"

    Write-Step "Build"
    if ($SkipBuild) {
        Write-Host "Skipping Gradle build because -SkipBuild was provided."
    } else {
        try {
            Set-GradleAndroidEnvironment
            $buildOutput = & $gradlew --no-daemon assembleDebug 2>&1
            $buildExit = $LASTEXITCODE
            $buildText = ($buildOutput | Out-String).Trim()
            if ($buildText) {
                Write-Host $buildText
            }
        } finally {
            Restore-AndroidEnvironment
        }
        if ($buildExit -ne 0) {
            $rootCause = Get-FirstRootCause -Text $buildText
            Stop-WithMessage "assembleDebug failed with exit code $buildExit. First root cause: $rootCause" $buildExit
        }
    }

    if (-not (Test-Path $apkPath)) {
        Stop-WithMessage "APK not found: $apkPath. Run .\gradlew.bat assembleDebug first or rerun without -SkipBuild."
    }

    Write-Step "Install APK"
    Restore-AndroidEnvironment
    $installOutput = Invoke-Adb @("-s", $Serial, "install", "-r", $apkPath) 2>&1
    $installExit = $LASTEXITCODE
    $installText = ($installOutput | Out-String).Trim()
    if ($installText) {
        Write-Host $installText
    }
    if ($installExit -ne 0) {
        $result.ApkInstall = "failed"
        if ($installText -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
            Stop-WithMessage "Existing package has different signing certificate. Use clean/factory-reset emulator or rerun with -WipeData." $installExit
        }
        Stop-WithMessage "APK install failed. Raw output: $installText" $installExit
    }
    $result.ApkInstall = "success"
    Confirm-OwnerPackageReadyAfterInstall

    Write-Step "Device Owner"
    Set-DeviceOwner

    Write-Step "Start app"
    Restore-AndroidEnvironment
    $startOutput = Invoke-Adb @("-s", $Serial, "shell", "am", "start", "-W", "-n", $launcherComponent) 2>&1
    $startExit = $LASTEXITCODE
    $startText = ($startOutput | Out-String).Trim()
    if ($startText) {
        Write-Host $startText
    }
    if ($startExit -ne 0) {
        Write-Host "WARN_RUNTIME_APP_START_EXIT_CODE: am start returned exit code $startExit. Raw output: $(Format-CapturedValue $startText)"
    }

    $appPid = Wait-AppProcess -TimeoutSeconds 20
    if ([string]::IsNullOrWhiteSpace($appPid)) {
        Write-Host "Launcher process was not visible after first start; retrying once..."
        Restore-AndroidEnvironment
        $startOutput = Invoke-Adb @("-s", $Serial, "shell", "am", "start", "-W", "-n", $launcherComponent) 2>&1
        $startExit = $LASTEXITCODE
        $startText = ($startOutput | Out-String).Trim()
        if ($startText) {
            Write-Host $startText
        }
        if ($startExit -ne 0) {
            Write-Host "WARN_RUNTIME_APP_START_RETRY_EXIT_CODE: am start retry returned exit code $startExit. Raw output: $(Format-CapturedValue $startText)"
        }
        $appPid = Wait-AppProcess -TimeoutSeconds 20
    }

    if ([string]::IsNullOrWhiteSpace($appPid)) {
        if (Test-LogcatHasFatalException) {
            $result.AppStart = "failed"
            Stop-WithMessage "Launcher activity start command completed, but process '$ownerPackage' was not visible and logcat contains FATAL EXCEPTION."
        }

        Write-Host "WARN_RUNTIME_APP_NOT_STAYING_FOREGROUND: launcher start completed but pidof '$ownerPackage' is empty."
        $result.AppStart = "WARN_RUNTIME_APP_NOT_STAYING_FOREGROUND"
    } else {
        $result.AppStart = "started $launcherComponent pid=$appPid"
    }

    if ($RebootAfterSetup) {
        Write-Step "Optional reboot"
        Restore-AndroidEnvironment
        Invoke-Adb @("-s", $Serial, "reboot")
        Require-ExitCode "adb reboot"
        $result.Reboot = "requested"
    }

    Write-ProvisioningSummary
    exit 0
}
finally {
    Pop-Location
}
