# Tests each stored VLESS profile through sing-box: starts sing-box with a local
# SOCKS inbound, performs a SOCKS5 handshake plus a real HTTP request through it
# and reports whether actual payload bytes came back.
#
# Read-only for the app: temporary folder, dedicated ports, sing-box stopped after
# every profile.

param(
    [string]$OutDir = ""
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $PSScriptRoot '..\..\.vpn-test' }
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$profilesPath = Join-Path $env:APPDATA 'AegisVPN-Desktop\profiles.json'
$profiles = Get-Content $profilesPath -Raw | ConvertFrom-Json
$singbox = Join-Path $PSScriptRoot 'sing-box.exe'
if (-not (Test-Path $singbox)) { $singbox = Join-Path $env:LOCALAPPDATA 'AegisVPN-Desktop\sing-box.exe' }

function Parse-Vless([string]$raw) {
    $body = $raw.Substring('vless://'.Length)
    $frag = ''
    if ($body.Contains('#')) { $frag = [uri]::UnescapeDataString($body.Substring($body.IndexOf('#') + 1)); $body = $body.Substring(0, $body.IndexOf('#')) }
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

function Test-Profile($profile, [int]$socksPort, [int]$seq) {
    $name = "p$seq"
    $cfgFile = Join-Path $OutDir "$name.json"
    $logFile = Join-Path $OutDir "$name.log"
    $cfg = [ordered]@{
        log       = [ordered]@{ level = 'error'; timestamp = $true }
        dns       = [ordered]@{ servers = @([ordered]@{ type = 'udp'; tag = 'dns-local'; server = '8.8.8.8' }); final = 'dns-local' }
        inbounds  = @([ordered]@{ type = 'socks'; tag = 'socks-in'; listen = '127.0.0.1'; listen_port = $socksPort })
        outbounds = @($profile.Outbound, [ordered]@{ type = 'direct'; tag = 'direct' })
        route     = [ordered]@{ rules = @(); final = 'proxy' }
    }
    $json = $cfg | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($cfgFile, $json, (New-Object System.Text.UTF8Encoding($false)))

    $proc = Start-Process -FilePath $singbox -ArgumentList 'run', '-c', $cfgFile -PassThru `
        -RedirectStandardOutput (Join-Path $OutDir "$name.out") -RedirectStandardError $logFile -WindowStyle Hidden
    Start-Sleep -Milliseconds 1500
    $result = [PSCustomObject]@{ Profile = $profile.Name; Server = "$($profile.Host):$($profile.Port)"; Net = $profile.Net; OK = $false; Reply = ''; Bytes = 0; Ms = 0; Error = '' }

    if (-not $proc.HasExited) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $client = New-Object System.Net.Sockets.TcpClient
            $client.Connect('127.0.0.1', $socksPort)
            $s = $client.GetStream()
            $s.ReadTimeout = 20000; $s.WriteTimeout = 20000
            $s.Write([byte[]](5, 1, 0), 0, 3)
            $resp = New-Object byte[] 2
            if ($s.Read($resp, 0, 2) -ne 2) { throw 'no socks greeting reply' }
            $hostBytes = [System.Text.Encoding]::ASCII.GetBytes('example.com')
            $hdr = New-Object System.Collections.Generic.List[byte]
            foreach ($b in [byte[]](5, 1, 0, 3, [byte]$hostBytes.Length)) { $hdr.Add($b) }
            foreach ($b in $hostBytes) { $hdr.Add($b) }
            foreach ($b in [byte[]](0, 80)) { $hdr.Add($b) }
            $s.Write($hdr.ToArray(), 0, $hdr.Count)
            $r2 = New-Object byte[] 10
            $n2 = $s.Read($r2, 0, 4)
            if ($n2 -lt 4) { throw "short socks reply ($n2)" }
            $result.Reply = [string]$r2[1]
            if ($r2[1] -eq 0) {
                $req = [System.Text.Encoding]::ASCII.GetBytes("GET / HTTP/1.0`r`nHost: example.com`r`nUser-Agent: probe`r`n`r`n")
                $s.Write($req, 0, $req.Length)
                $buf = New-Object byte[] 512
                try { $n = $s.Read($buf, 0, 512) } catch { $n = -1 }
                $result.Bytes = [int]$n
                if ($n -gt 0) { $result.OK = $true }
            }
            $client.Close()
        } catch {
            $result.Error = $_.Exception.Message
        } finally {
            $sw.Stop(); $result.Ms = $sw.ElapsedMilliseconds
        }
    } else {
        $result.Error = 'sing-box exited'
    }
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    $err = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
    if ($err -and $result.Error -eq '') {
        $fatal = ($err -split "`n" | Where-Object { $_ -match 'FATAL|ERROR' } | Select-Object -Last 1)
        if ($fatal) { $result.Error = ($fatal -replace '\x1b\[[0-9;]*m', '').Trim() }
    }
    return $result
}

$i = 0
$results = @()
foreach ($s in $profiles.servers) {
    if ($s.protocol -ne 'VLESS') { continue }
    $i++
    $parsed = Parse-Vless $s.raw
    $r = Test-Profile $parsed (10900 + $i) $i
    $results += $r
    Write-Host ("{0,-46} {1,-4} {2}" -f $r.Server, ($(if ($r.OK) { 'OK' } else { 'FAIL' })), $r.Error)
}
$results | Format-Table Profile, Server, Net, OK, Reply, Bytes, Ms -AutoSize
