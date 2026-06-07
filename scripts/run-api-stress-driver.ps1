[CmdletBinding()]
param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$DeviceFile,
    [int]$DurationMinutes = 30,
    [int]$CommandIntervalSec = 20,
    [string]$OutputDir = "benchmark-results\stress-test-10device",
    [string]$AdminUser = "local-admin",
    [string]$AdminPass = "local-admin-123",
    [string]$DeviceUser = "local-device",
    [string]$DevicePass = "local-device-123"
)
$ErrorActionPreference = 'Stop'
$apiBase = "$BackendUrl/api"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
function New-AuthHeader { param([string]$Token) @{ Authorization = "Bearer $Token" } }
function Invoke-StressRequest {
    param(
        [string]$Flow,
        [string]$Method,
        [string]$Endpoint,
        [object]$Body = $null,
        [hashtable]$Headers = @{},
        [string]$DeviceCode = "",
        [int]$Run = 0,
        [int]$TimeoutSec = 30
    )
    $uri = "$apiBase$Endpoint"
    $jsonBody = $null
    if ($null -ne $Body) { $jsonBody = $Body | ConvertTo-Json -Depth 20 -Compress }
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $statusCode = 0
    $success = $false
    $raw = $null
    $errorText = $null
    try {
        $resp = Invoke-WebRequest -Method $Method -Uri $uri -Headers $Headers -ContentType 'application/json' -Body $jsonBody -TimeoutSec $TimeoutSec -UseBasicParsing
        $timer.Stop()
        $statusCode = [int]$resp.StatusCode
        $success = $statusCode -ge 200 -and $statusCode -lt 300
        $raw = $resp.Content
    } catch {
        $timer.Stop()
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) { $statusCode = [int]$_.Exception.Response.StatusCode }
        $errorText = $_.Exception.Message
        $raw = $errorText
    }
    $parsed = $null
    if ($raw -and (($raw.Trim()).StartsWith('{') -or ($raw.Trim()).StartsWith('['))) {
        try { $parsed = $raw | ConvertFrom-Json } catch { $parsed = $null }
    }
    return [pscustomobject]@{
        flow = $Flow; method = $Method; endpoint = $Endpoint; run = $Run; deviceCode = $DeviceCode
        statusCode = $statusCode; elapsedMs = [Math]::Round($timer.Elapsed.TotalMilliseconds, 2)
        success = $success; error = $errorText; raw = $raw; json = $parsed
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    }
}
function Write-CsvAppend {
    param([string]$Path, [object]$Row)
    $exists = Test-Path -LiteralPath $Path
    $Row | Select-Object flow,method,endpoint,run,deviceCode,statusCode,elapsedMs,success,error,timestamp |
        Export-Csv -LiteralPath $Path -NoTypeInformation -Append:$exists -Force -Encoding UTF8
}
function Get-DesiredFromDetail {
    param([object]$Detail, [object]$Config)
    $hash = $Detail.desiredConfigHash
    $version = $Detail.desiredConfigVersionEpochMillis
    if (-not $version -and $Config) { $version = $Config.configVersionEpochMillis }
    if (-not $hash) { $hash = "stress-no-desired-hash-$version" }
    return [pscustomobject]@{ hash = $hash; version = [int64]$version }
}
if (-not $DeviceFile -or -not (Test-Path -LiteralPath $DeviceFile)) { throw 'DeviceFile is required and must exist.' }
$fileContent = Get-Content -Raw -LiteralPath $DeviceFile
$fileDevices = $fileContent | ConvertFrom-Json
if ($fileDevices -isnot [array]) { $fileDevices = @($fileDevices) }
if ($fileDevices.Count -eq 0) { throw 'DeviceFile is empty.' }

$aggregateCsv = Join-Path $OutputDir 'api-performance-aggregate.csv'
$adminLogin = Invoke-StressRequest -Flow 'admin_login_setup' -Method 'POST' -Endpoint '/auth/login' -Body @{username=$AdminUser;password=$AdminPass}
if (-not $adminLogin.success) { throw "Admin login failed: $($adminLogin.error)" }
$adminHeaders = New-AuthHeader $adminLogin.json.token
$profilesResp = Invoke-StressRequest -Flow 'admin_profiles' -Method 'GET' -Endpoint '/admin/profiles' -Headers $adminHeaders
$defaultUserCode = $null
if ($profilesResp.success -and $profilesResp.json) { $defaultUserCode = @($profilesResp.json)[0].userCode }
$setupDevices = @()
foreach ($dev in $fileDevices) {
    $code = $dev.deviceCode
    $register = Invoke-StressRequest -Flow 'device_register_setup' -Method 'POST' -Endpoint '/device/register' -Headers $adminHeaders -DeviceCode $code -Body @{deviceCode=$code;androidVersion='16';sdkInt=36;manufacturer='Google';model='sdk_gphone64_x86_64';batteryLevel=80;isCharging=$true;wifiEnabled=$true}
    Write-CsvAppend $aggregateCsv $register
    
    $deviceId = $register.json.deviceId
    if (-not $deviceId) { $deviceId = $register.json.id }
    
    if ($deviceId -and $defaultUserCode) {
        $link = Invoke-StressRequest -Flow 'admin_link_profile_setup' -Method 'PUT' -Endpoint "/admin/devices/$deviceId/link" -Headers $adminHeaders -DeviceCode $code -Body @{userCode=$defaultUserCode}
        Write-CsvAppend $aggregateCsv $link
    }
    
    if ($deviceId) { 
        $dev | Add-Member -MemberType NoteProperty -Name 'deviceId' -Value $deviceId -Force
        $setupDevices += $dev 
    } else {
        Write-Warning "Failed to get deviceId for $code. Register response: $($register.raw)"
    }
}
$deviceFileOut = Join-Path $OutputDir 'stress-devices.json'
$setupDevices | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $deviceFileOut -Encoding UTF8
$jobs = @()
$deviceIndex = 0
foreach ($dev in $setupDevices) {
    $currentIndex = $deviceIndex
    $deviceIndex++
    $jobs += Start-Job -Name "stress-$($dev.deviceCode)" -ArgumentList $apiBase,$dev.deviceCode,$dev.deviceId,$DurationMinutes,$CommandIntervalSec,$AdminUser,$AdminPass,$DeviceUser,$DevicePass,$OutputDir,$currentIndex -ScriptBlock {
        param($apiBase,$deviceCode,$deviceId,$durationMinutes,$commandIntervalSec,$adminUser,$adminPass,$deviceUser,$devicePass,$outputDir,$index)
        $ErrorActionPreference='Stop'
        function New-AuthHeaderLocal { param([string]$Token) @{ Authorization = "Bearer $Token" } }
        function Invoke-RequestLocal {
            param([string]$Flow,[string]$Method,[string]$Endpoint,[object]$Body=$null,[hashtable]$Headers=@{},[int]$Run=0)
            $uri="$apiBase$Endpoint"; $jsonBody=$null
            if($null -ne $Body){$jsonBody=$Body|ConvertTo-Json -Depth 20 -Compress}
            $sw=[Diagnostics.Stopwatch]::StartNew(); $status=0; $success=$false; $raw=$null; $err=$null
            try{ $resp=Invoke-WebRequest -Method $Method -Uri $uri -Headers $Headers -ContentType 'application/json' -Body $jsonBody -TimeoutSec 30 -UseBasicParsing; $sw.Stop(); $status=[int]$resp.StatusCode; $success=$status -ge 200 -and $status -lt 300; $raw=$resp.Content }
            catch{ $sw.Stop(); if($_.Exception.Response -and $_.Exception.Response.StatusCode){$status=[int]$_.Exception.Response.StatusCode}; $err=$_.Exception.Message; $raw=$err }
            $json=$null; if($raw -and (($raw.Trim()).StartsWith('{') -or ($raw.Trim()).StartsWith('['))){try{$json=$raw|ConvertFrom-Json}catch{}}
            [pscustomobject]@{flow=$Flow;method=$Method;endpoint=$Endpoint;run=$Run;deviceCode=$deviceCode;statusCode=$status;elapsedMs=[Math]::Round($sw.Elapsed.TotalMilliseconds,2);success=$success;error=$err;raw=$raw;json=$json;timestamp=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()}
        }
        function Invoke-AuthRequestWithRetryLocal {
            param([string]$Flow,[object]$Body)
            $attempt = 0
            $last = $null
            while ($attempt -lt 3) {
                $attempt++
                $last = Invoke-RequestLocal -Flow $Flow -Method 'POST' -Endpoint '/auth/login' -Body $Body -Run $attempt
                if ($last.success -or ($last.statusCode -ne 401 -and $last.statusCode -ne 429)) { return $last }
                $delayMs = [int]([Math]::Pow(2, $attempt - 1) * 1000) + (Get-Random -Minimum 100 -Maximum 500)
                Start-Sleep -Milliseconds $delayMs
            }
            return $last
        }
        function Add-CsvLocal { param($Path,$Row) $exists=Test-Path -LiteralPath $Path; $Row|Select-Object flow,method,endpoint,run,deviceCode,statusCode,elapsedMs,success,error,timestamp|Export-Csv -LiteralPath $Path -NoTypeInformation -Append:$exists -Force -Encoding UTF8 }
        $deviceDir=Join-Path $outputDir "device-$deviceCode"; New-Item -ItemType Directory -Force -Path $deviceDir|Out-Null
        $csv=Join-Path $deviceDir 'api-performance.csv'; $cmdHistory=New-Object System.Collections.Generic.List[object]; $policyHistory=New-Object System.Collections.Generic.List[object]
        Start-Sleep -Milliseconds ($index * 1500)
        $admin=Invoke-AuthRequestWithRetryLocal -Flow 'admin_login' -Body @{username=$adminUser;password=$adminPass}; Add-CsvLocal $csv $admin
        $devLogin=Invoke-AuthRequestWithRetryLocal -Flow 'device_login' -Body @{username=$deviceUser;password=$devicePass;deviceCode=$deviceCode}; Add-CsvLocal $csv $devLogin
        if(-not $admin.success -or -not $devLogin.success){ throw "auth failed for $deviceCode" }
        $adminHeaders=New-AuthHeaderLocal $admin.json.token; $deviceHeaders=New-AuthHeaderLocal $devLogin.json.token
        $start=[DateTimeOffset]::UtcNow; $deadline=$start.AddMinutes($durationMinutes); $run=0; $commandTypes=@('refresh_config','sync_config','refresh_config','lock_screen')
        while([DateTimeOffset]::UtcNow -lt $deadline){
            $run++
            $detail=Invoke-RequestLocal -Flow 'admin_get_device_detail' -Method 'GET' -Endpoint "/admin/devices/$deviceId" -Headers $adminHeaders -Run $run; Add-CsvLocal $csv $detail
            $config=Invoke-RequestLocal -Flow 'device_config_current' -Method 'GET' -Endpoint "/device/config/current?deviceCode=$deviceCode" -Headers $deviceHeaders -Run $run; Add-CsvLocal $csv $config
            $desiredHash=$detail.json.desiredConfigHash; $desiredVersion=$detail.json.desiredConfigVersionEpochMillis
            if(-not $desiredVersion -and $config.json){$desiredVersion=$config.json.configVersionEpochMillis}
            if(-not $desiredHash){$desiredHash="stress-no-desired-hash-$desiredVersion"}
            $type=$commandTypes[($run-1)%$commandTypes.Count]
            $payload='{}'; if($type -eq 'lock_screen'){$payload='{"duration_seconds":5}'}
            $createdBefore=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            $create=Invoke-RequestLocal -Flow 'admin_create_command' -Method 'POST' -Endpoint "/admin/devices/$deviceId/commands" -Headers $adminHeaders -Run $run -Body @{type=$type;payload=$payload;ttlSeconds=600}; Add-CsvLocal $csv $create
            $commandId=$create.json.id
            $poll=Invoke-RequestLocal -Flow 'device_poll_with_command' -Method 'POST' -Endpoint '/device/poll' -Headers $deviceHeaders -Run $run -Body @{deviceCode=$deviceCode;limit=10}; Add-CsvLocal $csv $poll
            $polledAt=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            $leased=$null; if($poll.success -and $poll.json.commands){$leased=@($poll.json.commands|Where-Object{$_.id -eq $commandId}|Select-Object -First 1)[0]; if(-not $leased){$leased=@($poll.json.commands|Select-Object -First 1)[0]}}
            $ack=$null; $ackAt=$null; $final='NOT_LEASED'
            if($leased){$ack=Invoke-RequestLocal -Flow 'device_ack_command' -Method 'POST' -Endpoint '/device/ack' -Headers $deviceHeaders -Run $run -Body @{deviceCode=$deviceCode;commandId=$leased.id;leaseToken=$leased.leaseToken;result='SUCCESS';output='stress ack'}; Add-CsvLocal $csv $ack; $ackAt=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); $final=$ack.json.status}
            $cmdHistory.Add([pscustomobject]@{deviceCode=$deviceCode;deviceId=$deviceId;commandId=$commandId;leasedCommandId=if($leased){$leased.id}else{$null};type=$type;createdAt=$createdBefore;polledAt=$polledAt;ackAt=$ackAt;leaseToken=if($leased){$leased.leaseToken}else{$null};status=$final;deliveryLatencyMs=($polledAt-$createdBefore);ackLatencyMs=if($ackAt){$ackAt-$polledAt}else{$null};createStatus=$create.statusCode;pollStatus=$poll.statusCode;ackStatus=if($ack){$ack.statusCode}else{0}})|Out-Null
            $now=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            $policy=Invoke-RequestLocal -Flow 'device_policy_state' -Method 'POST' -Endpoint '/device/policy-state' -Headers $deviceHeaders -Run $run -Body @{deviceCode=$deviceCode;desiredConfigVersionEpochMillis=[int64]$desiredVersion;desiredConfigHash=$desiredHash;appliedConfigVersionEpochMillis=[int64]$desiredVersion;appliedConfigHash=$desiredHash;policyApplyStatus='SUCCESS';policyAppliedAtEpochMillis=$now}; Add-CsvLocal $csv $policy
            $policyHistory.Add([pscustomobject]@{deviceCode=$deviceCode;timestamp=$now;desiredConfigHash=$desiredHash;desiredConfigVersionEpochMillis=$desiredVersion;appliedConfigHash=$desiredHash;appliedConfigVersionEpochMillis=$desiredVersion;policyApplyStatus='SUCCESS';statusCode=$policy.statusCode;success=$policy.success})|Out-Null
            Start-Sleep -Seconds $commandIntervalSec
        }
        $cmdHistory|ConvertTo-Json -Depth 20|Set-Content -LiteralPath (Join-Path $deviceDir 'commands-history.json') -Encoding UTF8
        $policyHistory|ConvertTo-Json -Depth 20|Set-Content -LiteralPath (Join-Path $deviceDir 'policy-state-history.json') -Encoding UTF8
        [pscustomobject]@{deviceCode=$deviceCode;commands=$cmdHistory.Count;commandSuccess=($cmdHistory|Where-Object status -eq 'SUCCESS').Count;commandFailed=($cmdHistory|Where-Object status -ne 'SUCCESS').Count;policyReports=$policyHistory.Count;policySuccess=($policyHistory|Where-Object success).Count}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $deviceDir 'api-summary.json') -Encoding UTF8
    }
}
$jobs | Wait-Job | Out-Null
foreach($job in $jobs){ Receive-Job $job -ErrorAction Continue | Out-File -FilePath (Join-Path $OutputDir "$($job.Name).job.log") -Append; Remove-Job $job -Force }
Get-ChildItem -Path $OutputDir -Recurse -Filter 'api-performance.csv' | ForEach-Object { Import-Csv -LiteralPath $_.FullName } | Export-Csv -LiteralPath $aggregateCsv -NoTypeInformation -Encoding UTF8
Get-ChildItem -Path $OutputDir -Recurse -Filter 'commands-history.json' | ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName | ConvertFrom-Json } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $OutputDir 'command-lifecycle-all.json') -Encoding UTF8
Get-ChildItem -Path $OutputDir -Recurse -Filter 'policy-state-history.json' | ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName | ConvertFrom-Json } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $OutputDir 'policy-state-all.json') -Encoding UTF8
[pscustomobject]@{deviceCount=$setupDevices.Count;durationMinutes=$DurationMinutes;commandIntervalSec=$CommandIntervalSec;outputDir=(Resolve-Path $OutputDir).Path}|ConvertTo-Json -Depth 5
