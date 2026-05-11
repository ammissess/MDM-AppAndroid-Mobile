[CmdletBinding()]
param(
    [string]$Serial = "emulator-5554",
    [int]$TimeoutSeconds = 1200,
    [int]$EmulatorPid = 0,
    [string]$EmulatorStdoutLog = "",
    [string]$EmulatorStderrLog = "",
    [int]$QemuStartupTimeoutSeconds = 90,
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Users\ADMIN\AppData\Local\Android\Sdk" })
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $SdkRoot "platform-tools\adb.exe"

if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
    exit 1
}

$Serial = $Serial.Trim()

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
        $exitCode = if ($null -eq $process.ExitCode) { 0 } else { $process.ExitCode }
        return [pscustomobject]@{
            ExitCode = $exitCode
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

function Get-AdbSerialParse {
    param([string[]]$Devices)

    $targetSerial = $Serial.Trim()
    foreach ($line in $Devices) {
        $trimmed = $line.Trim()
        if (
            [string]::IsNullOrWhiteSpace($trimmed) -or
            $trimmed -match "^(List of devices attached|\* daemon)"
        ) {
            continue
        }

        if ($trimmed -match "^(?<serial>\S+)\s+(?<state>\S+)(?:\s+.*)?$") {
            $parsedSerial = $Matches["serial"]
            $parsedState = $Matches["state"]
            if ($parsedSerial -eq $targetSerial) {
                return [pscustomobject]@{
                    TargetSerial = $targetSerial
                    ParsedSerial = $parsedSerial
                    ParsedState = $parsedState
                    MatchedLine = $trimmed
                }
            }
        }
    }
    return [pscustomobject]@{
        TargetSerial = $targetSerial
        ParsedSerial = ""
        ParsedState = ""
        MatchedLine = ""
    }
}

function Get-AdbDevicesSnapshot {
    $result = Invoke-AdbCapture -Arguments @("devices", "-l")
    $lines = @()
    if (-not [string]::IsNullOrWhiteSpace($result.Stdout)) {
        $lines = @($result.Stdout -split "\r?\n")
    }

    $parse = Get-AdbSerialParse -Devices $lines
    return [pscustomobject]@{
        ExitCode = $result.ExitCode
        Stdout = $result.Stdout
        Stderr = $result.Stderr
        Lines = $lines
        TargetSerial = $parse.TargetSerial
        ParsedSerial = $parse.ParsedSerial
        ParsedState = $parse.ParsedState
        MatchedLine = $parse.MatchedLine
    }
}

function Get-AdbDevices {
    return (Get-AdbDevicesSnapshot).Lines
}

function Get-AdbSerialState {
    param([string[]]$Devices)

    return (Get-AdbSerialParse -Devices $Devices).ParsedState
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

function Get-SerialPorts {
    if ($Serial -notmatch "^emulator-(\d+)$") {
        return @()
    }

    $consolePort = [int]$Matches[1]
    return @($consolePort, $consolePort + 1)
}

function Test-AdbCommandSuccess {
    param([int]$ExitCode)
    # Treat null or 0 as success, anything else as failure
    if ($null -eq $ExitCode -or $ExitCode -eq 0) {
        return $true
    }
    return $false
}

function Test-ProcessAlive {

    if ($ProcessId -le 0) {
        return $false
    }

    try {
        return $null -ne (Get-Process -Id $ProcessId -ErrorAction Stop)
    } catch {
        return $false
    }
}

function Get-QemuProcesses {
    $rows = @()
    try {
        $rows = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object { $_.Name -like "qemu-system*" })
    } catch {
        return @()
    }

    if ($EmulatorPid -gt 0) {
        $children = @($rows | Where-Object { $_.ParentProcessId -eq $EmulatorPid })
        if ($children.Count -gt 0) {
            return $children
        }
    }

    return $rows
}

function Get-ProcessSnapshotText {
    $rows = @()
    try {
        $rows = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
            $_.Name -match "^(adb|emulator|qemu-system)" -or
            ($EmulatorPid -gt 0 -and ($_.ProcessId -eq $EmulatorPid -or $_.ParentProcessId -eq $EmulatorPid))
        })
    } catch {
        return "process snapshot unavailable: $($_.Exception.Message)"
    }

    if ($rows.Count -eq 0) {
        return "<no adb/emulator/qemu processes>"
    }

    return (($rows | Sort-Object ProcessId | ForEach-Object {
        "pid=$($_.ProcessId) name=$($_.Name) parent=$($_.ParentProcessId) cmd=$($_.CommandLine)"
    }) -join "`n")
}

function Get-PortSnapshotText {
    $ports = @(Get-SerialPorts)
    if ($ports.Count -eq 0) {
        return "<serial is not an emulator port serial>"
    }

    if ($null -eq (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) {
        return "<Get-NetTCPConnection unavailable>"
    }

    $connections = @(Get-NetTCPConnection -LocalPort $ports -ErrorAction SilentlyContinue)
    if ($connections.Count -eq 0) {
        return "<no listeners or connections on ports $($ports -join ', ')>"
    }

    return (($connections | Sort-Object LocalPort, State, OwningProcess | ForEach-Object {
        $ownerName = "<unknown>"
        try {
            $owner = Get-Process -Id $_.OwningProcess -ErrorAction Stop
            $ownerName = $owner.ProcessName
        } catch {
        }
        "local=$($_.LocalAddress):$($_.LocalPort) remote=$($_.RemoteAddress):$($_.RemotePort) state=$($_.State) owner=$($_.OwningProcess)($ownerName)"
    }) -join "`n")
}

function Get-LogTailText {
    param(
        [string]$Path,
        [int]$Lines = 80
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return "<not configured>"
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        return "<missing: $Path>"
    }

    $content = @(Get-Content -LiteralPath $Path -Tail $Lines -ErrorAction SilentlyContinue)
    if ($content.Count -eq 0) {
        return "<empty>"
    }

    return (($content | Out-String).Trim())
}

function Write-AdbParseDiagnostics {
    param(
        [string]$Reason,
        [pscustomobject]$Snapshot,
        [string]$Decision
    )

    Write-Host ""
    Write-Host "adb parse diagnostics ($Reason):"
    Write-Host "raw adb devices -l stdout:"
    Write-Host (Format-AdbValue $Snapshot.Stdout)
    if ($Snapshot.ExitCode -ne 0 -or -not [string]::IsNullOrWhiteSpace($Snapshot.Stderr)) {
        Write-Host "adb devices stderr:"
        Write-Host (Format-AdbValue $Snapshot.Stderr)
    }
    Write-Host "target serial: '$($Snapshot.TargetSerial)'"
    Write-Host "matched line: '$(Format-AdbValue $Snapshot.MatchedLine)'"
    Write-Host "parsed serial: '$(Format-AdbValue $Snapshot.ParsedSerial)'"
    Write-Host "parsed state: '$(Format-AdbValue $Snapshot.ParsedState)'"
    Write-Host "comparison parsed_serial == target_serial: $($Snapshot.ParsedSerial -eq $Snapshot.TargetSerial)"
    Write-Host "comparison parsed_state == device: $($Snapshot.ParsedState -eq "device")"
    Write-Host "branch decision: $Decision"
}

function Write-AdbDevicesSnapshot {
    param(
        [string]$Reason,
        [pscustomobject]$Snapshot = $null,
        [string]$Decision = "snapshot"
    )

    if ($null -eq $Snapshot) {
        $Snapshot = Get-AdbDevicesSnapshot
    }
    Write-AdbParseDiagnostics -Reason $Reason -Snapshot $Snapshot -Decision $Decision
}

function Write-WaitFailureDiagnostics {
    param([string]$Reason)

    $elapsed = [int](((Get-Date) - $script:startTime).TotalSeconds)
    Write-Host ""
    Write-Host "== wait-adb diagnostics =="
    Write-Host "reason: $Reason"
    Write-Host "serial: $Serial"
    Write-Host "elapsed_seconds: $elapsed"
    Write-Host "timeout_seconds: $TimeoutSeconds"
    if ($EmulatorPid -gt 0) {
        Write-Host "emulator_pid: $EmulatorPid"
        Write-Host "emulator_pid_alive: $(Test-ProcessAlive $EmulatorPid)"
    }

    Write-AdbDevicesSnapshot -Reason $Reason

    $stateResult = Get-AdbGetState
    Write-Host "adb -s $Serial get-state: $(Format-AdbValue $stateResult.Stdout)"
    if ($stateResult.ExitCode -ne 0 -or -not [string]::IsNullOrWhiteSpace($stateResult.Stderr)) {
        Write-Host "get-state stderr: $(Format-AdbValue $stateResult.Stderr)"
    }

    $bootResult = Get-BootCompleted
    Write-Host "adb -s $Serial shell getprop sys.boot_completed: $(Format-AdbValue $bootResult.Stdout)"
    if ($bootResult.ExitCode -ne 0 -or -not [string]::IsNullOrWhiteSpace($bootResult.Stderr)) {
        Write-Host "boot_completed stderr: $(Format-AdbValue $bootResult.Stderr)"
    }

    Write-Host ""
    Write-Host "process snapshot:"
    Write-Host (Get-ProcessSnapshotText)

    Write-Host ""
    Write-Host "port snapshot:"
    Write-Host (Get-PortSnapshotText)

    Write-Host ""
    Write-Host "emulator stdout tail:"
    Write-Host (Get-LogTailText -Path $EmulatorStdoutLog)

    Write-Host ""
    Write-Host "emulator stderr tail:"
    Write-Host (Get-LogTailText -Path $EmulatorStderrLog)
}

function Test-EmulatorRuntimeFailureForMissingSerial {
    param([datetime]$Now)

    if ($EmulatorPid -le 0) {
        return ""
    }

    if (($Now - $script:lastRuntimeCheckAt).TotalSeconds -lt 5) {
        return ""
    }

    $script:lastRuntimeCheckAt = $Now
    $emulatorAlive = Test-ProcessAlive $EmulatorPid
    $qemuProcesses = @(Get-QemuProcesses)
    $qemuPresent = $qemuProcesses.Count -gt 0

    if ($qemuPresent) {
        $script:qemuSeen = $true
        $script:qemuMissingSince = $null
        return ""
    }

    if ($null -eq $script:qemuMissingSince) {
        $script:qemuMissingSince = $Now
    }

    if (-not $emulatorAlive) {
        return "EMULATOR_EXITED_BEFORE_ADB serial=$Serial emulator_pid=$EmulatorPid"
    }

    if ($script:qemuSeen) {
        return "QEMU_EXITED_BEFORE_ADB serial=$Serial emulator_pid=$EmulatorPid"
    }

    if (($Now - $script:startTime).TotalSeconds -ge $QemuStartupTimeoutSeconds) {
        return "QEMU_NOT_STARTED_BEFORE_ADB serial=$Serial emulator_pid=$EmulatorPid qemu_timeout_seconds=$QemuStartupTimeoutSeconds"
    }

    return ""
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
$script:lastRuntimeCheckAt = Get-Date "2000-01-01"
$script:qemuSeen = $false
$script:qemuMissingSince = $null
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$serialMissingSince = $null
$lastAdbRecovery = Get-Date "2000-01-01"
$lastOfflineReconnect = Get-Date "2000-01-01"

Invoke-AdbCapture -Arguments @("start-server") | Out-Null
Write-Host "WAIT_ADB_TARGET serial=$Serial timeout_seconds=$TimeoutSeconds emulator_pid=$EmulatorPid qemu_startup_timeout_seconds=$QemuStartupTimeoutSeconds"

while ((Get-Date) -lt $deadline) {
    $snapshot = Get-AdbDevicesSnapshot
    $listedState = $snapshot.ParsedState
    $now = Get-Date

    if ([string]::IsNullOrWhiteSpace($listedState)) {
        if ($null -eq $serialMissingSince) {
            $serialMissingSince = $now
            Write-AdbParseDiagnostics -Reason "serial first missing" -Snapshot $snapshot -Decision "target serial not found; wait before recovery"
        }

        Write-WaitStatus "waiting serial... serial=$Serial"

        $runtimeFailure = Test-EmulatorRuntimeFailureForMissingSerial -Now $now
        if (-not [string]::IsNullOrWhiteSpace($runtimeFailure)) {
            Write-WaitFailureDiagnostics -Reason $runtimeFailure
            throw $runtimeFailure
        }

        if (
            (($now - $serialMissingSince).TotalSeconds -ge 35) -and
            (($now - $lastAdbRecovery).TotalSeconds -ge 35)
        ) {
            $recheckSnapshot = Get-AdbDevicesSnapshot
            Write-AdbDevicesSnapshot -Reason "before adb recovery for missing serial" -Snapshot $recheckSnapshot -Decision "rechecked before recovery"
            if (-not [string]::IsNullOrWhiteSpace($recheckSnapshot.ParsedState)) {
                Write-WaitStatus "adb recovery skipped; serial found in fresh snapshot... serial=$Serial state=$($recheckSnapshot.ParsedState)" -Force
                $serialMissingSince = $null
                Start-Sleep -Seconds 1
                continue
            }

            Write-AdbParseDiagnostics -Reason "adb recovery decision" -Snapshot $recheckSnapshot -Decision "target serial still missing; restarting adb"
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
        Write-AdbParseDiagnostics -Reason "serial non-device state" -Snapshot $snapshot -Decision "target serial found but state is '$listedState'; reconnect offline transports"
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

    # Note: We already confirmed the device is listed as "device" in adb devices.
    # get-state command can sometimes fail transiently during boot even when the device is ready.
    # Only fail if get-state returns a value that contradicts the device list.
    $stateResult = Get-AdbGetState
    $state = $stateResult.Stdout
    if ($stateResult.ExitCode -ne 0) {
        # get-state failed, but device is in list as "device", so allow progress
        Write-WaitStatus "serial device in list, allowing boot checks despite get-state error... serial=$Serial"
        Start-Sleep -Seconds 1
    } elseif ($state -ne "device") {
        # get-state returned a state other than "device"
        Write-WaitStatus "serial found but get-state returned unexpected state... serial=$Serial state=$state"
        Start-Sleep -Seconds 2
        continue
    }

    $bootResult = Get-BootCompleted
    $bootCompleted = $bootResult.Stdout
    if (-not (Test-AdbCommandSuccess $bootResult.ExitCode) -or $bootCompleted -ne "1") {
        $stderrMsg = if ([string]::IsNullOrWhiteSpace($bootResult.Stderr)) { "" } else { " stderr='$($bootResult.Stderr)'" }
        Write-WaitStatus "serial device, waiting sys.boot_completed... serial=$Serial boot_completed=$(Format-AdbValue $bootCompleted) exit_code=$($bootResult.ExitCode)$stderrMsg"
        Start-Sleep -Seconds 2
        continue
    }

    $devBootResult = Get-DevBootComplete
    $devBootComplete = $devBootResult.Stdout
    if (-not (Test-AdbCommandSuccess $devBootResult.ExitCode)) {
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
    if (-not (Test-AdbCommandSuccess $packageManagerResult.ExitCode) -or [string]::IsNullOrWhiteSpace($packageManagerResult.Stdout)) {
        Write-WaitStatus "serial device, waiting package manager... serial=$Serial stderr=$(Format-AdbValue $packageManagerResult.Stderr)"
        Start-Sleep -Seconds 2
        continue
    }

    $settingsResult = Get-SettingsProviderReady
    if (-not (Test-AdbCommandSuccess $settingsResult.ExitCode)) {
        Write-WaitStatus "serial device, waiting settings provider... serial=$Serial stderr=$(Format-AdbValue $settingsResult.Stderr)"
        Start-Sleep -Seconds 2
        continue
    }

    Write-Host "ADB_READY serial=$Serial boot_completed=1"
    Write-AdbParseDiagnostics -Reason "adb ready" -Snapshot $snapshot -Decision "target serial parsed as device; boot and service checks passed"
    return
}

Write-WaitFailureDiagnostics -Reason "ADB_TIMEOUT"
throw "Timed out waiting for adb device '$Serial' to be device, boot_completed=1, and package manager ready within $TimeoutSeconds seconds."
