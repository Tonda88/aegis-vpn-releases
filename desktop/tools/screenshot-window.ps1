# Captures a window (by title or process name) to a PNG file.
# Used to verify the window frame polish; not part of the shipped application.

param(
    [string]$WindowTitle = '',
    [string]$ProcessName = '',
    [long]$Handle = 0,
    [string]$Out = "$env:TEMP\aegis-shot.png"
)

$ErrorActionPreference = 'Stop'

if (-not ('AegisShot' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class AegisShot
{
    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);

    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    public static IntPtr FindByProcess(string processName)
    {
        IntPtr found = IntPtr.Zero;
        EnumWindows((hWnd, lParam) =>
        {
            if (!IsWindowVisible(hWnd)) return true;
            uint pid;
            GetWindowThreadProcessId(hWnd, out pid);
            try
            {
                var p = System.Diagnostics.Process.GetProcessById((int)pid);
                if (string.Equals(p.ProcessName, processName, StringComparison.OrdinalIgnoreCase))
                {
                    RECT r;
                    if (GetWindowRect(hWnd, out r) && r.Right - r.Left > 200)
                    {
                        found = hWnd;
                        return false;
                    }
                }
            }
            catch { }
            return true;
        }, IntPtr.Zero);
        return found;
    }
}
'@
}

Add-Type -AssemblyName System.Drawing

if ($Handle -ne 0) { $hwnd = [IntPtr]$Handle }
elseif ($ProcessName -ne '') { $hwnd = [AegisShot]::FindByProcess($ProcessName) }
else { $hwnd = [AegisShot]::FindWindow($null, $WindowTitle) }

if ($hwnd -eq [IntPtr]::Zero) { Write-Output "shot: window not found"; exit 1 }

[void][AegisShot]::ShowWindow($hwnd, 5)
[void][AegisShot]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 900

$r = New-Object AegisShot+RECT
[void][AegisShot]::GetWindowRect($hwnd, [ref]$r)
$w = $r.Right - $r.Left
$h = $r.Bottom - $r.Top
Write-Output "shot: hwnd=$hwnd rect=$($r.Left),$($r.Top),$w x $h"

$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.Left, $r.Top, 0, 0, (New-Object System.Drawing.Size $w, $h))
$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "shot: saved $Out"
