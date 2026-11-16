$ErrorActionPreference = 'Stop'

$desk = Split-Path -Parent $MyInvocation.MyCommand.Path
$dist = Join-Path $desk 'AegisVPN-Desktop'
$inst = Join-Path $desk 'AegisVPN-Installer\AegisVPN-Desktop'
$rel  = Join-Path $desk 'release-desktop-v1.1.1'
$jar  = Join-Path $desk 'build\compose\jars\AegisVPN-windows-x64-1.1.1.jar'

if (!(Test-Path $jar)) { throw "Jar not found: $jar. Build :packageUberJarForCurrentOS first." }

Copy-Item $jar (Join-Path $dist 'AegisVPN-Desktop.jar') -Force
Copy-Item $jar (Join-Path $inst 'AegisVPN-Desktop.jar') -Force

$stage = Join-Path $rel 'stage\AegisVPN-Desktop'
if (Test-Path (Join-Path $rel 'stage')) { Remove-Item (Join-Path $rel 'stage') -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Get-ChildItem $dist -Force | Where-Object { $_.Name -ne 'AegisVPN-Desktop.jar' } | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $stage $_.Name) -Recurse -Force
}
Copy-Item $jar (Join-Path $stage 'AegisVPN-Desktop.jar') -Force

$zip = Join-Path $rel 'AegisVPN-Desktop-1.1.1-windows-x64.zip'
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $stage -DestinationPath $zip -Force
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()

$notes = (Get-Content (Join-Path $rel 'notes.txt') -Raw -Encoding UTF8).Trim()
$json = [ordered]@{
    version     = '1.1.1'
    versionCode = 3
    notes       = $notes
    url         = 'https://github.com/Tonda88/aegis-vpn-releases/releases/download/desktop-v1.1.1/AegisVPN-Desktop-1.1.1-windows-x64.zip'
    sha256      = $hash
} | ConvertTo-Json -Depth 5
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $rel 'latest-desktop.json'), $json, $utf8)
[IO.File]::WriteAllText((Join-Path $desk 'latest-desktop.json'), $json, $utf8)
[IO.File]::WriteAllText((Join-Path (Split-Path $desk -Parent) 'latest-desktop.json'), $json, $utf8)
"SHA256=$hash"
"SIZE_MB=$([math]::Round((Get-Item $zip).Length / 1MB, 2))"
