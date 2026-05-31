[CmdletBinding()]
param(
    [int]$DeviceCount = 10,
    [int]$ConcurrentMax = 5,
    [int]$DurationMinutes = 30,
    [int]$Api = 36,
    [int]$CommandIntervalSec = 20,
    [int]$AvdRamMB = 768,
    [string]$OutputDir = "benchmark-results\stress-test-10device",
    [switch]$SkipBuild,
    [switch]$SkipAvdCreate
)

$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
$out = Join-Path $repoRoot $OutputDir
New-Item -ItemType Directory -Force -Path $out | Out-Null
$timeline = New-Object System.Collections.Generic.List[object]
$memoryLog = New-Object System.Collections.Generic.List[object]

function Add-TimelineEvent {
    param([string]$Event,[string]$Avd='',[string]$Serial='',[string]$Status='',[string]$Note='')
    $script:timeline.Add([pscustomobject]@{timestamp=(Get-Date -Format o);event=$Event;avd=$Avd;serial=$Serial;status=$Status;note=$Note}) | Out-Null
}

function Add-MemoryWatchdogRecord {
    param([string]$Phase,[int]$FreeMB,[bool]$Safe,[string]$Action='',[string]$Note='')
    $script:memoryLog.Add([pscustomobject]@{timestamp=(Get-Date -Format o);phase=$Phase;freeRamMB=$FreeMB;safe=$Safe;action=$Action;note=$Note}) | Out-Null
}

function Test-MemorySafe {
    param([int]$MinFreeMB=4000,[string]$Phase='check')
    $freeMB=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
    $safe=$freeMB -gt $MinFreeMB
    if(-not $safe){ Write-Warning "LOW MEMORY: Free=$freeMB MB < threshold=$MinFreeMB MB" }
    Add-MemoryWatchdogRecord -Phase $Phase -FreeMB $freeMB -Safe $safe -Note "threshold=$MinFreeMB MB"
    return [pscustomobject]@{FreeMB=$freeMB;Safe=$safe}
}

function New-AuthHeaderStress {
    param([string]$Token)
    @{ Authorization = "Bearer $Token" }
}

function Get-SdkRootPath {
    if($env:ANDROID_HOME){$env:ANDROID_HOME}
    elseif($env:ANDROID_SDK_ROOT){$env:ANDROID_SDK_ROOT}
    else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
}

function Write-ContentSafe {
    param([string]$Path, [string]$Value, [int]$Retries = 8, [int]$DelayMs = 500)
    for ($i = 0; $i -lt $Retries; $i++) {
        try {
            [System.IO.File]::WriteAllText($Path, $Value, [System.Text.Encoding]::UTF8)
            return
        } catch [System.IO.IOException] {
            if ($i -lt ($Retries - 1)) {
                # Back off with jitter to reduce contention between concurrent provisioning jobs
                $jitter = Get-Random -Minimum 0 -Maximum 200
                Start-Sleep -Milliseconds ($DelayMs + $jitter)
            } else {
                Write-Warning "Write-ContentSafe: could not write '$Path' after $Retries attempts: $_"
            }
        }
    }
}

function Ensure-AdbServer {
    # Check if ADB server is responsive; restart it if not.
    $test = & $script:adbExe devices 2>&1
    if ($LASTEXITCODE -ne 0 -or ($test -join '') -match 'error|cannot connect|failed to start') {
        Write-Warning "ADB server unresponsive — restarting..."
        & $script:adbExe kill-server 2>&1 | Out-Null
        Start-Sleep -Seconds 3
        & $script:adbExe start-server 2>&1 | Out-Null
        Start-Sleep -Seconds 5
        Add-TimelineEvent -Event 'adb_restart' -Status 'RECOVERED'
    }
}

function Invoke-AdbStress {
    param([string[]]$Args,[int]$TimeoutSec=30)
    $p=Start-Process -FilePath $script:adbExe -ArgumentList $Args -NoNewWindow -PassThru -RedirectStandardOutput "adb-out.txt" -RedirectStandardError "adb-err.txt"
    if(-not $p.WaitForExit($TimeoutSec*1000)){
        try{$p.Kill()}catch{}
        return [pscustomobject]@{ExitCode=124;Stdout='';Stderr='timeout'}
    }
    return [pscustomobject]@{ExitCode=$p.ExitCode;Stdout=(Get-Content "adb-out.txt" -Raw);Stderr=''}
}

function Get-TotalPssFromText {
    param([string]$Text)
    $m=[regex]::Match($Text,'TOTAL PSS:\s+(\d+)')
    if($m.Success){return [int64]$m.Groups[1].Value}
    $m=[regex]::Match($Text,'\bTOTAL\s+(\d+)')
    if($m.Success){return [int64]$m.Groups[1].Value}
    return $null
}

function Invoke-ApiJsonStress {
    param([string]$Method,[string]$Endpoint,[object]$Body=$null,[hashtable]$Headers=@{})
    $json=$null; if($null -ne $Body){$json=$Body|ConvertTo-Json -Depth 20 -Compress}
    $resp=Invoke-WebRequest -Method $Method -Uri "http://127.0.0.1:8080/api$Endpoint" -Headers $Headers -ContentType 'application/json' -Body $json -TimeoutSec 30 -UseBasicParsing
    if($resp.Content){return $resp.Content|ConvertFrom-Json}; return $null
}

function Ensure-StressAvd {
    param([string]$Name,[string]$Package,[string]$DeviceName,[int]$RamMB=768)
    $avdDir = Join-Path $env:USERPROFILE ".android\avd\$Name.avd"
    if(Test-Path -LiteralPath $avdDir){ Add-TimelineEvent -Event 'avd_reuse' -Avd $Name -Status 'OK'; return $true }
    Add-TimelineEvent -Event 'avd_create_start' -Avd $Name
    $createOut = "no" | & $script:avdManager create avd -n $Name -k $Package -d $DeviceName --force 2>&1
    $createOut | Set-Content -LiteralPath (Join-Path $out "avd-create-$Name.log") -Encoding UTF8
    if(-not (Test-Path -LiteralPath $avdDir)){ Add-TimelineEvent -Event 'avd_create' -Avd $Name -Status 'FAILED'; return $false }
    $configFile=Join-Path $avdDir 'config.ini'
    if(Test-Path -LiteralPath $configFile){
        $content=Get-Content -Raw -LiteralPath $configFile
        if($content -match 'hw\.ramSize=\S+') { $content=$content -replace 'hw\.ramSize=\S+',("hw.ramSize={0}" -f $RamMB) } else { $content += "`nhw.ramSize=$RamMB`n" }
        if($content -notmatch 'hw\.cpu\.ncore='){ $content += "`nhw.cpu.ncore=2`n" } else { $content=$content -replace 'hw\.cpu\.ncore=\d+','hw.cpu.ncore=2' }
        if($content -match 'disk\.dataPartition\.size=\S+') { $content=$content -replace 'disk\.dataPartition\.size=\S+','disk.dataPartition.size=2G' } else { $content += "`ndisk.dataPartition.size=2G`n" }
        if($content -match 'fastboot\.chosenSnapshotFile=\S*') { $content=$content -replace 'fastboot\.chosenSnapshotFile=\S*','fastboot.chosenSnapshotFile=' }
        $content=$content -replace 'hw\.keyboard=\S+','hw.keyboard=yes'
        Set-Content -LiteralPath $configFile -Value $content -Encoding ASCII
    }
    Add-TimelineEvent -Event 'avd_create' -Avd $Name -Status 'OK'
    return $true
}

function Wait-ForBootStress {
    param([string]$Serial,[int]$TimeoutSec=180)
    $deadline=(Get-Date).AddSeconds($TimeoutSec)
    while((Get-Date) -lt $deadline){
        # Ensure ADB is alive before each poll
        $adbCheck = & $script:adbExe devices 2>&1
        if (($adbCheck -join '') -match 'error|cannot connect') {
            Write-Warning "ADB lost during boot wait for $Serial — restarting ADB..."
            & $script:adbExe kill-server 2>&1 | Out-Null
            Start-Sleep -Seconds 3
            & $script:adbExe start-server 2>&1 | Out-Null
            Start-Sleep -Seconds 5

            # After ADB restart, wait for the emulator serial to re-appear before polling boot prop.
            # The emulator process is still running; ADB just needs to re-enumerate it.
            Write-Host "Waiting for $Serial to re-appear in ADB after server restart..."
            $reattachDeadline = (Get-Date).AddSeconds(60)
            $reattached = $false
            while ((Get-Date) -lt $reattachDeadline) {
                $deviceList = & $script:adbExe devices 2>&1
                if (($deviceList -join "`n") -match [regex]::Escape($Serial)) {
                    $reattached = $true
                    break
                }
                Start-Sleep -Seconds 3
            }
            if (-not $reattached) {
                Write-Warning "Serial $Serial did not re-appear within 60s after ADB restart."
                # Continue the outer loop; it will retry or hit the deadline naturally
            }
        }
        $bootLine = (& $script:adbExe -s $Serial shell getprop sys.boot_completed 2>$null | Select-Object -First 1)
        $boot = if($null -ne $bootLine){$bootLine.ToString().Trim()}else{''}
        if($boot -eq '1'){ return $true }
        Start-Sleep -Seconds 5
    }
    return $false
}

function Start-ProvisionStressAvd {
    param([string]$AvdName,[int]$Port,[string]$ApkPath,[int]$RamMB=768)
    $serial="emulator-$Port"
    $deviceDir=Join-Path $out "device-$AvdName"
    New-Item -ItemType Directory -Force -Path $deviceDir | Out-Null

    $memCheck=Test-MemorySafe -MinFreeMB 4000 -Phase "pre_boot_$AvdName"
    if(-not $memCheck.Safe){
        Add-TimelineEvent -Event 'boot_skip' -Avd $AvdName -Serial $serial -Status 'LOW_MEMORY' -Note "free=$($memCheck.FreeMB) MB"
        Write-ContentSafe -Path (Join-Path $deviceDir 'device-summary.json') -Value (
            [pscustomobject]@{avd=$AvdName;serial=$serial;status='BOOT_SKIPPED_LOW_MEMORY';freeRamMB=$memCheck.FreeMB} | ConvertTo-Json
        )
        return $null
    }

    # Ensure ADB is healthy before launching emulator
    Ensure-AdbServer

    Add-TimelineEvent -Event 'boot_start' -Avd $AvdName -Serial $serial
    $emulatorArgs=@('-avd',$AvdName,'-port',"$Port",'-memory',"$RamMB",'-no-window','-no-audio','-no-snapshot-save','-no-snapshot-load','-no-boot-anim','-gpu','guest','-partition-size','2048','-wipe-data')
    $proc=Start-Process -FilePath $script:emulatorExe -ArgumentList $emulatorArgs -WindowStyle Hidden -PassThru

    if(-not (Wait-ForBootStress -Serial $serial -TimeoutSec 180)){
        Add-TimelineEvent -Event 'boot_retry' -Avd $AvdName -Serial $serial -Status 'FIRST_ATTEMPT_FAILED' -Note 'retry with swiftshader_indirect; restarting ADB first'
        try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch {}
        Start-Sleep -Seconds 5

        # Restart ADB before retry to recover from disconnection
        Ensure-AdbServer

        $retryArgs=@('-avd',$AvdName,'-port',"$Port",'-memory',"$RamMB",'-no-window','-no-audio','-no-snapshot-save','-no-snapshot-load','-no-boot-anim','-gpu','swiftshader_indirect','-partition-size','2048','-wipe-data')
        $proc=Start-Process -FilePath $script:emulatorExe -ArgumentList $retryArgs -WindowStyle Hidden -PassThru
        if(-not (Wait-ForBootStress -Serial $serial -TimeoutSec 180)){
            Add-TimelineEvent -Event 'boot' -Avd $AvdName -Serial $serial -Status 'BOOT_FAILED'
            try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch {}
            Write-ContentSafe -Path (Join-Path $deviceDir 'device-summary.json') -Value (
                [pscustomobject]@{avd=$AvdName;serial=$serial;status='BOOT_FAILED';pid=$proc.Id;ramMB=$RamMB;retry='swiftshader_indirect'} | ConvertTo-Json
            )
            return $null
        }
    }

    Add-TimelineEvent -Event 'boot' -Avd $AvdName -Serial $serial -Status 'OK'
    & $script:adbExe -s $serial wait-for-device | Out-Null
    & $script:adbExe -s $serial install -r $ApkPath 2>&1 | Set-Content -LiteralPath (Join-Path $deviceDir 'install-log.txt') -Encoding UTF8
    $owners = & $script:adbExe -s $serial shell dpm list-owners 2>&1
    if(($owners -join "`n") -notmatch 'com.example.mdmapplication'){
        & $script:adbExe -s $serial shell dpm set-device-owner com.example.mdmapplication/.device.MyDeviceAdminReceiver 2>&1 | Set-Content -LiteralPath (Join-Path $deviceDir 'dpm-log.txt') -Encoding UTF8
    } else { 'already owner' | Set-Content -LiteralPath (Join-Path $deviceDir 'dpm-log.txt') -Encoding UTF8 }
    $dpm = & $script:adbExe -s $serial shell dpm list-owners 2>&1
    $dpm | Set-Content -LiteralPath (Join-Path $deviceDir 'dpm-verify.txt') -Encoding UTF8
    $modelLine = (& $script:adbExe -s $serial shell getprop ro.product.model 2>$null | Select-Object -First 1)
    $sdkLine   = (& $script:adbExe -s $serial shell getprop ro.build.version.sdk 2>$null | Select-Object -First 1)
    $model = if($null -ne $modelLine){$modelLine.ToString().Trim()}else{''}
    $sdk   = if($null -ne $sdkLine){$sdkLine.ToString().Trim()}else{''}
    $memInfo = & $script:adbExe -s $serial shell dumpsys meminfo com.example.mdmapplication 2>$null
    $memInfo | Set-Content -LiteralPath (Join-Path $deviceDir 'meminfo-before.txt') -Encoding UTF8
    & $script:adbExe -s $serial logcat -c 2>$null
    $deviceCode = ('stress{0:D2}{1}' -f ([int]($AvdName -replace '\D','')), ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString().Substring(5)))

    Write-ContentSafe -Path (Join-Path $deviceDir 'device-info.json') -Value (
        [pscustomobject]@{avd=$AvdName;serial=$serial;port=$Port;pid=$proc.Id;status='PROVISIONED';model=$model;sdk=$sdk;deviceCode=$deviceCode;dpmStatus=($dpm -join "`n")} | ConvertTo-Json -Depth 8
    )

    Add-TimelineEvent -Event 'provision' -Avd $AvdName -Serial $serial -Status 'OK'
    return [pscustomobject]@{avd=$AvdName;serial=$serial;port=$Port;pid=$proc.Id;status='PROVISIONED';deviceCode=$deviceCode;deviceDir=$deviceDir}
}

function Stop-EmulatorRecord {
    param([object]$Record,[string]$Reason='rolling_cap')
    if($null -eq $Record){return}
    Add-TimelineEvent -Event 'shutdown_start' -Avd $Record.avd -Serial $Record.serial -Note $Reason
    try { & $script:adbExe -s $Record.serial emu kill 2>$null | Out-Null } catch {}
    Start-Sleep -Seconds 5
    Add-TimelineEvent -Event 'shutdown' -Avd $Record.avd -Serial $Record.serial -Status 'REQUESTED' -Note $Reason
}

function Start-MetricsJob {
    param([object[]]$Records,[int]$Minutes)
    Start-Job -Name 'stress-metrics' -ArgumentList ($Records|ConvertTo-Json -Depth 8),$out,$Minutes,$script:adbExe -ScriptBlock {
        param($recordsJson,$outDir,$minutes,$adbExe)
        $records=@($recordsJson|ConvertFrom-Json)
        $deadline=(Get-Date).AddMinutes($minutes)
        $memoryLogPath=Join-Path $outDir 'memory-watchdog-log.jsonl'
        $sample=0
        while((Get-Date) -lt $deadline){
            $sample++
            if($sample -eq 1 -or ($sample % 2) -eq 0){
                $freeMB=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
                $action='NONE'
                $victim=$null
                if($freeMB -lt 2000 -and $records.Count -gt 3){
                    $victim=@($records | Sort-Object @{Expression={ [int](($_.avd -replace '\D','')) }} | Select-Object -Last 1)[0]
                    if($victim -and $victim.serial){ & $adbExe -s $victim.serial emu kill 2>$null | Out-Null; $records=@($records | Where-Object serial -ne $victim.serial); $action='SHUTDOWN_ONE_AVD_LOW_MEMORY' }
                }
                ([pscustomobject]@{timestamp=(Get-Date -Format o);phase='stress_loop';freeRamMB=$freeMB;safe=($freeMB -gt 2000);action=$action;victim=if($victim){$victim.avd}else{$null};activeRecords=$records.Count} | ConvertTo-Json -Compress) | Add-Content -LiteralPath $memoryLogPath -Encoding UTF8
            }
            foreach($r in $records){
                if(-not $r.serial){continue}
                $dir=Join-Path $outDir "device-$($r.avd)"; New-Item -ItemType Directory -Force -Path $dir|Out-Null
                $memText=(& $adbExe -s $r.serial shell dumpsys meminfo com.example.mdmapplication 2>$null) -join "`n"
                $ram=$null; $m=[regex]::Match($memText,'TOTAL PSS:\s+(\d+)'); if($m.Success){$ram=$m.Groups[1].Value}else{$m=[regex]::Match($memText,'\bTOTAL\s+(\d+)'); if($m.Success){$ram=$m.Groups[1].Value}}
                $cpu=((& $adbExe -s $r.serial shell dumpsys cpuinfo 2>$null | Select-String 'com.example.mdmapplication' | Select-Object -First 1) -join '').Trim()
                $jank=((& $adbExe -s $r.serial shell dumpsys gfxinfo com.example.mdmapplication 2>$null | Select-String 'Janky frames' | Select-Object -First 1) -join '').Trim()
                $line=[pscustomobject]@{timestamp=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();serial=$r.serial;ram_kb=$ram;cpu=$cpu;jank=$jank}
                $path=Join-Path $dir 'timeline-metrics.csv'; $exists=Test-Path -LiteralPath $path; $line|Export-Csv -LiteralPath $path -Append:$exists -NoTypeInformation -Force -Encoding UTF8
            }
            Start-Sleep -Seconds 60
        }
    }
}

# ── Environment ───────────────────────────────────────────────────────────────
$script:sdkRoot     = Get-SdkRootPath
$script:adbExe      = Join-Path $sdkRoot 'platform-tools\adb.exe'
$script:emulatorExe = Join-Path $sdkRoot 'emulator\emulator.exe'
$script:avdManager  = Join-Path $sdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
$script:sdkManager  = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'

# Ensure ADB is healthy at startup
Ensure-AdbServer

$cpu   = Get-CimInstance Win32_Processor      | Select-Object -First 1 -Property Name,NumberOfCores,NumberOfLogicalProcessors
$mem   = Get-CimInstance Win32_ComputerSystem | Select-Object -First 1 -Property TotalPhysicalMemory
$osRaw = Get-CimInstance Win32_OperatingSystem
$os    = $osRaw | Select-Object -First 1 -Property Caption,Version,OSArchitecture

$freeRamStartMB         = [math]::Round($osRaw.FreePhysicalMemory / 1024)
$safeFreeMB             = 4000
$availableForAvds       = $freeRamStartMB - $safeFreeMB
$memoryConcurrentMax    = [math]::Min(7,[math]::Max(0,[math]::Floor($availableForAvds / $AvdRamMB)))
$effectiveConcurrentMax = [math]::Min($ConcurrentMax,$memoryConcurrentMax)
$initialMemorySafe      = $effectiveConcurrentMax -ge 3

$memoryBudget = [pscustomobject]@{
    totalRamGB             = [math]::Round($mem.TotalPhysicalMemory/1GB,1)
    freeRamStartMB         = $freeRamStartMB
    avdRamMB               = $AvdRamMB
    safeFreeMB             = $safeFreeMB
    availableForAvdsMB     = $availableForAvds
    memoryConcurrentMax    = $memoryConcurrentMax
    requestedConcurrentMax = $ConcurrentMax
    effectiveConcurrentMax = $effectiveConcurrentMax
    initialMemorySafe      = $initialMemorySafe
    recommendation         = 'Close IntelliJ IDEA, Android Studio, and heavy browsers if free RAM is low.'
}

Add-MemoryWatchdogRecord -Phase 'environment_start' -FreeMB $freeRamStartMB -Safe $initialMemorySafe -Note "effectiveConcurrentMax=$effectiveConcurrentMax; avdRamMB=$AvdRamMB"

$health       = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/health' -TimeoutSec 10 -UseBasicParsing
$existingAvds = @(Get-ChildItem "$env:USERPROFILE\.android\avd" -Filter '*.ini' -ErrorAction SilentlyContinue | ForEach-Object { $_.BaseName })
$batchStrategy = if ($effectiveConcurrentMax -ge 5) {
    "lightweight rolling batch, max $effectiveConcurrentMax concurrent AVDs"
} elseif ($effectiveConcurrentMax -ge 3) {
    "lightweight fallback, max $effectiveConcurrentMax concurrent AVDs due free RAM"
} else {
    'blocked: free RAM below minimum for 3 AVDs'
}

[pscustomobject]@{
    generatedAt            = (Get-Date -Format o)
    cpu                    = $cpu
    memory                 = $mem
    os                     = $os
    backendHealthStatus    = $health.StatusCode
    sdkRoot                = $sdkRoot
    adbVersion             = (& $script:adbExe version | Select-Object -First 2)
    emulatorVersion        = (& $script:emulatorExe -version 2>&1 | Select-Object -First 1)
    existingAvds           = $existingAvds
    requestedDevices       = $DeviceCount
    requestedConcurrentMax = $ConcurrentMax
    avdRamConfigMB         = $AvdRamMB
    memoryBudget           = $memoryBudget
    batchStrategy          = $batchStrategy
} | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $out 'environment.json') -Encoding UTF8

Add-TimelineEvent -Event 'environment' -Status 'OK' -Note $batchStrategy

if(-not $initialMemorySafe){
    throw "BLOCKED_LOW_MEMORY: free RAM $freeRamStartMB MB allows only $effectiveConcurrentMax AVDs at ${AvdRamMB}MB with ${safeFreeMB}MB safety reserve."
}

# ── Build ─────────────────────────────────────────────────────────────────────
if(-not $SkipBuild -or -not (Test-Path 'app\build\outputs\apk\debug\app-debug.apk')){
    & .\gradlew.bat :app:assembleDebug
}
$apkPath = (Get-Item 'app\build\outputs\apk\debug\app-debug.apk').FullName

# ── SDK image ─────────────────────────────────────────────────────────────────
$systemImage = "system-images;android-$Api;google_apis;x86_64"
if(Test-Path -LiteralPath $script:sdkManager){
    & $script:sdkManager --install $systemImage | Out-File (Join-Path $out 'sdkmanager.log')
}

# ── AVD names ─────────────────────────────────────────────────────────────────
$avdNames = @(1..$DeviceCount | ForEach-Object { 'MDM_STRESS_{0:D2}_API36' -f $_ })

$created = 0
if ($SkipAvdCreate) {
    foreach ($name in $avdNames) {
        $avdDir = Join-Path $env:USERPROFILE ".android\avd\$name.avd"
        if (Test-Path -LiteralPath $avdDir) {
            $created++
            Add-TimelineEvent -Event 'avd_reuse' -Avd $name -Status 'OK'
        } else {
            Add-TimelineEvent -Event 'avd_missing' -Avd $name -Status 'SKIPPED' -Note 'SkipAvdCreate set but AVD not found'
        }
    }
} else {
    foreach($name in $avdNames){
        if(Ensure-StressAvd -Name $name -Package $systemImage -DeviceName 'pixel_6' -RamMB $AvdRamMB){ $created++ }
    }
}

# ── Boot & provision loop ─────────────────────────────────────────────────────
$provisioned = New-Object System.Collections.Generic.List[object]
$running     = New-Object System.Collections.Generic.List[object]
$maxOnline   = [Math]::Max(3,[Math]::Min($effectiveConcurrentMax,7))
$basePort    = 5554

for($i=0; $i -lt $avdNames.Count; $i++){
    while($running.Count -ge $maxOnline){
        $old = $running[0]
        Stop-EmulatorRecord -Record $old -Reason 'rolling online cap'
        $running.RemoveAt(0)
    }
    $port    = $basePort + ($i*2)
    $memGate = Test-MemorySafe -MinFreeMB 4000 -Phase "boot_gate_$($avdNames[$i])"
    if(-not $memGate.Safe){
        Add-TimelineEvent -Event 'boot_gate_stop' -Avd $avdNames[$i] -Status 'LOW_MEMORY' -Note "free=$($memGate.FreeMB) MB; provisioned=$($provisioned.Count)"
        if($provisioned.Count -lt 3){ throw "BLOCKED_LOW_MEMORY: only $($provisioned.Count) AVD provisioned before memory gate stopped boot." }
        break
    }
    $rec = Start-ProvisionStressAvd -AvdName $avdNames[$i] -Port $port -ApkPath $apkPath -RamMB $AvdRamMB
    if($rec){ $provisioned.Add($rec)|Out-Null; $running.Add($rec)|Out-Null }
    Start-Sleep -Seconds 30
}

$concurrentActual = $running.Count

# ── Save device map ───────────────────────────────────────────────────────────
$stressDevices = $provisioned | Select-Object avd,serial,deviceCode
$stressDevices | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $out 'avd-device-map.json') -Encoding UTF8

$deviceFile = Join-Path