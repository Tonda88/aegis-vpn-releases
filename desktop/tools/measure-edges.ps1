param(
    [string]$OutDir = ""
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $PSScriptRoot '..\..\.tmp-verify' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Add-Type -AssemblyName System.Drawing

function EdgeStats([string]$file) {
    $bmp = [System.Drawing.Bitmap]::FromFile($file)
    $w = [int]$bmp.Width; $h = [int]$bmp.Height
    $samples = @()
    $cols = @(0, 1, ($w - 2))
    $rows = @(0, 1, ($h - 2))
    # outer 2 columns / rows, excluding the very last pixel (often the desktop behind a rounded corner)
    foreach ($x in $cols) { foreach ($y in (4..($h - 5))) { $samples += $bmp.GetPixel($x, $y) } }
    foreach ($y in $rows) { foreach ($x in (4..($w - 5))) { $samples += $bmp.GetPixel($x, $y) } }
    $lum = $samples | ForEach-Object { 0.299 * $_.R + 0.587 * $_.G + 0.114 * $_.B }
    $avg = ($lum | Measure-Object -Average).Average
    $max = ($lum | Measure-Object -Maximum).Maximum
    $min = ($lum | Measure-Object -Minimum).Minimum
    $bmp.Dispose()
    [PSCustomObject]@{ File = (Split-Path $file -Leaf); EdgeAvgLum = [math]::Round($avg, 1); Max = [math]::Round($max, 1); Min = [math]::Round($min, 1) }
}

EdgeStats (Join-Path $OutDir 'frame_before.png')
EdgeStats (Join-Path $OutDir 'frame_after.png')
