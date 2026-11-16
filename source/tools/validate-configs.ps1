param(
    [string]$ConfigDir = "",
    [string]$SingBox = "",
    [string]$WorkDir = ""
)

# Defaults are resolved relative to this script, so the folder can be moved.
$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ConfigDir)) {
    $ConfigDir = Join-Path $Root "app\build\generated-test-configs\subscription"
}
if ([string]::IsNullOrWhiteSpace($SingBox)) {
    $SingBox = Join-Path $PSScriptRoot "sing-box.exe"
}
if ([string]::IsNullOrWhiteSpace($WorkDir)) {
    $WorkDir = Join-Path $PSScriptRoot "validate"
}

if (-not (Test-Path $SingBox)) {
    Write-Error "sing-box binary not found: $SingBox (put sing-box.exe into the tools folder)"
}

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

$results = @()

foreach ($file in (Get-ChildItem -Path $ConfigDir -Filter "*.json" | Sort-Object Name)) {
    $raw = Get-Content $file.FullName -Raw
    $cfg = $raw | ConvertFrom-Json

    # Replace the tun inbound with a local socks inbound so sing-box can start
    # on a desktop OS (no TUN required).
    $socks = [PSCustomObject]@{
        type         = "socks"
        tag          = "socks-in"
        listen       = "127.0.0.1"
        listen_port  = 19080
    }
    $cfg.inbounds = @($socks)

    $runFile = Join-Path $WorkDir $file.Name
    $json = $cfg | ConvertTo-Json -Depth 20
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($runFile, $json, $utf8NoBom)

    $errFile = Join-Path $WorkDir ($file.BaseName + ".err")
    $outFile = Join-Path $WorkDir ($file.BaseName + ".out")

    $p = Start-Process -FilePath $SingBox -ArgumentList "run", "-c", $runFile `
        -PassThru -RedirectStandardOutput $outFile -RedirectStandardError $errFile -WindowStyle Hidden

    Start-Sleep -Seconds 2

    $fatal = $null
    if (Test-Path $errFile) {
        $err = Get-Content $errFile -Raw
        if ($err) {
            $m = [regex]::Match($err, 'FATAL[^\r\n]*')
            if ($m.Success) { $fatal = $m.Value }
        }
    }

    if (-not $p.HasExited) {
        Stop-Process -Id $p.Id -Force
        $status = if ($fatal) { "FAIL" } else { "OK" }
        $detail = if ($fatal) { $fatal } else { "started" }
    } else {
        $status = "FAIL"
        $detail = "exited early (code $($p.ExitCode))"
        if (-not $fatal -and (Test-Path $errFile)) {
            $e = (Get-Content $errFile -Raw) -split "`n" | Where-Object { $_.Trim() } | Select-Object -First 1
            if ($e) { $detail = $e.Trim() }
        }
    }

    $results += [PSCustomObject]@{ Config = $file.Name; Status = $status; Detail = $detail }
    Write-Host ("{0,-14} {1,-6} {2}" -f $file.Name, $status, $detail)
}

Write-Host ""
$failed = @($results | Where-Object { $_.Status -eq "FAIL" })
Write-Host ("TOTAL: {0}  OK: {1}  FAIL: {2}" -f $results.Count, ($results.Count - $failed.Count), $failed.Count)
if ($failed.Count -gt 0) { exit 1 }
