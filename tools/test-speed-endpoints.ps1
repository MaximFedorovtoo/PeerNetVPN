param(
    [ValidateRange(1,1048576)][int]$PayloadBytes = 65536,
    [ValidatePattern('^[A-Za-z0-9:._-]+$')][string]$AdbSerial
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$folder = Join-Path $project ('.tools/speed-probes/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $folder | Out-Null
$payload = Join-Path $folder 'payload.bin'
$bytes = New-Object byte[] $PayloadBytes
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($bytes) } finally { $random.Dispose() }
[IO.File]::WriteAllBytes($payload,$bytes)
$endpoints = @('https://speedtest.selectel.ru/empty.php', 'https://speedtest.59telecom.ru/backend/empty')
# These exact upload paths are published by each provider's own speed-test frontend.
# No redirects, TLS exceptions, speculative POST targets, or automatic retries.
$adb = Join-Path $project '.tools/android-sdk/platform-tools/adb.exe'
$remotePrefix = '/data/local/tmp/uzel-speed-' + [guid]::NewGuid().ToString('N')
if ($AdbSerial) {
    & $adb -s $AdbSerial push $payload "$remotePrefix.bin" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot copy bounded test payload to the Android device' }
}
$results = @()
try {
    for ($i=0; $i -lt $endpoints.Count; $i++) {
        $headers = Join-Path $folder "$i.headers"
        $body = Join-Path $folder "$i.body"
        $format = 'HTTP=%{http_code},UPLOAD=%{size_upload},BODY=%{size_download},SECONDS=%{time_total}'
        $options = @('--silent','--show-error','--connect-timeout','8','--max-time','20','--max-filesize','1024',
            '--proto','=https','--request','POST','--header','Content-Type:application/octet-stream',
            '--header','Content-Encoding:identity','--header','Accept-Encoding:identity')
        if ($AdbSerial) {
            $metadata = & $adb -s $AdbSerial shell curl @options --dump-header "$remotePrefix.headers" --output "$remotePrefix.body" --write-out $format --data-binary "@$remotePrefix.bin" $endpoints[$i]
            $exitCode = $LASTEXITCODE
            & $adb -s $AdbSerial pull "$remotePrefix.headers" $headers 2>$null | Out-Null
            & $adb -s $AdbSerial pull "$remotePrefix.body" $body 2>$null | Out-Null
        } else {
            $metadata = & curl.exe @options --dump-header $headers --output $body --write-out $format --data-binary "@$payload" $endpoints[$i]
            $exitCode = $LASTEXITCODE
        }
        $meta = [regex]::Match(($metadata -join ''),'HTTP=(\d+),UPLOAD=(\d+),BODY=(\d+),SECONDS=([0-9.]+)')
        $cache = if (Test-Path $headers) { ((Get-Content $headers | Where-Object {$_ -match '^Cache-Control:'}) -replace '^Cache-Control:\s*','') -join ',' } else { '' }
        $directives = @($cache.ToLowerInvariant().Split(',') | ForEach-Object {$_.Trim()})
        $bodySize = if (Test-Path $body) { (Get-Item $body).Length } else { -1 }
        $accepted = $exitCode -eq 0 -and $meta.Success -and $meta.Groups[1].Value -eq '200' -and
            [long]$meta.Groups[2].Value -eq $PayloadBytes -and $bodySize -eq 0 -and
            $directives -contains 'no-store' -and $directives -contains 'no-cache'
        $results += [pscustomobject]@{
            Endpoint=$endpoints[$i]; ExitCode=$exitCode; HTTP=$meta.Groups[1].Value
            UploadedBytes=$meta.Groups[2].Value; BodyBytes=$bodySize; Seconds=$meta.Groups[4].Value
            ContractAccepted=$accepted; TLSVerificationEnabled=$true
            Route=$(if ($AdbSerial) { "Android shell default route ($AdbSerial); physical binding not asserted" } else { 'Desktop default route' })
        }
    }
} finally {
    if ($AdbSerial) { & $adb -s $AdbSerial shell rm -f "$remotePrefix.bin" "$remotePrefix.headers" "$remotePrefix.body" | Out-Null }
}
$results | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $folder 'results.json')
$results | Format-List
Write-Host "Raw responses and results: $folder"
if (@($results | Where-Object ContractAccepted).Count -eq 0) { throw 'Neither supported endpoint passed from this connection' }
