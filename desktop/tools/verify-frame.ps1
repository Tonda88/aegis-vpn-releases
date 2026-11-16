# Self-contained verification for the window-frame polish.
# Launches a throw-away Notepad, captures "before" and "after" PNGs and applies
# the same DWM attributes that tools\polish-window.ps1 uses in production.
# Only used for local verification; not shipped in the release.

param(
    [string]$OutDir = ""
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $PSScriptRoot '..\..\.tmp-verify' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Add-Type -AssemblyName System.Drawing

if (-not ('AegisWin' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class AegisWin
{
    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] private static extern bool MoveWindow(IntPtr hWnd, int x, int y, int w, int h, bool repaint);
    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }

    private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private const int DWMWA_BORDER_COLOR = 34;
    private const int DWMWA_CAPTION_COLOR = 35;
    private const int DWMWA_TEXT_COLOR = 36;
    private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private const int DWMWCP_DONOTROUND = 1;
    private const int DWMWA_COLOR_NONE = unchecked((int)0xFFFFFFFE);

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
                    if (GetWindowRect(hWnd, out r) && r.Right - r.Left > 200) { found = hWnd; return false; }
                }
            }
            catch { }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    public static void Bring(IntPtr hWnd)
    {
        ShowWindow(hWnd, 5);
        MoveWindow(hWnd, 80, 80, 760, 480, true);
        SetForegroundWindow(hWnd);
    }

    public static string Apply(IntPtr hWnd)
    {
        var log = new StringBuilder();
        int v;
        v = 1;                 log.Append("dark:").Append(DwmSetWindowAttribute(hWnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref v, 4)).Append(' ');
        v = DWMWA_COLOR_NONE;  log.Append("border:").Append(DwmSetWindowAttribute(hWnd, DWMWA_BORDER_COLOR, ref v, 4)).Append(' ');
        v = 0x090914;          log.Append("caption:").Append(DwmSetWindowAttribute(hWnd, DWMWA_CAPTION_COLOR, ref v, 4)).Append(' ');
        v = 0xF3F0FF;          log.Append("text:").Append(DwmSetWindowAttribute(hWnd, DWMWA_TEXT_COLOR, ref v, 4)).Append(' ');
        v = DWMWCP_DONOTROUND; log.Append("corner:").Append(DwmSetWindowAttribute(hWnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref v, 4));
        return log.ToString();
    }

    public static bool Snap(IntPtr hWnd, string path)
    {
        RECT r;
        if (!GetWindowRect(hWnd, out r)) return false;
        int w = r.Right - r.Left, h = r.Bottom - r.Top;
        using (var bmp = new System.Drawing.Bitmap(w, h))
        using (var g = System.Drawing.Graphics.FromImage(bmp))
        {
            g.CopyFromScreen(r.Left, r.Top, 0, 0, new System.Drawing.Size(w, h));
            bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
        }
        return true;
    }
}
'@ -ReferencedAssemblies System.Drawing
}

$p = Start-Process notepad.exe -PassThru
Start-Sleep -Milliseconds 1800
$hwnd = [AegisWin]::FindByProcess('notepad')
"hwnd=$hwnd"
if ($hwnd -eq [IntPtr]::Zero) { throw 'notepad window not found' }

[AegisWin]::Bring($hwnd)
Start-Sleep -Milliseconds 700
[void][AegisWin]::Snap($hwnd, (Join-Path $OutDir 'frame_before.png'))

"apply: " + [AegisWin]::Apply($hwnd)
Start-Sleep -Milliseconds 900
[void][AegisWin]::Snap($hwnd, (Join-Path $OutDir 'frame_after.png'))

Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
Get-ChildItem $OutDir -Filter 'frame_*.png' | Select-Object Name, Length
