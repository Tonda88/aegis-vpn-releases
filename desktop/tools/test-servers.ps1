# Builds a sing-box config for every stored profile and actually tests each one:
# starts sing-box on its own local SOCKS port, opens a real TCP connection
# through it to a well-known host and reports the outcome.
#
# Read-only with respect to the app: uses a temporary folder and its own ports.

param(
    [string]$OutDir = ""
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $PSScriptRoot '..\..\.vpn-test' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$profilesPath = Join-Path $env:APPDATA 'AegisVPN-Desktop\profiles.json'
$profiles = Get-Content $profilesPath -Raw | ConvertFrom-Json
$singbox = $null
foreach ($cand in @(
        (Join-Path $PSScriptRoot 'sing-box.exe'),
        (Join-Path $env:LOCALAPPDATA 'AegisVPN-Desktop\sing-box.exe'),
        'C:\dsh-test\sing-box.exe')) {
    if (Test-Path $cand) { $singbox = $cand; break }
}
if (-not $singbox) { throw 'sing-box.exe not found' }
"sing-box: $singbox"

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
    return [PSCustomObject]@{ Name = $frag; Host = $srvHost; Port = $port; Outbound = $out }
}

function Run-Test($profile, [int]$socksPort, [string]$name) {
    $cfg = [ordered]@{
        log       = [ordered]@{ level = 'error'; timestamp = $true }
        dns       = [ordered]@{ servers = @([ordered]@{ type = 'udp'; tag = 'dns-local'; server = '8.8.8.8' }); final = 'dns-local' }
        inbounds  = @([ordered]@{ type = 'socks'; tag = 'socks-in'; listen = '127.0.0.1'; listen_port = $socksPort })
        outbounds = @($profile.Outbound, [ordered]@{ type = 'direct'; tag = 'direct' })
        route     = [ordered]@{ rules = @(); final = 'proxy' }
    }
    $cfgFile = Join-Path $OutDir "$name.json"
    $logFile = Join-Path $OutDir "$name.log"
    $json = $cfg | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($cfgFile, $json, (New-Object System.Text.UTF8Encoding($false)))
    $p = Start-Process -FilePath $singbox -ArgumentList 'run', '-c', $cfgFile `
        -PassThru -RedirectStandardOutput (Join-Path $OutDir "$name.out") `
        -RedirectStandardError $logFile -WindowStyle Hidden
    Start-Sleep -Milliseconds 1800
    if ($p.HasExited) {
        $err = (Get-Content $logFile -Raw -ErrorAction SilentlyContinue)
        return [PSCustomObject]@{ Name = $name; OK = $false; Detail = ('exited: ' + ($err -split "`n" | Where-Object { $_.Trim() } | Select-Object -Last 1)) }
    }
    $ok = $false; $detail = ''
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $client.Connect('127.0.0.1', $socksPort)
        $s = $client.GetStream()
        $s.ReadTimeout = 12000; $s.WriteTimeout = 12000
        $req = [byte[]](0x05, 0x01, 0x00)
        $s.Write($req, 0, 3); $s.Flush()
        $resp = New-Object byte[] 2; [void]$s.Read($resp, 0, 2)
        $hostBytes = [System.Text.Encoding]::ASCII.GetBytes('example.com')
        $hdr = New-Object System.Collections.Generic.List[byte]
        $hdr.AddRange([byte[]](0x05, 0x01, 0x00, 0x03, [byte]$hostBytes.Length))
        $hdr.AddRange($hostBytes)
        $hdr.AddRange([byte[]](0x00, 0x50))   # port 80
        $s.Write($hdr.ToArray(), 0, $hdr.Count); $s.Flush()
        $r2 = New-Object byte[] 4; [void]$s.Read($r2, 0, 4)
        $rep = if ($r2.Length -gt 1) { $r2[1] } else { 255 }
        if ($rep -eq 0) {
            $s.Write([System.Text.Encoding]::ASCII.GetBytes("HEAD / HTTP/1.0`r`nHost: example.com`r`n`r`n"), 0, 37); $s.Flush()
            Start-Sleep -Milliseconds 900
            $buf = New-Object byte[] 128
            try { $n = $s.Read($buf, 0, 128); $ok = ($n -gt 0) } catch { $ok = $false }
            $detail = "socks-rep=$rep bytes=$n"
        } else { $detail = "socks-rep=$rep (0=ok)" }
        $client.Close()
    } catch { $detail = 'conn error: ' + $_.Exception.Message }
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    return [PSCustomObject]@{ Name = $name; OK = $ok; Detail = $detail }
}

$i = 0
$results = @()
foreach ($s in $profiles.servers) {
    $i++
    if ($s.protocol -ne 'VLESS') { continue }
    $parsed = Parse-Vless $s.raw
    $label = "$i-$($parsed.Host)-$($parsed.Port)"
    $res = Run-Test $parsed (10800 + $i) $label
    $res | Add-Member -NotePropertyName Server -NotePropertyValue ("{0}:{1}" -f $parsed.Host, $parsed.Port)
    $results += $res
    Write-Host ("{0,-40} OK={1}  {2}" -f $label, $res.OK, $res.Detail)
}
$results | Format-Table Server, OK, Detail -AutoSize
