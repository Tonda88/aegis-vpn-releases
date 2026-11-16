# Packages desktop 1.1.7: stage -> zip -> checksums -> latest-desktop.json.
# ASCII-only script; the repository path is resolved dynamically.
$ErrorActionPreference = 'Stop'

$ver  = '1.1.7'
$code = 9
$app  = 'C:\aegis-dist-117'

$docs = [Environment]::GetFolderPath('MyDocuments')
$repoRoot = (Get-ChildItem -LiteralPath $docs -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'AegisVPN\desktop') } |
    Select-Object -First 1).FullName
$repo = Join-Path $repoRoot 'AegisVPN'
$desk = Join-Path $repo 'desktop'
$rel  = Join-Path $desk "release-desktop-v$ver"

if (-not (Test-Path (Join-Path $app 'AegisVPN-Desktop.jar'))) { throw "jar missing in $app" }

$tmp = Join-Path $rel 'stage\AegisVPN-Desktop'
if (Test-Path (Join-Path $rel 'stage')) { Remove-Item (Join-Path $rel 'stage') -Recurse -Force }
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Copy-Item (Join-Path $app '*') $tmp -Recurse -Force

$zip = Join-Path $rel "AegisVPN-Desktop-$ver-windows-x64.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $tmp -DestinationPath $zip -Force
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
$size = [math]::Round((Get-Item $zip).Length / 1MB, 2)

$notes = (Get-Content (Join-Path $rel 'notes.txt') -Raw -Encoding UTF8).Trim()
$json = [ordered]@{
    version     = $ver
    versionCode = $code
    notes       = $notes
    url         = "https://github.com/Tonda88/aegis-vpn-releases/releases/download/desktop-v$ver/AegisVPN-Desktop-$ver-windows-x64.zip"
    sha256      = $hash
} | ConvertTo-Json -Depth 5
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $rel 'latest-desktop.json'), $json, $utf8)
[IO.File]::WriteAllText((Join-Path $desk 'latest-desktop.json'), $json, $utf8)
[IO.File]::WriteAllText((Join-Path $repo 'latest-desktop.json'), $json, $utf8)

$checks = "Aegis VPN Desktop $ver - SHA-256`r`n`r`n$hash  AegisVPN-Desktop-$ver-windows-x64.zip`r`n"
[IO.File]::WriteAllText((Join-Path $rel 'CHECKSUMS-desktop.txt'), $checks, $utf8)

"REPO=$repo"
"ZIP=$zip"
"SHA256=$hash"
"SIZE_MB=$size"
