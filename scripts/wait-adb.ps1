[CmdletBinding()]
param(
    [string]$Serial = "emulator-5554",
    [int]$TimeoutSeconds = 1200,
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Users\ADMIN\AppData\Local\Android\Sdk" })
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $SdkRoot "platform-tools\adb.exe"

if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
    exit 1
}

function Invoke-Adb {
    param([string[]]$Arguments)
    & $adb @Arguments
}

function Invoke-AdbCapture {
    param(
        [string[]]$Arguments,
        [int]$TimeoutSeconds = 15
    )

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

function Get-AdbDevices {
    $result = Invoke-AdbCapture -Arguments @("devices", "-l")
    if ($result.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($result.Stdout)) {
        return @()
    }
    return @($result.Stdout -split "\r?\n")
}

function Get-AdbSerialState {
    param([string[]]$Devices)
    $escapedSerial = [regex]::Escape($Serial)
    foreach ($line in $Devices) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^$escapedSerial\s+(\S+)") {
            return $Matches[1]
        }
    }
    return ""
}

function Get-AdbGetState {
    return Invoke-AdbCapture -Arguments @("-s", $Serial, "get-state")
}

function Get-BootCompleted {
    return Invoke-AdbCapture -Arguments @("-s", $Serial, "shell", "getprop", "sys.boot_completed")
}

function Get-DevBootComplete {
    return Invoke-AdbCapture -Arguments @("-s", $Serial, "shell", "getprop", "dev.bootcomplete")
}

function Get-PackageManagerReady {
    return Invoke-AdbCapture -Arguments @("-s", $Serial, "shell", "pm", "path", "android")
}

function Get-SettingsProviderReady {
    return Invoke-AdbCapture -Arguments @("-s", $Serial, "shell", "settings", "get", "global", "device_provisioned")
}

function Format-AdbValue {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return "<empty>"
    }
    return $Value
}

function Write-WaitStatus {
    param(
        [string]$Message,
        [switch]$Force
    )

    $now = Get-Date
    if (
        $Force -or
        $script:lastStatusMessage -ne $Message -or
        (($now - $script:lastStatusAt).TotalSeconds -ge 30)
    ) {
        $elapsed = [int](($now - $script:startTime).TotalSeconds)
        Write-Host ("[{0}s] {1}" -f $elapsed, $Message)
        $script:lastStatusMessage = $Message
        $script:lastStatusAt = $now
    }
}

function Restart-AdbServer {
    Write-WaitStatus "restarting adb server..." -Force
    Invoke-AdbCapture -Arguments @("kill-server") | Out-Null
    Start-Sleep -Seconds 1
    Invoke-AdbCapture -Arguments @("start-server") | Out-Null
    Start-Sleep -Seconds 1
    Invoke-AdbCapture -Arguments @("reconnect", "offline") | Out-Null
}

function Try-AdbTcpConnect {
    if ($Serial -notmatch "^emulator-(\d+)$") {
        return
    }

    $consolePort = [int]$Matches[1]
    $adbPort = $consolePort + 1
    Write-WaitStatus "trying adb connect 127.0.0.1:$adbPort for missing emulator serial..." -Force
    Invoke-AdbCapture -Arguments @("connect", "127.0.0.1:$adbPort") | Out-Null
    Start-Sleep -Seconds 2
}

$script:startTime = Get-Date
$script:lastStatusMessage = ""
$script:lastStatusAt = Get-Date "2000-01-01"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$serialMissingSince = $null
$lastAdbRecovery = Get-Date "2000-01-01"
$lastOfflineReconnect = Get-Date "2000-01-01"

Invoke-AdbCapture -Arguments @("start-server") | Out-Null

while ((Get-Date) -lt $deadline) {
    $devices = Get-AdbDevices
    $listedState = Get-AdbSerialState -Devices $devices
    $now = Get-Date

    if ([string]::IsNullOrWhiteSpace($listedState)) {
        if ($null -eq $serialMissingSince) {
            $serialMissingSince = $now
        }

        Write-WaitStatus "waiting serial... serial=$Serial"

        if (
            (($now - $serialMissingSince).TotalSeconds -ge 35) -and
            (($now - $lastAdbRecovery).TotalSeconds -ge 35)
        ) {
            Restart-AdbServer
            Try-AdbTcpConnect
            Restart-AdbServer
            $lastAdbRecovery = Get-Date
        }

        Start-Sleep -Seconds 2
        continue
    }

    $serialMissingSince = $null

    if ($listedState -ne "device") {
        Write-WaitStatus "serial offline... serial=$Serial state=$listedState"

        if (($now - $lastOfflineReconnect).TotalSeconds -ge 15) {
            Invoke-AdbCapture -Arguments @("reconnect", "offline") | Out-Null
            $lastOfflineReconnect = Get-Date
        }

        if (($now - $lastAdbRecovery).TotalSeconds -ge 45) {
            Restart-AdbServer
            $lastAdbRecovery = Get-Date
        }

        Start-Sleep -Seconds 2
        continue
    }

    $stateResult = Get-AdbGetState
    $state = $stateResult.Stdout
    if ($stateResult.ExitCode -ne 0 -or $state -ne "device") {
        Write-WaitStatus "serial found but get-state is not ready... serial=$Serial state=$state"
        Start-Sleep -Seconds 2
        continue
    }

    $bootResult = Get-BootCompleted
    $bootCompleted = $bootResult.Stdout
    if ($bootResult.ExitCode -ne 0 -or $bootCompleted -ne "1") {
        Write-WaitStatus "serial device, waiting sys.boot_completed... serial=$Serial boot_completed=$(Format-AdbValue $bootCompleted)"
        Start-Sleep -Seconds 2
        continue
    }

    $devBootResult = Get-DevBootComplete
    $devBootComplete = $devBootResult.Stdout
    if ($devBootResult.ExitCode -ne 0) {
        Write-WaitStatus "serial device, waiting dev.bootcomplete check... serial=$Serial stderr=$(Format-AdbValue $devBootResult.Stderr)"
        Start-Sleep -Seconds 2
        continue
    }
    if (-not [string]::IsNullOrWhiteSpace($devBootComplete) -and $devBootComplete -ne "1") {
        Write-WaitStatus "serial device, waiting dev.bootcomplete... serial=$Serial dev_bootcomplete=$devBootComplete"
        Start-Sleep -Seconds 2
        continue
    }

    $packageManagerResult = Get-PackageManagerReady
    if ($packageManagerResult.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($packageManagerResult.Stdout)) {
        Write-WaitStatus "serial device, waiting package manager... serial=$Serial stderr=$(Format-AdbValue $packageManagerResult.Stderr)"
        Start-Sleep -Seconds 2
        continue
    }

    $settingsResult = Get-SettingsProviderReady
    if ($settingsResult.ExitCode -ne 0) {
        Write-WaitStatus "serial device, waiting settings provider... serial=$Serial stderr=$(Format-AdbValue $settingsResult.Stderr)"
        Start-Sleep -Seconds 2
        continue
    }

    Write-Host "ADB_READY serial=$Serial boot_completed=1"
    exit 0
}

Write-Error "Timed out waiting for adb device '$Serial' to be device, boot_completed=1, and package manager ready within $TimeoutSeconds seconds."
exit 1
