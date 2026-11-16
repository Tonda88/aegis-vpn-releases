# Imports every vless:// key from a text file into the desktop app's profiles.json.
# Existing keys (same raw URL) are kept with their ids; new keys get fresh ids.
# The selected server is set to the first key whose name matches -SelectMatch.

param(
    [string]$KeyFile = '',
    [string]$SelectMatch = 'Estonia'
)
$ErrorActionPreference = 'Stop'

$prof = Join-Path $env:APPDATA 'AegisVPN-Desktop\profiles.json'
$backup = Join-Path $env:APPDATA ('AegisVPN-Desktop\profiles.json.before-import-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
Copy-Item $prof $backup -Force
"backup: $backup"

$state = [System.IO.File]::ReadAllText($prof) | ConvertFrom-Json
$existing = @{}
foreach ($s in $state.servers) { $existing[$s.raw.Trim()] = $s }

$keys = Get-Content $KeyFile -Encoding UTF8 | Where-Object { $_ -match '^vless://' }
$added = 0
$updated = 0
foreach ($raw in $keys) {
    $raw = $raw.Trim()
    $body = $raw.Substring('vless://'.Length)
    $frag = ''
    if ($body.Contains('#')) {
        $frag = [uri]::UnescapeDataString($body.Substring($body.IndexOf('#') + 1))
        $body = $body.Substring(0, $body.IndexOf('#'))
    }
    if ($body.Contains('?')) { $body = $body.Substring(0, $body.IndexOf('?')) }
    $authority = $body.Substring($body.LastIndexOf('@') + 1)
    $hostPart = $authority; $port = 443
    if ($authority.Contains(':')) {
        $hostPart = $authority.Substring(0, $authority.LastIndexOf(':'))
        $port = [int]$authority.Substring($authority.LastIndexOf(':') + 1)
    }
    if ($existing.ContainsKey($raw)) {
        # refresh the display name if the stored one is empty/broken
        $cur = $existing[$raw]
        if ([string]::IsNullOrWhiteSpace($cur.name) -and $frag) { $cur.name = $frag; $updated++ }
        continue
    }
    $profile = [ordered]@{
        id        = [guid]::NewGuid().ToString()
        name      = $frag
        protocol  = 'VLESS'
        host      = $hostPart
        port      = $port
        raw       = $raw
        createdAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    }
    $state.servers += [pscustomobject]$profile
    $existing[$raw] = $profile
    $added++
}

# pick the requested server (prefer the gRPC variant)
$target = $state.servers | Where-Object { $_.name -like "*$SelectMatch*" -and $_.raw -match 'type=grpc' } | Select-Object -First 1
if (-not $target) { $target = $state.servers | Where-Object { $_.name -like "*$SelectMatch*" } | Select-Object -First 1 }
if ($target) { $state.selectedId = $target.id }

[System.IO.File]::WriteAllText($prof, ($state | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
"added=$added updated=$updated total=$($state.servers.Count)"
"selected=$($target.name) ($($target.host):$($target.port))"
