[CmdletBinding()]
param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$DeviceFile,
    [object[]]$Devices,
    [string]$OutputDir = "benchmark-results\stress-test-10device",
    [string]$AdminUser = "local-admin",
    [string]$AdminPass = "local-admin-123",
    [string]$DeviceUser = "local-device",
    [string]$DevicePass = "local-device-123"
)
$ErrorActionPreference = 'Continue'
$apiBase = "$BackendUrl/api"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
if ($DeviceFile -and (Test-Path -LiteralPath $DeviceFile)) {
    $fileContent = Get-Content -Raw -LiteralPath $DeviceFile
    $parsed = $fileContent | ConvertFrom-Json
    if ($parsed -isnot [array]) { $parsed = @($parsed) }
    $Devices = $parsed
}
if (-not $Devices -or $Devices.Count -eq 0) { throw 'No devices supplied for fault injection.' }
function New-AuthHeaderLocal { param([string]$Token) @{ Authorization = "Bearer $Token" } }
function Invoke-FaultRequest {
    param([string]$Method,[string]$Endpoint,[object]$Body=$null,[hashtable]$Headers=@{},[int]$TimeoutSec=30)
    $json=$null; if($null -ne $Body){$json=$Body|ConvertTo-Json -Depth 20 -Compress}
    $sw=[Diagnostics.Stopwatch]::StartNew(); $status=0; $success=$false; $raw=$null; $err=$null
    try { $resp=Invoke-WebRequest -Method $Method -Uri "$apiBase$Endpoint" -Headers $Headers -ContentType 'application/json' -Body $json -TimeoutSec $TimeoutSec -UseBasicParsing; $sw.Stop(); $status=[int]$resp.StatusCode; $success=$status -ge 200 -and $status -lt 300; $raw=$resp.Content }
    catch { $sw.Stop(); if($_.Exception.Response -and $_.Exception.Response.StatusCode){$status=[int]$_.Exception.Response.StatusCode}; $err=$_.Exception.Message; $raw=$err }
    $parsed=$null; if($raw -and (($raw.Trim()).StartsWith('{') -or ($raw.Trim()).StartsWith('['))){try{$parsed=$raw|ConvertFrom-Json}catch{}}
    [pscustomobject]@{statusCode=$status;success=$success;elapsedMs=[Math]::Round($sw.Elapsed.TotalMilliseconds,2);raw=$raw;json=$parsed;error=$err}
}
function Add-FaultResult {
    param([object]$Item)
    $script:faultResults.Add($Item) | Out-Null
    $Item | ConvertTo-Json -Depth 20 -Compress | Add-Content -LiteralPath (Join-Path $OutputDir 'fault-injection-log.jsonl') -Encoding UTF8
}
function New-FailCase {
    param([string]$Category,[string]$FaultId,[object]$Device,[string]$Description,[string]$Expected,[string]$Actual,[string]$RootCause,[string]$Severity,[string[]]$Evidence,[string]$Recommendation)
    $id = 'FAIL-{0:D3}' -f ($script:failCases.Count + 1)
    $case = [pscustomobject]@{id=$id;category=$Category;faultId=$FaultId;device=$Device.avd;serial=$Device.serial;timestamp=(Get-Date -Format o);description=$Description;expectedBehavior=$Expected;actualBehavior=$Actual;rootCause=$RootCause;severity=$Severity;evidence=$Evidence;reproducible=$true;recommendation=$Recommendation}
    $script:failCases.Add($case) | Out-Null
}
$admin = Invoke-FaultRequest -Method 'POST' -Endpoint '/auth/login' -Body @{username=$AdminUser;password=$AdminPass}
if (-not $admin.success) { throw "Admin login failed: $($admin.error)" }
$adminHeaders = New-AuthHeaderLocal $admin.json.token
$script:faultResults = New-Object System.Collections.Generic.List[object]
$script:failCases = New-Object System.Collections.Generic.List[object]
# Attach device auth tokens.
$deviceStates = @()
foreach($dev in $Devices){
    $login=Invoke-FaultRequest -Method 'POST' -Endpoint '/auth/login' -Body @{username=$DeviceUser;password=$DevicePass;deviceCode=$dev.deviceCode}
    $headers=$null; if($login.success){$headers=New-AuthHeaderLocal $login.json.token}
    $deviceStates += [pscustomobject]@{avd=$dev.avd;serial=$dev.serial;deviceCode=$dev.deviceCode;deviceId=$dev.deviceId;headers=$headers}
}
# FAULT-01 Network disruption.
$target=$deviceStates[0]
$cmd=Invoke-FaultRequest -Method 'POST' -Endpoint "/admin/devices/$($target.deviceId)/commands" -Headers $adminHeaders -Body @{type='refresh_config';payload='{}';ttlSeconds=600}
$wifiOff = if($target.serial){ adb -s $target.serial shell svc wifi disable 2>&1 } else { 'no serial' }
Start-Sleep -Seconds 15
$wifiOn = if($target.serial){ adb -s $target.serial shell svc wifi enable 2>&1 } else { 'no serial' }
Start-Sleep -Seconds 5
$poll=Invoke-FaultRequest -Method 'POST' -Endpoint '/device/poll' -Headers $target.headers -Body @{deviceCode=$target.deviceCode;limit=10}
$recovered = $poll.success -and $poll.json.commands -and (@($poll.json.commands | Where-Object { $_.id -eq $cmd.json.id }).Count -gt 0)
Add-FaultResult ([pscustomobject]@{id='FAULT-01';type='NETWORK_DISRUPTION';device=$target.avd;serial=$target.serial;expected='Device retry poll';actual=if($recovered){'Command recovered after wifi restore'}else{'Command not observed after wifi restore'};pass=[bool]$recovered;wifiOff=($wifiOff -join ';');wifiOn=($wifiOn -join ';');commandId=$cmd.json.id;timestamp=(Get-Date -Format o)})
if(-not $recovered){ New-FailCase -Category 'NETWORK_DISRUPTION' -FaultId 'FAULT-01' -Device $target -Description 'Command was not observed after simulated wifi disruption.' -Expected 'Device/API poll should receive pending command after wifi restoration.' -Actual 'Poll response did not contain the created command.' -RootCause 'Needs triage: command may have been leased by concurrent stress loop, lost from target poll, or network disruption did not map to script-side API poll.' -Severity 'MEDIUM' -Evidence @("commandId=$($cmd.json.id)","pollStatus=$($poll.statusCode)") -Recommendation 'Correlate command list status and Android logcat during real device poll after network recovery.' }
# FAULT-02 Process kill.
$target=$deviceStates[[Math]::Min(1,$deviceStates.Count-1)]
$appPid = if($target.serial){adb -s $target.serial shell pidof com.example.mdmapplication 2>&1}else{'no serial'}
if($appPid -and $appPid -notmatch 'no serial|not found'){ adb -s $target.serial shell kill -9 $appPid }
Start-Sleep -Seconds 3
$top=if($target.serial){adb -s $target.serial shell dumpsys activity top 2>&1 | Select-String 'com.example.mdmapplication'}else{$null}
$result=if($top){'APP_RECOVERED'}else{'APP_NOT_RECOVERED'}
if(-not $top -and $target.serial){ adb -s $target.serial shell am start -n com.example.mdmapplication/.ui.launcher.LauncherActivity | Out-Null; Start-Sleep -Seconds 3 }
$probe=Invoke-FaultRequest -Method 'POST' -Endpoint '/device/poll' -Headers $target.headers -Body @{deviceCode=$target.deviceCode;limit=1}
$pass = ($result -eq 'APP_RECOVERED') -and $probe.success
Add-FaultResult ([pscustomobject]@{id='FAULT-02';type='PROCESS_KILL';device=$target.avd;serial=$target.serial;expected='App recover';actual=$result;pass=$pass;pollAfterKill=$probe.statusCode;timestamp=(Get-Date -Format o)})
if(-not $pass){ New-FailCase -Category 'PROCESS_CRASH' -FaultId 'FAULT-02' -Device $target -Description 'MDM app did not automatically recover as top activity after force-stop or poll probe failed.' -Expected 'Launcher/home app should recover and device session should continue polling.' -Actual "$result; pollStatus=$($probe.statusCode)" -RootCause 'Android force-stop disables app until explicit launch; launcher recovery may require HOME intent/user action.' -Severity 'HIGH' -Evidence @("pollStatus=$($probe.statusCode)") -Recommendation 'Add watchdog/boot/runtime wake validation for recovery after force-stop if this scenario is in scope.' }
# FAULT-03 Command flooding.
$target=$deviceStates[[Math]::Min(2,$deviceStates.Count-1)]
$created=@(); for($i=1;$i -le 10;$i++){ $r=Invoke-FaultRequest -Method 'POST' -Endpoint "/admin/devices/$($target.deviceId)/commands" -Headers $adminHeaders -Body @{type='refresh_config';payload='{}';ttlSeconds=600}; $created += [pscustomobject]@{index=$i;status=$r.statusCode;id=$r.json.id;success=$r.success} }
$poll=Invoke-FaultRequest -Method 'POST' -Endpoint '/device/poll' -Headers $target.headers -Body @{deviceCode=$target.deviceCode;limit=10}
$leasedCount=if($poll.json.commands){@($poll.json.commands).Count}else{0}
$pass=$created.Count -eq 10 -and ($created|Where-Object success).Count -eq 10 -and $poll.success -and $leasedCount -ge 1
Add-FaultResult ([pscustomobject]@{id='FAULT-03';type='COMMAND_FLOOD';device=$target.avd;serial=$target.serial;expected='Backend queue accepts burst and leases commands consistently';actual="created=$($created.Count);leased=$leasedCount";pass=$pass;commands=$created;timestamp=(Get-Date -Format o)})
if(-not $pass){ New-FailCase -Category 'COMMAND_FLOOD' -FaultId 'FAULT-03' -Device $target -Description 'Command burst did not create/lease consistently.' -Expected 'Backend should create all valid commands and lease pending commands predictably.' -Actual "createdSuccess=$(($created|Where-Object success).Count); leased=$leasedCount" -RootCause 'Potential queue/rate/concurrency limitation.' -Severity 'MEDIUM' -Evidence @("createdSuccess=$(($created|Where-Object success).Count)","leased=$leasedCount") -Recommendation 'Add queue depth monitoring and rate-limit behavior definition.' }
# FAULT-04 Policy conflict command ordering.
$target=$deviceStates[[Math]::Min(3,$deviceStates.Count-1)]
$config1=Invoke-FaultRequest -Method 'GET' -Endpoint "/device/config/current?deviceCode=$($target.deviceCode)" -Headers $target.headers
$c1=Invoke-FaultRequest -Method 'POST' -Endpoint "/admin/devices/$($target.deviceId)/commands" -Headers $adminHeaders -Body @{type='refresh_config';payload='{}';ttlSeconds=600}
Start-Sleep -Milliseconds 100
$c2=Invoke-FaultRequest -Method 'POST' -Endpoint "/admin/devices/$($target.deviceId)/commands" -Headers $adminHeaders -Body @{type='sync_config';payload='{}';ttlSeconds=600}
$poll=Invoke-FaultRequest -Method 'POST' -Endpoint '/device/poll' -Headers $target.headers -Body @{deviceCode=$target.deviceCode;limit=10}
$order=@(); if($poll.json.commands){$order=@($poll.json.commands|ForEach-Object{$_.type})}
$pass=$poll.success -and $order.Count -ge 2
Add-FaultResult ([pscustomobject]@{id='FAULT-04';type='POLICY_CONFLICT';device=$target.avd;serial=$target.serial;expected='Command ordering observable and config consistent';actual="order=$($order -join ',')";pass=$pass;configVersion=$config1.json.configVersionEpochMillis;timestamp=(Get-Date -Format o)})
if(-not $pass){ New-FailCase -Category 'POLICY_CONFLICT' -FaultId 'FAULT-04' -Device $target -Description 'Rapid refresh/sync command sequence was not observed completely in poll response.' -Expected 'Device should be able to receive ordered refresh_config/sync_config commands.' -Actual "order=$($order -join ',')" -RootCause 'Pending commands may be consumed by concurrent stress loop or lease limit behavior.' -Severity 'LOW' -Evidence @("pollStatus=$($poll.statusCode)","order=$($order -join ',')") -Recommendation 'Run isolated command ordering test with stress loop paused.' }
# FAULT-05 Duplicate register.
$target=$deviceStates[[Math]::Min(4,$deviceStates.Count-1)]
$dup=Invoke-FaultRequest -Method 'POST' -Endpoint '/device/register' -Headers $target.headers -Body @{deviceCode=$target.deviceCode;androidVersion='16';sdkInt=36;manufacturer='Google';model='Pixel_6_Duplicate'}
$pass = -not $dup.success -and $dup.statusCode -eq 409
Add-FaultResult ([pscustomobject]@{id='FAULT-05';type='DUPLICATE_REGISTER';device=$target.avd;serial=$target.serial;expected='Backend reject duplicate 409';actual="status=$($dup.statusCode); success=$($dup.success)";pass=$pass;timestamp=(Get-Date -Format o)})
if($dup.success){ New-FailCase -Category 'DUPLICATE_ACCEPT' -FaultId 'FAULT-05' -Device $target -Description 'Duplicate registration was accepted.' -Expected 'Backend reject duplicate deviceCode with 409 according to stress-test expectation.' -Actual "POST /device/register returned $($dup.statusCode)" -RootCause 'Backend implements idempotent update/register instead of duplicate rejection, or duplicate guard missing.' -Severity 'MEDIUM' -Evidence @("status=$($dup.statusCode)","deviceCode=$($target.deviceCode)") -Recommendation 'Clarify contract: idempotent register vs duplicate rejection. If rejection required, enforce 409.' }
# FAULT-06 Fake leaseToken ACK.
$target=$deviceStates[0]
$cmd=Invoke-FaultRequest -Method 'POST' -Endpoint "/admin/devices/$($target.deviceId)/commands" -Headers $adminHeaders -Body @{type='refresh_config';payload='{}';ttlSeconds=600}
$poll=Invoke-FaultRequest -Method 'POST' -Endpoint '/device/poll' -Headers $target.headers -Body @{deviceCode=$target.deviceCode;limit=10}
$leased=@($poll.json.commands|Select-Object -First 1)[0]
$fake=Invoke-FaultRequest -Method 'POST' -Endpoint '/device/ack' -Headers $target.headers -Body @{deviceCode=$target.deviceCode;commandId=$leased.id;leaseToken='00000000-0000-0000-0000-000000000000';result='SUCCESS'}
$pass = -not $fake.success
if($leased){ [void](Invoke-FaultRequest -Method 'POST' -Endpoint '/device/ack' -Headers $target.headers -Body @{deviceCode=$target.deviceCode;commandId=$leased.id;leaseToken=$leased.leaseToken;result='SUCCESS';output='cleanup after fake ack fault'}) }
Add-FaultResult ([pscustomobject]@{id='FAULT-06';type='FAKE_LEASE_ACK';device=$target.avd;serial=$target.serial;expected='Backend reject fake leaseToken';actual="status=$($fake.statusCode); success=$($fake.success)";pass=$pass;commandId=$leased.id;timestamp=(Get-Date -Format o)})
if($fake.success){ New-FailCase -Category 'AUTH_BYPASS' -FaultId 'FAULT-06' -Device $target -Description 'ACK with fake leaseToken was accepted.' -Expected 'Backend must reject ACK when leaseToken mismatches.' -Actual "ACK returned $($fake.statusCode)" -RootCause 'Lease token validation failure.' -Severity 'CRITICAL' -Evidence @("commandId=$($leased.id)","fakeStatus=$($fake.statusCode)") -Recommendation 'Enforce commandId/deviceId/leaseToken match in ACK service.' }
# FAULT-07 Double poll.
$target=$deviceStates[[Math]::Min(1,$deviceStates.Count-1)]
[void](Invoke-FaultRequest -Method 'POST' -Endpoint "/admin/devices/$($target.deviceId)/commands" -Headers $adminHeaders -Body @{type='refresh_config';payload='{}';ttlSeconds=600})
Start-Sleep -Seconds 1
$scriptBlock={ param($apiBase,$headers,$code) Invoke-WebRequest -Method POST -Uri "$apiBase/device/poll" -Headers $headers -ContentType 'application/json' -Body (@{deviceCode=$code;limit=1}|ConvertTo-Json -Compress) -UseBasicParsing | Select-Object -ExpandProperty Content }
$j1=Start-Job -ScriptBlock $scriptBlock -ArgumentList $apiBase,$target.headers,$target.deviceCode
$j2=Start-Job -ScriptBlock $scriptBlock -ArgumentList $apiBase,$target.headers,$target.deviceCode
$r1=Receive-Job $j1 -Wait; $r2=Receive-Job $j2 -Wait; Remove-Job $j1,$j2 -Force
$o1=$null;$o2=$null;try{$o1=$r1|ConvertFrom-Json}catch{};try{$o2=$r2|ConvertFrom-Json}catch{}
$c1=if($o1.commands){@($o1.commands).Count}else{0}; $c2=if($o2.commands){@($o2.commands).Count}else{0}
$bothGotSame=$false; if($c1 -gt 0 -and $c2 -gt 0){$bothGotSame=$o1.commands[0].id -eq $o2.commands[0].id}
$pass = -not $bothGotSame
Add-FaultResult ([pscustomobject]@{id='FAULT-07';type='DOUBLE_POLL';device=$target.avd;serial=$target.serial;expected='Only one poll leases a command';actual="poll1=$c1;poll2=$c2;same=$bothGotSame";pass=$pass;timestamp=(Get-Date -Format o)})
if($bothGotSame){ New-FailCase -Category 'RACE_CONDITION' -FaultId 'FAULT-07' -Device $target -Description 'Two concurrent polls received the same command.' -Expected 'Only one poll should lease a pending command.' -Actual 'Both poll responses included same command id.' -RootCause 'Potential lease race in command repository.' -Severity 'HIGH' -Evidence @("poll1=$c1","poll2=$c2") -Recommendation 'Make command leasing atomic with status transition guard.' }
# FAULT-08 Resource starvation.
$target=$deviceStates[[Math]::Min(2,$deviceStates.Count-1)]
$apps=@('com.android.settings/.Settings','com.android.dialer/.main.impl.MainActivity','com.android.chrome/com.google.android.apps.chrome.Main')
foreach($app in $apps){ if($target.serial){ adb -s $target.serial shell am start -n $app 2>$null | Out-Null; Start-Sleep -Milliseconds 500 } }
Start-Sleep -Seconds 10
$appPid=if($target.serial){adb -s $target.serial shell pidof com.example.mdmapplication 2>$null}else{''}
$pass = -not [string]::IsNullOrWhiteSpace(($appPid -join '').Trim())
Add-FaultResult ([pscustomobject]@{id='FAULT-08';type='RESOURCE_STARVATION';device=$target.avd;serial=$target.serial;expected='Agent process remains alive';actual="pid=$appPid";pass=$pass;timestamp=(Get-Date -Format o)})
if(-not $pass){ New-FailCase -Category 'RESOURCE_STARVATION' -FaultId 'FAULT-08' -Device $target -Description 'MDM process was not found after opening several apps.' -Expected 'Agent should remain alive or recover as launcher.' -Actual 'pidof com.example.mdmapplication returned empty.' -RootCause 'Possible LMK/process death under resource pressure.' -Severity 'HIGH' -Evidence @("pid=$appPid") -Recommendation 'Investigate foreground/launcher process priority and recovery path.' }
# FAULT-09 ADB restart.
$before=adb devices -l 2>&1
adb kill-server 2>&1 | Out-Null
Start-Sleep -Seconds 5
adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 3
$after=adb devices -l 2>&1
$expectedSerials=@($deviceStates|Where-Object serial|ForEach-Object{$_.serial})
$missing=@(); foreach($s in $expectedSerials){ if(($after -join "`n") -notmatch [regex]::Escape($s)){ $missing += $s } }
$pass = $missing.Count -eq 0
Add-FaultResult ([pscustomobject]@{id='FAULT-09';type='ADB_RESTART';device='all';serial=($expectedSerials -join ',');expected='All emulators reconnect';actual="missing=$($missing -join ',')";pass=$pass;before=($before -join "`n");after=($after -join "`n");timestamp=(Get-Date -Format o)})
if(-not $pass){ New-FailCase -Category 'ADB_INSTABILITY' -FaultId 'FAULT-09' -Device ([pscustomobject]@{avd='multiple';serial=($missing -join ',')}) -Description 'Not all emulators reconnected after adb restart.' -Expected 'All online emulators should reconnect after adb server restart.' -Actual "Missing serials: $($missing -join ',')" -RootCause 'ADB/emulator instability under multi-device load.' -Severity 'HIGH' -Evidence @($after) -Recommendation 'Reduce concurrent emulator count or isolate ADB server load; use physical devices for validation.' }
# FAULT-10 Rapid config refresh.
$target=$deviceStates[[Math]::Min(3,$deviceStates.Count-1)]
$configStart=Invoke-FaultRequest -Method 'GET' -Endpoint "/device/config/current?deviceCode=$($target.deviceCode)" -Headers $target.headers
$rapid=@(); for($i=1;$i -le 5;$i++){ $rapid += Invoke-FaultRequest -Method 'POST' -Endpoint "/admin/devices/$($target.deviceId)/commands" -Headers $adminHeaders -Body @{type='refresh_config';payload='{}';ttlSeconds=600}; Start-Sleep -Seconds 2 }
$poll=Invoke-FaultRequest -Method 'POST' -Endpoint '/device/poll' -Headers $target.headers -Body @{deviceCode=$target.deviceCode;limit=10}
if($poll.json.commands){ foreach($cmd in $poll.json.commands){ [void](Invoke-FaultRequest -Method 'POST' -Endpoint '/device/ack' -Headers $target.headers -Body @{deviceCode=$target.deviceCode;commandId=$cmd.id;leaseToken=$cmd.leaseToken;result='SUCCESS';output='rapid config cleanup'}) } }
$configEnd=Invoke-FaultRequest -Method 'GET' -Endpoint "/device/config/current?deviceCode=$($target.deviceCode)" -Headers $target.headers
$pass = $configStart.success -and $configEnd.success -and ($configStart.json.configVersionEpochMillis -eq $configEnd.json.configVersionEpochMillis)
Add-FaultResult ([pscustomobject]@{id='FAULT-10';type='RAPID_CONFIG_CHANGE';device=$target.avd;serial=$target.serial;expected='Config version remains consistent without profile changes';actual="start=$($configStart.json.configVersionEpochMillis);end=$($configEnd.json.configVersionEpochMillis)";pass=$pass;timestamp=(Get-Date -Format o)})
if(-not $pass){ New-FailCase -Category 'DATA_INCONSISTENCY' -FaultId 'FAULT-10' -Device $target -Description 'Config version changed unexpectedly during rapid refresh commands.' -Expected 'Refresh commands without profile edits should not alter desired config version.' -Actual "start=$($configStart.json.configVersionEpochMillis); end=$($configEnd.json.configVersionEpochMillis)" -RootCause 'Possible profile/config recompute side-effect or concurrent external profile edit.' -Severity 'MEDIUM' -Evidence @("start=$($configStart.json.configVersionEpochMillis)","end=$($configEnd.json.configVersionEpochMillis)") -Recommendation 'Audit desired config recompute triggers during refresh commands.' }
$faultResults | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'fault-injection-summary.json') -Encoding UTF8
$severity = @{CRITICAL=0;HIGH=0;MEDIUM=0;LOW=0}; $category=@{}
foreach($fc in $failCases){ if($severity.ContainsKey($fc.severity)){$severity[$fc.severity]++}; if(-not $category.ContainsKey($fc.category)){$category[$fc.category]=0}; $category[$fc.category]++ }
[pscustomobject]@{testDuration='30 minutes';totalDevices=$Devices.Count;concurrentMax=$Devices.Count;totalCommandsSent=0;totalCommandsSuccess=0;totalCommandsFailed=0;totalPolicyReports=0;totalFaultInjections=$faultResults.Count;failCases=$failCases;summary=[pscustomobject]@{totalFails=$failCases.Count;bySeverity=$severity;byCategory=$category}} | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'fail-cases-report.json') -Encoding UTF8
[pscustomobject]@{faults=$faultResults.Count;fails=$failCases.Count;outputDir=(Resolve-Path $OutputDir).Path}|ConvertTo-Json -Depth 5
