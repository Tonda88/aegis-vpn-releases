# Tests every vless:// key from a text file through sing-box.
# For each key: SOCKS5 CONNECT to example.com:80 (plain HTTP) and a TLS request
# to a target (default youtube.com:443) to see whether real traffic passes.

param(
    [string]$KeyFile = '',
    [string]$OutDir = '',
    [string]$TlsTarget = 'youtube.com'
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $PSScriptRoot '..\..\.keytest' }
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$singbox = Join-Path $PSScriptRoot 'sing-box.exe'
if (-not (Test-Path $singbox)) { throw 'sing-box.exe not found next to this script' }

$keys = Get-Content $KeyFile -Encoding UTF8 | Where-Object { $_ -match '^vless://' }
"keys: $($keys.Count)"

function Parse-Vless([string]$raw) {
    $body = $raw.Substring('vless://'.Length)
    $frag = ''
    if ($body.Contains('#')) {
        $frag = [uri]::UnescapeDataString($body.Substring($body.IndexOf('#') + 1))
        $body = $body.Substring(0, $body.IndexOf('#'))
    }
    $query = @{}
    if ($body.Contains('?')) {
        $q = $body.Substring($body.IndexOf('?') + 1)
        $body = $body.Substring(0, $body.IndexOf('?'))
        foreach ($pair in $q.Split('&')) {
            if (-not $pair) { continue }
            $kv = $pair.Split('=', 2)
            $query[$kv[0]] = if ($kv.Count -gt 1) { [uri]::UnescapeDataString($kv[1]) } else { '' }
        }
    }
    $userInfo = $body.Substring(0, $body.LastIndexOf('@'))
    $authority = $body.Substring($body.LastIndexOf('@') + 1)
    $srvHost = $authority; $port = 443
    if ($authority.Contains(':')) {
        $srvHost = $authority.Substring(0, $authority.LastIndexOf(':'))
        $port = [int]$authority.Substring($authority.LastIndexOf(':') + 1)
    }
    $net = if ($query['type']) { $query['type'].ToLower() } else { 'tcp' }
    $sec = if ($query['security']) { $query['security'].ToLower() } else { 'none' }
    $out = [ordered]@{ type = 'vless'; tag = 'proxy'; server = $srvHost; server_port = $port; uuid = [uri]::UnescapeDataString($userInfo) }
    if ($sec -in @('tls', 'reality')) {
        $tls = [ordered]@{ enabled = $true; server_name = $(if ($query['sni']) { $query['sni'] } else { $srvHost }) }
        if ($query['fp']) { $tls['utls'] = [ordered]@{ enabled = $true; fingerprint = $query['fp'] } }
        if ($sec -eq 'reality' -or $query['pbk']) {
            $pk = $query['pbk']
            if ($pk) { $pk = $pk.TrimEnd('=').Replace('+', '-').Replace('/', '_') }
            if ($pk) { $tls['reality'] = [ordered]@{ enabled = $true; public_key = $pk; short_id = $(if ($query['sid']) { $query['sid'] } else { '' }) } }
        }
        $out['tls'] = $tls
    }
    if ($query['flow'] -and $sec -in @('tls', 'reality')) { $out['flow'] = $query['flow'] }
    if ($net -eq 'grpc') {
        $out['transport'] = [ordered]@{ type = 'grpc'; service_name = $(if ($query['serviceName']) { $query['serviceName'] } else { '' }) }
    }
    return [PSCustomObject]@{ Name = $frag; Host = $srvHost; Port = $port; Outbound = $out; Net = $net }
}

function Read-Exact($stream, [int]$count) {
    $buf = New-Object byte[] $count
    $read = 0
    while ($read -lt $count) {
        $n = $stream.Read($buf, $read, $count - $read)
        if ($n -le 0) { throw "closed $read/$count" }
        $read += $n
    }
    return $buf
}

function Socks-Probe([int]$port, [string]$target, [int]$targetPort, [bool]$tls) {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect('127.0.0.1', $port)
    $s = $client.GetStream()
    $s.ReadTimeout = 12000; $s.WriteTimeout = 12000
    $s.Write([byte[]](5, 1, 0), 0, 3); [void](Read-Exact $s 2)
    $hb = [System.Text.Encoding]::ASCII.GetBytes($target)
    $req = New-Object System.Collections.Generic.List[byte]
    foreach ($b in [byte[]](5, 1, 0, 3, [byte]$hb.Length)) { $req.Add($b) }
    foreach ($b in $hb) { $req.Add($b) }
    $req.Add([byte][math]::Floor($targetPort / 256)); $req.Add([byte]($targetPort % 256))
    $s.Write($req.ToArray(), 0, $req.Count)
    $head = Read-Exact $s 4
    if ($head[1] -ne 0) { $client.Close(); return "socks=$($head[1])" }
    $atyp = $head[3]
    $skip = switch ($atyp) { 1 { 6 } 4 { 18 } 3 { $l = (Read-Exact $s 1)[0]; $l + 2 } default { 0 } }
    if ($skip -gt 0) { [void](Read-Exact $s $skip) }

    if ($tls) {
        $ssl = New-Object System.Net.Security.SslStream($s, $false, { param($a, $b, $c, $d) $true })
        $ssl.AuthenticateAsClient($target)
        $h = [System.Text.Encoding]::ASCII.GetBytes("HEAD / HTTP/1.1`r`nHost: $target`r`nConnection: close`r`n`r`n")
        $ssl.Write($h, 0, $h.Length)
        $buf = New-Object byte[] 128
        $n = $ssl.Read($buf, 0, 128)
        $line = [System.Text.Encoding]::ASCII.GetString($buf, 0, $n).Split("`r")[0]
        $ssl.Close(); $client.Close()
        return "TLS OK: $line"
    } else {
        $h = [System.Text.Encoding]::ASCII.GetBytes("GET / HTTP/1.0`r`nHost: $target`r`n`r`n")
        $s.Write($h, 0, $h.Length)
        $buf = New-Object byte[] 128
        $n = $s.Read($buf, 0, 128)
        $line = [System.Text.Encoding]::ASCII.GetString($buf, 0, $n).Split("`r")[0]
        $client.Close()
        return "HTTP: $line"
    }
}

$i = 0
$results = @()
foreach ($raw in $keys) {
    $i++
    $p = Parse-Vless $raw
    $name = if ($p.Name) { $p.Name } else { "key$i" }
    $cfgFile = Join-Path $OutDir "k$i.json"
    $logFile = Join-Path $OutDir "k$i.log"
    $cfg = [ordered]@{
        log       = [ordered]@{ level = 'error'; timestamp = $true }
        dns       = [ordered]@{ servers = @([ordered]@{ type = 'udp'; tag = 'dns-local'; server = '8.8.8.8' }); final = 'dns-local' }
        inbounds  = @([ordered]@{ type = 'socks'; tag = 'socks-in'; listen = '127.0.0.1'; listen_port = (11000 + $i) })
        outbounds = @($p.Outbound, [ordered]@{ type = 'direct'; tag = 'direct' })
        route     = [ordered]@{ rules = @(); final = 'proxy' }
    }
    [System.IO.File]::WriteAllText($cfgFile, ($cfg | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
    $proc = Start-Process -FilePath $singbox -ArgumentList 'run', '-c', $cfgFile -PassThru `
        -RedirectStandardOutput (Join-Path $OutDir "k$i.out") -RedirectStandardError $logFile -WindowStyle Hidden
    Start-Sleep -Milliseconds 1400
    $http = ''
    $tlsRes = ''
    if (-not $proc.HasExited) {
        try { $http = Socks-Probe (11000 + $i) 'example.com' 80 $false } catch { $http = 'http-fail: ' + $_.Exception.Message }
        try { $tlsRes = Socks-Probe (11000 + $i) $TlsTarget 443 $true } catch { $tlsRes = 'tls-fail: ' + $_.Exception.Message }
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    } else {
        $err = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
        $first = ($err -split "`n" | Where-Object { $_ -match 'FATAL|ERROR' } | Select-Object -Last 1)
        $http = 'sing-box exited: ' + ($first -replace '\x1b\[[0-9;]*m', '')
    }
    $ok = $http -match '^HTTP: HTTP/'
    $results += [PSCustomObject]@{ Key = $name; Server = "$($p.Host):$($p.Port)"; Net = $p.Net; HTTP = ($http -replace '\s+', ' '); TLS = ($tlsRes -replace '\s+', ' ') }
    Write-Host ("{0,-38} {1,-6} {2}" -f $name, $(if ($ok) { 'OK' } else { 'FAIL' }), ($http -replace '\s+', ' '))
}
$results | Format-Table -AutoSize
