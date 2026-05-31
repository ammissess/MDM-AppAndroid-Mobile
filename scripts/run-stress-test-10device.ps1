[CmdletBinding()]
param(
    [int]$DeviceCount = 10,
    [int]$ConcurrentMax = 5,
    [int]$DurationMinutes = 30,
    [int]$Api = 36,
    [int]$CommandIntervalSec = 20,
    [int]$AvdRamMB = 768,
    [string]$OutputDir = "benchmark-results\stress-test-10device",
    [switch]$SkipBuild
)

$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
$out = Join-Path $repoRoot $OutputDir
New-Item -ItemType Directory -Force -Path $out | Out-Null
$timeline = New-Object System.Collections.Generic.List[object]
$memoryLog = New-Object System.Collections.Generic.List[object]
function Add-TimelineEvent { param([string]$Event,[string]$Avd='',[string]$Serial='',[string]$Status='',[string]$Note='') $script:timeline.Add([pscustomobject]@{timestamp=(Get-Date -Format o);event=$Event;avd=$Avd;serial=$Serial;status=$Status;note=$Note}) | Out-Null }
function Add-MemoryWatchdogRecord { param([string]$Phase,[int]$FreeMB,[bool]$Safe,[string]$Action='',[string]$Note='') $script:memoryLog.Add([pscustomobject]@{timestamp=(Get-Date -Format o);phase=$Phase;freeRamMB=$FreeMB;safe=$Safe;action=$Action;note=$Note}) | Out-Null }
function Test-MemorySafe { param([int]$MinFreeMB=4000,[string]$Phase='check') $freeMB=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024); $safe=$freeMB -gt $MinFreeMB; if(-not $safe){Write-Warning "LOW MEMORY: Free=$freeMB MB < threshold=$MinFreeMB MB"}; Add-MemoryWatchdogRecord -Phase $Phase -FreeMB $freeMB -Safe $safe -Note "threshold=$MinFreeMB MB"; return [pscustomobject]@{FreeMB=$freeMB;Safe=$safe} }
function New-AuthHeaderStress { param([string]$Token) @{ Authorization = "Bearer $Token" } }
function Get-SdkRootPath { if($env:ANDROID_HOME){$env:ANDROID_HOME}elseif($env:ANDROID_SDK_ROOT){$env:ANDROID_SDK_ROOT}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'} }
function Invoke-AdbStress { param([string[]]$Args,[int]$TimeoutSec=30) $p=Start-Process -FilePath $script:adbExe -ArgumentList $Args -NoNewWindow -PassThru -RedirectStandardOutput "adb-out.txt" -RedirectStandardError "adb-err.txt"; if(-not $p.WaitForExit($TimeoutSec*1000)){try{$p.Kill()}catch{}; return [pscustomobject]@{ExitCode=124;Stdout='';Stderr='timeout'}}; return [pscustomobject]@{ExitCode=$p.ExitCode;Stdout=(Get-Content "adb-out.txt" -Raw);Stderr=''} }
function Get-TotalPssFromText { param([string]$Text) $m=[regex]::Match($Text,'TOTAL PSS:\s+(\d+)'); if($m.Success){return [int64]$m.Groups[1].Value}; $m=[regex]::Match($Text,'\bTOTAL\s+(\d+)'); if($m.Success){return [int64]$m.Groups[1].Value}; return $null }
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
        $bootLine = (& $script:adbExe -s $Serial shell getprop sys.boot_completed 2>$null | Select-Object -First 1)
        $boot = if($null -ne $bootLine){$bootLine.ToString().Trim()}else{''}
        if($boot -eq '1'){ return $true }
        Start-Sleep -Seconds 5
    }
    return $false
}
function Start-ProvisionStressAvd {
    param([string]$AvdName,[int]$Port,[string]$ApkPath,[int]$RamMB=768)
    $serial="emulator-$Port"; $deviceDir=Join-Path $out "device-$AvdName"; New-Item -ItemType Directory -Force -Path $deviceDir | Out-Null
    $memCheck=Test-MemorySafe -MinFreeMB 4000 -Phase "pre_boot_$AvdName"
    if(-not $memCheck.Safe){
        Add-TimelineEvent -Event 'boot_skip' -Avd $AvdName -Serial $serial -Status 'LOW_MEMORY' -Note "free=$($memCheck.FreeMB) MB"
        [pscustomobject]@{avd=$AvdName;serial=$serial;status='BOOT_SKIPPED_LOW_MEMORY';freeRamMB=$memCheck.FreeMB}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $deviceDir 'device-summary.json') -Encoding UTF8
        return $null
    }
    Add-TimelineEvent -Event 'boot_start' -Avd $AvdName -Serial $serial
    $args=@('-avd',$AvdName,'-port',"$Port",'-memory',"$RamMB",'-no-window','-no-audio','-no-snapshot-save','-no-snapshot-load','-no-boot-anim','-gpu','guest','-partition-size','2048','-wipe-data')
    $proc=Start-Process -FilePath $script:emulatorExe -ArgumentList $args -WindowStyle Hidden -PassThru
    if(-not (Wait-ForBootStress -Serial $serial -TimeoutSec 180)){
        Add-TimelineEvent -Event 'boot_retry' -Avd $AvdName -Serial $serial -Status 'FIRST_ATTEMPT_FAILED' -Note 'retry with swiftshader_indirect at same RAM budget'
        try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch {}
        Start-Sleep -Seconds 5
        $retryArgs=@('-avd',$AvdName,'-port',"$Port",'-memory',"$RamMB",'-no-window','-no-audio','-no-snapshot-save','-no-snapshot-load','-no-boot-anim','-gpu','swiftshader_indirect','-partition-size','2048','-wipe-data')
        $proc=Start-Process -FilePath $script:emulatorExe -ArgumentList $retryArgs -WindowStyle Hidden -PassThru
        if(-not (Wait-ForBootStress -Serial $serial -TimeoutSec 180)){
            Add-TimelineEvent -Event 'boot' -Avd $AvdName -Serial $serial -Status 'BOOT_FAILED'
            try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch {}
            [pscustomobject]@{avd=$AvdName;serial=$serial;status='BOOT_FAILED';pid=$proc.Id;ramMB=$RamMB;retry='swiftshader_indirect'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $deviceDir 'device-summary.json') -Encoding UTF8
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
    $sdkLine = (& $script:adbExe -s $serial shell getprop ro.build.version.sdk 2>$null | Select-Object -First 1)
    $model = if($null -ne $modelLine){$modelLine.ToString().Trim()}else{''}
    $sdk = if($null -ne $sdkLine){$sdkLine.ToString().Trim()}else{''}
    $mem = & $script:adbExe -s $serial shell dumpsys meminfo com.example.mdmapplication 2>$null
    $mem | Set-Content -LiteralPath (Join-Path $deviceDir 'meminfo-before.txt') -Encoding UTF8
    & $script:adbExe -s $serial logcat -c 2>$null
    $deviceCode = ('stress{0:D2}{1}' -f ([int]($AvdName -replace '\D','')), ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString().Substring(5)))
    [pscustomobject]@{avd=$AvdName;serial=$serial;port=$Port;pid=$proc.Id;status='PROVISIONED';model=$model;sdk=$sdk;deviceCode=$deviceCode;dpmStatus=($dpm -join "`n")} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $deviceDir 'device-info.json') -Encoding UTF8
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
# Environment
$script:sdkRoot=Get-SdkRootPath
$script:adbExe=Join-Path $sdkRoot 'platform-tools\adb.exe'
$script:emulatorExe=Join-Path $sdkRoot 'emulator\emulator.exe'
$script:avdManager=Join-Path $sdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
$script:sdkManager=Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
$cpu=Get-CimInstance Win32_Processor | Select-Object -First 1 Name,NumberOfCores,NumberOfLogicalProcessors
$mem=Get-CimInstance Win32_ComputerSystem | Select-Object -First 1 TotalPhysicalMemory
$osRaw=Get-CimInstance Win32_OperatingSystem
$os=$osRaw | Select-Object -First 1 Caption,Version,OSArchitecture
$freeRamStartMB=[math]::Round($osRaw.FreePhysicalMemory / 1024)
$safeFreeMB=4000
$availableForAvds=$freeRamStartMB - $safeFreeMB
$memoryConcurrentMax=[math]::Min(7,[math]::Max(0,[math]::Floor($availableForAvds / $AvdRamMB)))
$effectiveConcurrentMax=[math]::Min($ConcurrentMax,$memoryConcurrentMax)
$initialMemorySafe=$effectiveConcurrentMax -ge 3
$memoryBudget=[pscustomobject]@{totalRamGB=[math]::Round($mem.TotalPhysicalMemory/1GB,1);freeRamStartMB=$freeRamStartMB;avdRamMB=$AvdRamMB;safeFreeMB=$safeFreeMB;availableForAvdsMB=$availableForAvds;memoryConcurrentMax=$memoryConcurrentMax;requestedConcurrentMax=$ConcurrentMax;effectiveConcurrentMax=$effectiveConcurrentMax;initialMemorySafe=$initialMemorySafe;recommendation='Close IntelliJ IDEA, Android Studio, and heavy browsers if free RAM is low.'}
Add-MemoryWatchdogRecord -Phase 'environment_start' -FreeMB $freeRamStartMB -Safe $initialMemorySafe -Note "effectiveConcurrentMax=$effectiveConcurrentMax; avdRamMB=$AvdRamMB"
$health=Invoke-WebRequest -Uri 'http://127.0.0.1:8080/health' -TimeoutSec 10 -UseBasicParsing
$existingAvds=@(Get-ChildItem "$env:USERPROFILE\.android\avd" -Filter '*.ini' -ErrorAction SilentlyContinue | ForEach-Object { $_.BaseName })
$batchStrategy = if($effectiveConcurrentMax -ge 5){"lightweight rolling batch, max $effectiveConcurrentMax concurrent AVDs"}elseif($effectiveConcurrentMax -ge 3){"lightweight fallback, max $effectiveConcurrentMax concurrent AVDs due free RAM"}else{'blocked: free RAM below minimum for 3 AVDs'}
[pscustomobject]@{generatedAt=(Get-Date -Format o);cpu=$cpu;memory=$mem;os=$os;backendHealthStatus=$health.StatusCode;sdkRoot=$sdkRoot;adbVersion=(& $adbExe version | Select-Object -First 2);emulatorVersion=(& $emulatorExe -version 2>&1 | Select-Object -First 1);existingAvds=$existingAvds;requestedDevices=$DeviceCount;requestedConcurrentMax=$ConcurrentMax;avdRamConfigMB=$AvdRamMB;memoryBudget=$memoryBudget;batchStrategy=$batchStrategy} | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $out 'environment.json') -Encoding UTF8
Add-TimelineEvent -Event 'environment' -Status 'OK' -Note $batchStrategy
if(-not $initialMemorySafe){ throw "BLOCKED_LOW_MEMORY: free RAM $freeRamStartMB MB allows only $effectiveConcurrentMax AVDs at ${AvdRamMB}MB with ${safeFreeMB}MB safety reserve." }
if(-not $SkipBuild -or -not (Test-Path 'app\build\outputs\apk\debug\app-debug.apk')){ & .\gradlew.bat :app:assembleDebug }
$apkPath=(Get-Item 'app\build\outputs\apk\debug\app-debug.apk').FullName
$systemImage="system-images;android-$Api;google_apis;x86_64"
if(Test-Path -LiteralPath $sdkManager){ & $sdkManager --install $systemImage | Out-File (Join-Path $out 'sdkmanager.log') }
$avdNames=@(1..$DeviceCount | ForEach-Object { 'MDM_STRESS_{0:D2}_API36' -f $_ })
$created=0; foreach($name in $avdNames){ if(Ensure-StressAvd -Name $name -Package $systemImage -DeviceName 'pixel_6' -RamMB $AvdRamMB){ $created++ } }
$provisioned=New-Object System.Collections.Generic.List[object]
$running=New-Object System.Collections.Generic.List[object]
$maxOnline=[Math]::Max(3,[Math]::Min($effectiveConcurrentMax,7))
$basePort=5554
for($i=0;$i -lt $avdNames.Count;$i++){
    while($running.Count -ge $maxOnline){ $old=$running[0]; Stop-EmulatorRecord -Record $old -Reason 'rolling online cap'; $running.RemoveAt(0) }
    $port=$basePort + ($i*2)
    $memGate=Test-MemorySafe -MinFreeMB 4000 -Phase "boot_gate_$($avdNames[$i])"
    if(-not $memGate.Safe){
        Add-TimelineEvent -Event 'boot_gate_stop' -Avd $avdNames[$i] -Status 'LOW_MEMORY' -Note "free=$($memGate.FreeMB) MB; provisioned=$($provisioned.Count)"
        if($provisioned.Count -lt 3){ throw "BLOCKED_LOW_MEMORY: only $($provisioned.Count) AVD provisioned before memory gate stopped boot." }
        break
    }
    $rec=Start-ProvisionStressAvd -AvdName $avdNames[$i] -Port $port -ApkPath $apkPath -RamMB $AvdRamMB
    if($rec){ $provisioned.Add($rec)|Out-Null; $running.Add($rec)|Out-Null }
    Start-Sleep -Seconds 30
}
$concurrentActual=$running.Count
$stressDevices=$provisioned | Select-Object avd,serial,deviceCode
$stressDevices | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $out 'avd-device-map.json') -Encoding UTF8
$deviceFile=Join-Path $out 'stress-devices.json'
if($provisioned.Count -gt 0){
    $metricsRecords=@($running | ForEach-Object { $_ })
    $metricsJob=Start-MetricsJob -Records $metricsRecords -Minutes $DurationMinutes
    $driverScript=Join-Path $PSScriptRoot 'run-api-stress-driver.ps1'
    $deviceCodes=@($stressDevices | ForEach-Object { $_.deviceCode } | Where-Object { $_ })
    if($deviceCodes.Count -gt 0){
        $driverJob=Start-Job -Name 'api-stress-driver' -ArgumentList $driverScript,$out,$deviceCodes,$DurationMinutes,$CommandIntervalSec -ScriptBlock { param($scriptPath,$outDir,$codes,$minutes,$interval) & $scriptPath -DeviceCodes $codes -DurationMinutes $minutes -CommandIntervalSec $interval -OutputDir $outDir }
        $waitStart=Get-Date
        while(-not (Test-Path -LiteralPath $deviceFile) -and ((Get-Date)-$waitStart).TotalSeconds -lt 120){ Start-Sleep -Seconds 2 }
        $faultDevices=@()
        if(Test-Path -LiteralPath $deviceFile){
            $registered=@(Get-Content -Raw -LiteralPath $deviceFile | ConvertFrom-Json)
            foreach($reg in $registered){ $map=@($provisioned|Where-Object deviceCode -eq $reg.deviceCode|Select-Object -First 1)[0]; $faultDevices += [pscustomobject]@{avd=if($map){$map.avd}else{$reg.deviceCode};serial=if($map){$map.serial}else{$null};deviceCode=$reg.deviceCode;deviceId=$reg.deviceId} }
            $runningSerials=@($running|ForEach-Object{$_.serial})
            $faultDevices = @($faultDevices | Sort-Object @{Expression={ if($runningSerials -contains $_.serial){0}else{1} }})
            $faultFile=Join-Path $out 'fault-devices.json'; $faultDevices|ConvertTo-Json -Depth 10|Set-Content -LiteralPath $faultFile -Encoding UTF8
            $faultScript=Join-Path $PSScriptRoot 'run-fault-injection.ps1'
            $faultDelay=[Math]::Min(600,[Math]::Max(60,[int]($DurationMinutes*60/3)))
            Start-Sleep -Seconds $faultDelay
            & $faultScript -DeviceFile $faultFile -OutputDir $out | Tee-Object -FilePath (Join-Path $out 'fault-injection-console.log')
        }
        Wait-Job $driverJob | Out-Null
        Receive-Job $driverJob -ErrorAction Continue | Tee-Object -FilePath (Join-Path $out 'api-stress-driver.log')
        Remove-Job $driverJob -Force
    } else {
        Add-TimelineEvent -Event 'api_driver_skip' -Status 'NO_DEVICE_CODES'
    }
    Wait-Job $metricsJob | Out-Null
    Receive-Job $metricsJob -ErrorAction Continue | Out-File (Join-Path $out 'metrics-job.log')
    Remove-Job $metricsJob -Force
} else {
    Add-TimelineEvent -Event 'stress_skip' -Status 'NO_PROVISIONED_DEVICES' -Note 'No API stress or fault injection executed.'
}
# Final per-device evidence for currently running emulators.
foreach($rec in $provisioned){
    $dir=Join-Path $out "device-$($rec.avd)"; New-Item -ItemType Directory -Force -Path $dir|Out-Null
    if($running | Where-Object serial -eq $rec.serial){
        (& $adbExe -s $rec.serial shell dumpsys meminfo com.example.mdmapplication 2>$null) | Set-Content -LiteralPath (Join-Path $dir 'meminfo-after.txt') -Encoding UTF8
        (& $adbExe -s $rec.serial shell dumpsys cpuinfo 2>$null) | Set-Content -LiteralPath (Join-Path $dir 'cpuinfo-after.txt') -Encoding UTF8
        (& $adbExe -s $rec.serial shell dumpsys gfxinfo com.example.mdmapplication 2>$null) | Set-Content -LiteralPath (Join-Path $dir 'gfxinfo-after.txt') -Encoding UTF8
        (& $adbExe -s $rec.serial logcat -d -v time 2>$null) | Set-Content -LiteralPath (Join-Path $dir 'logcat-full.txt') -Encoding UTF8
    }
    $beforePath=Join-Path $dir 'meminfo-before.txt'; $afterPath=Join-Path $dir 'meminfo-after.txt'
    $before=if(Test-Path $beforePath){Get-TotalPssFromText (Get-Content -Raw -LiteralPath $beforePath)}else{$null}
    $after=if(Test-Path $afterPath){Get-TotalPssFromText (Get-Content -Raw -LiteralPath $afterPath)}else{$null}
    $cmdPath=Join-Path $dir 'commands-history.json'; $polPath=Join-Path $dir 'policy-state-history.json'; $logPath=Join-Path $dir 'logcat-full.txt'
    $cmds=if(Test-Path $cmdPath){@(Get-Content -Raw -LiteralPath $cmdPath|ConvertFrom-Json)}else{@()}; $pols=if(Test-Path $polPath){@(Get-Content -Raw -LiteralPath $polPath|ConvertFrom-Json)}else{@()}
    $log=if(Test-Path $logPath){Get-Content -Raw -LiteralPath $logPath}else{''}
    $crash=([regex]::Matches($log,'FATAL EXCEPTION|AndroidRuntime')).Count; $anr=([regex]::Matches($log,'ANR in|Application Not Responding')).Count
    [pscustomobject]@{avd=$rec.avd;serial=$rec.serial;deviceCode=$rec.deviceCode;status=$rec.status;totalCommands=$cmds.Count;commandSuccess=($cmds|Where-Object status -eq 'SUCCESS').Count;commandFailed=($cmds|Where-Object status -ne 'SUCCESS').Count;policyReports=$pols.Count;policySuccess=($pols|Where-Object success).Count;crashes=$crash;anrs=$anr;ramBeforeKb=$before;ramAfterKb=$after;ramDeltaKb=if($null-ne$before -and $null-ne$after){$after-$before}else{$null}} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $dir 'device-summary.json') -Encoding UTF8
}
# Aggregate
$timeline | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $out 'batch-timeline.json') -Encoding UTF8
$freeRamEndMB=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
Add-MemoryWatchdogRecord -Phase 'aggregate_end' -FreeMB $freeRamEndMB -Safe ($freeRamEndMB -gt 2000) -Note 'final free RAM snapshot'
$memoryJsonl=Join-Path $out 'memory-watchdog-log.jsonl'
$memorySamples=@($memoryLog)
if(Test-Path -LiteralPath $memoryJsonl){ $memorySamples += @(Get-Content -LiteralPath $memoryJsonl | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json }) }
$memorySamples | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $out 'memory-watchdog-log.json') -Encoding UTF8
$deviceSummaries=@(Get-ChildItem -Path $out -Filter 'device-summary.json' -Recurse | ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName | ConvertFrom-Json })
$commands=@(); if(Test-Path (Join-Path $out 'command-lifecycle-all.json')){$commands=@(Get-Content -Raw -LiteralPath (Join-Path $out 'command-lifecycle-all.json')|ConvertFrom-Json)}
$policies=@(); if(Test-Path (Join-Path $out 'policy-state-all.json')){$policies=@(Get-Content -Raw -LiteralPath (Join-Path $out 'policy-state-all.json')|ConvertFrom-Json)}
$fails=@(); if(Test-Path (Join-Path $out 'fail-cases-report.json')){$fails=@((Get-Content -Raw -LiteralPath (Join-Path $out 'fail-cases-report.json')|ConvertFrom-Json).failCases)}else{[pscustomobject]@{testDuration="$DurationMinutes minutes";totalDevices=$DeviceCount;concurrentMax=$concurrentActual;totalCommandsSent=$commands.Count;totalCommandsSuccess=($commands|Where-Object status -eq 'SUCCESS').Count;totalCommandsFailed=($commands|Where-Object status -ne 'SUCCESS').Count;totalPolicyReports=$policies.Count;totalFaultInjections=0;failCases=@();summary=[pscustomobject]@{totalFails=0;bySeverity=[pscustomobject]@{CRITICAL=0;HIGH=0;MEDIUM=0;LOW=0};byCategory=[pscustomobject]@{}}}|ConvertTo-Json -Depth 20|Set-Content -LiteralPath (Join-Path $out 'fail-cases-report.json') -Encoding UTF8}
$faults=@(); if(Test-Path (Join-Path $out 'fault-injection-summary.json')){$faults=@(Get-Content -Raw -LiteralPath (Join-Path $out 'fault-injection-summary.json')|ConvertFrom-Json)}
$avgBefore=($deviceSummaries|Where-Object ramBeforeKb|Measure-Object ramBeforeKb -Average).Average; $avgAfter=($deviceSummaries|Where-Object ramAfterKb|Measure-Object ramAfterKb -Average).Average
$registeredBackend=if(Test-Path -LiteralPath $deviceFile){(@(Get-Content -Raw -LiteralPath $deviceFile|ConvertFrom-Json)).Count}else{0}
$freeRamMinMB=if($memorySamples.Count){(@($memorySamples | Measure-Object freeRamMB -Minimum).Minimum)}else{$null}
$stability=[pscustomobject]@{durationMinutes=$DurationMinutes;devicesRequested=$DeviceCount;avdsCreated=$created;avdsProvisioned=$provisioned.Count;registeredBackend=$registeredBackend;concurrentMaxActual=$concurrentActual;avdRamMB=$AvdRamMB;freeRamStartMB=$freeRamStartMB;freeRamEndMB=$freeRamEndMB;freeRamMinMB=$freeRamMinMB;totalCommands=$commands.Count;commandSuccess=($commands|Where-Object status -eq 'SUCCESS').Count;commandFailed=($commands|Where-Object status -ne 'SUCCESS').Count;policyReports=$policies.Count;policySuccess=($policies|Where-Object success).Count;policyFailed=($policies|Where-Object {-not $_.success}).Count;crashCount=($deviceSummaries|Measure-Object crashes -Sum).Sum;anrCount=($deviceSummaries|Measure-Object anrs -Sum).Sum;ramAvgBeforeKb=[Math]::Round($avgBefore,2);ramAvgAfterKb=[Math]::Round($avgAfter,2);ramAvgDeltaKb=if($avgBefore -and $avgAfter){[Math]::Round($avgAfter-$avgBefore,2)}else{$null};faultInjections=$faults.Count;failCases=$fails.Count}
$stability|ConvertTo-Json -Depth 20|Set-Content -LiteralPath (Join-Path $out 'stability-aggregate.json') -Encoding UTF8
# API percentiles
$apiRows=@(); if(Test-Path (Join-Path $out 'api-performance-aggregate.csv')){$apiRows=Import-Csv -LiteralPath (Join-Path $out 'api-performance-aggregate.csv')}
function Get-Percentile { param([double[]]$Values,[double]$P) if(-not $Values -or $Values.Count -eq 0){return ''}; $s=@($Values|Sort-Object); $idx=[Math]::Ceiling(($P/100)*$s.Count)-1; if($idx -lt 0){$idx=0}; if($idx -ge $s.Count){$idx=$s.Count-1}; return [Math]::Round($s[$idx],2) }
$apiSummary=@(); foreach($flow in @('admin_login','device_register_setup','device_config_current','device_poll_with_command','device_ack_command','device_policy_state')){ $rows=@($apiRows|Where-Object flow -eq $flow); $vals=[double[]]@($rows|ForEach-Object{[double]$_.elapsedMs}); $err=if($rows.Count){[Math]::Round((@($rows|Where-Object success -ne 'True').Count/$rows.Count)*100,2)}else{''}; $apiSummary += [pscustomobject]@{flow=$flow;min=Get-Percentile $vals 0;p50=Get-Percentile $vals 50;p90=Get-Percentile $vals 90;max=Get-Percentile $vals 100;errorRate=$err;count=$rows.Count} }
# Markdown summary
$status=if($provisioned.Count -eq 0){'STRESS_TEST_BLOCKED'}elseif($DurationMinutes -lt 30 -or $concurrentActual -lt 3){'STRESS_TEST_PARTIAL'}elseif($fails.Count -gt 0){'STRESS_TEST_COMPLETE_WITH_FAILS'}elseif($concurrentActual -ge 5){'STRESS_TEST_COMPLETE_ALL_PASS'}else{'STRESS_TEST_PARTIAL'}
$severity=@{CRITICAL=0;HIGH=0;MEDIUM=0;LOW=0}; foreach($fc in $fails){if($severity.ContainsKey($fc.severity)){$severity[$fc.severity]++}}
$md = @()
$md += '# MDM Stress Test 10 Device Summary'
$md += ''
$md += ("Final status: ``{0}``" -f $status)
$md += ''
$md += '## Bảng A: Môi trường thử nghiệm'
$md += '| Thành phần | Thông số | Giá trị |'
$md += '|---|---|---|'
$md += "| Máy chạy thử nghiệm | CPU | $($cpu.Name), $($cpu.NumberOfCores) cores / $($cpu.NumberOfLogicalProcessors) LP |"
$md += "| | RAM | $([Math]::Round($mem.TotalPhysicalMemory/1GB,1)) GB |"
$md += "| | Hệ điều hành | $($os.Caption) $($os.Version), $($os.OSArchitecture) |"
$md += "| Android Emulator | API Level | $Api |"
$md += "| | Số AVD tham gia | $($provisioned.Count) / $DeviceCount provisioned |"
$md += "| | Số AVD đồng thời tối đa thực tế | $concurrentActual |"
$md += "| | RAM mỗi AVD | $AvdRamMB MB |"
$md += '| | CPU mỗi AVD | 2 cores |'
$md += '| Android Agent | Package | com.example.mdmapplication, Device Owner attempted/verified per device |'
$md += '| Backend | Framework | Kotlin Ktor 3.4.0, Netty, Exposed 0.61.0 |'
$md += '| | Địa chỉ | http://127.0.0.1:8080, /health HTTP 200 |'
$md += '| Web Dashboard | Framework | React 18.3.1 + TypeScript + Vite 7.1.3 |'
$md += '| CSDL | | MySQL local mdmappbasic |'
$md += '| Công cụ đo | | PowerShell stress driver, ADB, logcat |'
$md += ''
$md += '## Bảng B: Stability test đa thiết bị'
$md += '| Chỉ số | Giá trị |'; $md += '|---|---|'
$md += "| Thời gian chạy liên tục | $DurationMinutes phút |"
$md += "| Số thiết bị tham gia | $DeviceCount requested; $($provisioned.Count) AVD provisioned; $($stability.registeredBackend) backend devices |"
$md += "| Số thiết bị đồng thời tối đa | $concurrentActual |"
$md += "| RAM mỗi AVD | $AvdRamMB MB |"
$md += "| Tổng số command đã gửi | $($stability.totalCommands) |"
$md += "| Command SUCCESS | $($stability.commandSuccess) |"
$md += "| Command FAILED | $($stability.commandFailed) |"
$rate=if($stability.totalCommands){[Math]::Round(($stability.commandSuccess/$stability.totalCommands)*100,2)}else{0}
$md += "| Tỷ lệ command thành công (%) | $rate |"
$md += "| Số lần report policy-state | $($stability.policyReports) |"
$md += "| Policy-state thành công | $($stability.policySuccess) |"
$md += "| Crash count (tổng) | $($stability.crashCount) |"
$md += "| ANR count (tổng) | $($stability.anrCount) |"
$md += "| RAM trung bình ban đầu (KB) | $($stability.ramAvgBeforeKb) |"
$md += "| RAM trung bình cuối (KB) | $($stability.ramAvgAfterKb) |"
$md += "| Mức tăng RAM trung bình (KB) | $($stability.ramAvgDeltaKb) |"
$md += "| Số fault injection thực hiện | $($stability.faultInjections) / 10 |"
$md += "| Số fail case tìm thấy | $($stability.failCases) |"
$md += "| Free RAM hệ thống lúc bắt đầu (MB) | $($stability.freeRamStartMB) |"
$md += "| Free RAM hệ thống thấp nhất ghi nhận (MB) | $($stability.freeRamMinMB) |"
$md += "| Free RAM hệ thống lúc kết thúc (MB) | $($stability.freeRamEndMB) |"
$md += '| Ghi chú | Stress cục bộ trên emulator; nếu ADB/offline xảy ra được ghi nhận là fail/partial evidence |'
$md += ''
$md += '## Bảng C: Backend API response time dưới tải đa thiết bị'
$md += '| STT | API / Luồng đo | Method | Endpoint | Min (ms) | P50 (ms) | P90 (ms) | Max (ms) | Error rate | Nhận xét |'
$md += '|---|---|---|---|---:|---:|---:|---:|---:|---|'
$labels=@(@('admin_login','Đăng nhập','POST','/api/auth/login'),@('device_register_setup','Đăng ký thiết bị','POST','/api/device/register'),@('device_config_current','Lấy cấu hình','GET','/api/device/config/current'),@('device_poll_no_command','Poll (không lệnh)','POST','/api/device/poll'),@('device_poll_with_command','Poll (có lệnh)','POST','/api/device/poll'),@('device_ack_command','ACK command','POST','/api/device/ack'),@('device_policy_state','Policy-state','POST','/api/device/policy-state'))
$idx=0; foreach($l in $labels){$idx++; $s=@($apiSummary|Where-Object flow -eq $l[0]|Select-Object -First 1)[0]; $md += "| $idx | $($l[1]) | $($l[2]) | $($l[3]) | $($s.min) | $($s.p50) | $($s.p90) | $($s.max) | $($s.errorRate)% | count=$($s.count) |"}
$md += ''
$md += '## Bảng D: Fault injection results'
$md += '| ID | Fault type | Device | Kết quả mong đợi | Kết quả thực tế | Pass/Fail | Ghi chú |'
$md += '|---|---|---|---|---|---|---|'
foreach($f in $faults){ $md += "| $($f.id) | $($f.type) | $($f.device) | $($f.expected) | $($f.actual) | $(if($f.pass){'PASS'}else{'FAIL'}) | $($f.commandId) |" }
$md += ''
$md += '## Bảng E: Fail cases tìm thấy'
$md += '| ID | Category | Severity | Device | Mô tả | Root cause | Đề xuất |'
$md += '|---|---|---|---|---|---|---|'
if($fails.Count -eq 0){$md += '| - | - | - | - | Không phát hiện fail case trong phạm vi stress test. Cần tăng cường độ hoặc mở rộng kịch bản. | - | - |'} else { foreach($fc in $fails){$md += "| $($fc.id) | $($fc.category) | $($fc.severity) | $($fc.device) | $($fc.description) | $($fc.rootCause) | $($fc.recommendation) |"} }
$md += ''
$md += '## So sánh với phiên trước'
$md += '| Metrics | Phiên trước (1 device / 11 phút) | Phiên này |'
$md += '|---|---|---|'
$md += "| Devices | 1 | $DeviceCount requested / $($provisioned.Count) AVD provisioned / $($stability.registeredBackend) backend devices |"
$md += "| Duration | 11 phút | $DurationMinutes phút |"
$md += "| AVD RAM | 2048 MB | $AvdRamMB MB |"
$md += "| Commands | 3 | $($stability.totalCommands) |"
$md += "| Command interval | 60s | $CommandIntervalSec s |"
$md += "| Fail cases | 0 | $($stability.failCases) |"
$md += "| Fault injections | 0 | $($stability.faultInjections) |"
$loginSummary=@($apiSummary|Where-Object flow -eq 'admin_login'|Select-Object -First 1)[0]
$pollSummary=@($apiSummary|Where-Object flow -eq 'device_poll_with_command'|Select-Object -First 1)[0]
$md += "| API avg login (ms) | 233.42 | p50=$($loginSummary.p50), p90=$($loginSummary.p90) |"
$md += "| API avg poll (ms) | 15.2 | p50=$($pollSummary.p50), p90=$($pollSummary.p90) |"
$md += "| RAM delta | -788 KB | avg delta=$($stability.ramAvgDeltaKb) KB |"
$md += ''
if($fails.Count -gt 0){$md += "Kết luận: Trong phạm vi stress test cục bộ trên $($stability.registeredBackend) backend device và $concurrentActual emulator đồng thời, fault injection đã phát hiện $($fails.Count) fail case. Kết quả này cho thấy hệ thống bộc lộ giới hạn dưới các kịch bản lỗi nhân tạo; cần kiểm thử thêm trên thiết bị thật và môi trường production."}else{$md += "Kết luận: Trong phạm vi stress test cục bộ trên $($stability.registeredBackend) backend device và $concurrentActual emulator đồng thời trong $DurationMinutes phút, không phát hiện fail case từ các fault injection đã chạy. Kết quả này vẫn giới hạn ở môi trường emulator cục bộ và chưa đại diện cho production."}
$md -join "`n" | Set-Content -LiteralPath (Join-Path $out 'STRESS-TEST-SUMMARY.md') -Encoding UTF8
# Shutdown
foreach($rec in $running){ Stop-EmulatorRecord -Record $rec -Reason 'test complete' }
[pscustomobject]@{status=$status;outputDir=$out;avdsCreated=$created;avdsProvisioned=$provisioned.Count;registeredBackend=$stability.registeredBackend;concurrentMaxActual=$concurrentActual;avdRamMB=$AvdRamMB;freeRamMinMB=$stability.freeRamMinMB;commands=$stability.totalCommands;commandSuccess=$stability.commandSuccess;commandFailed=$stability.commandFailed;policySuccess=$stability.policySuccess;policyFailed=$stability.policyFailed;faults=$stability.faultInjections;fails=$stability.failCases;severity=$severity} | ConvertTo-Json -Depth 10
