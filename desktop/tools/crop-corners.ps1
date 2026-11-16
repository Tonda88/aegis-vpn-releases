param(
    [string]$In = "",
    [string]$OutDir = "",
    [int]$Size = 150,
    [int]$Scale = 5
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $PSScriptRoot '..\..\.tmp-verify' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Add-Type -AssemblyName System.Drawing

$bmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $In))
$w = $bmp.Width; $h = $bmp.Height
$regions = @{
    'tl' = @(0, 0)
    'tr' = @(($w - $Size), 0)
    'bl' = @(0, ($h - $Size))
    'br' = @(($w - $Size), ($h - $Size))
}
foreach ($k in $regions.Keys) {
    $x = [int]$regions[$k][0]; $y = [int]$regions[$k][1]
    $crop = New-Object System.Drawing.Bitmap $Size, $Size
    $g = [System.Drawing.Graphics]::FromImage($crop)
    $g.DrawImage($bmp, (New-Object System.Drawing.Rectangle 0, 0, $Size, $Size),
        (New-Object System.Drawing.Rectangle $x, $y, $Size, $Size), [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    $big = New-Object System.Drawing.Bitmap ($Size * $Scale), ($Size * $Scale)
    $g2 = [System.Drawing.Graphics]::FromImage($big)
    $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $g2.DrawImage($crop, 0, 0, $big.Width, $big.Height)
    $g2.Dispose()
    $big.Save((Join-Path $OutDir "corner_$k.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $big.Dispose(); $crop.Dispose()
}
$bmp.Dispose()
Get-ChildItem $OutDir -Filter 'corner_*.png' | Select-Object Name, Length
