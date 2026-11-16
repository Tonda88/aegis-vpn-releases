param(
  [string]$InstallDir = "$env:LOCALAPPDATA\AegisVPN-Desktop",
  [switch]$AllUsers
)
$ErrorActionPreference = 'Stop'
if ($AllUsers) { $InstallDir = "$env:ProgramFiles\AegisVPN-Desktop" }
$src = Split-Path -Parent $MyInvocation.MyCommand.Path
$appSrc = Join-Path $src 'AegisVPN-Desktop'
if (!(Test-Path $appSrc)) { $appSrc = $src }
Write-Host "Installing Aegis VPN Desktop to: $InstallDir"
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item -Path (Join-Path $appSrc '*') -Destination $InstallDir -Recurse -Force
$icon = Join-Path $InstallDir 'AegisVPN.ico'
if (!(Test-Path $icon)) { $icon = Join-Path $InstallDir 'app-icon.png' }
$iconLocation = if (Test-Path $icon) { "$icon,0" } else { "$env:SystemRoot\System32\shell32.dll,47" }
$WshShell = New-Object -ComObject WScript.Shell
$desktop = [Environment]::GetFolderPath('Desktop')
$startMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'
New-Item -ItemType Directory -Force -Path $startMenu | Out-Null
function MkShortcut($path,$target,$wd,$desc){
  $s=$WshShell.CreateShortcut($path); $s.TargetPath=$target; $s.WorkingDirectory=$wd; $s.Description=$desc; $s.IconLocation=$iconLocation; $s.Save()
}
# launchers always request admin (UAC) for TUN to work
MkShortcut (Join-Path $desktop 'Aegis VPN.lnk') (Join-Path $InstallDir 'AegisVPN.vbs') $InstallDir 'Aegis VPN (Admin)'
MkShortcut (Join-Path $startMenu 'Aegis VPN.lnk') (Join-Path $InstallDir 'AegisVPN.vbs') $InstallDir 'Aegis VPN (Admin)'
@"
Remove-Item '$InstallDir' -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item '$desktop\Aegis VPN.lnk' -Force -ErrorAction SilentlyContinue
Remove-Item '$startMenu\Aegis VPN.lnk' -Force -ErrorAction SilentlyContinue
Remove-Item '$startMenu\Aegis VPN (Admin).lnk' -Force -ErrorAction SilentlyContinue
Write-Host 'Aegis VPN Desktop removed.'
"@ | Set-Content (Join-Path $InstallDir 'Uninstall.ps1') -Encoding UTF8
Write-Host "Done. Shortcuts created (app will request Administrator/UAC on launch)."