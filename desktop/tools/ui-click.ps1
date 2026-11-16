# Sends a real mouse click to the Aegis VPN window at window-relative coordinates,
# so the auto-reconnect behaviour can be exercised on a live window.

param(
    [int]$RelX = 100,
    [int]$RelY = 231,
    [long]$Handle = 0
)

$ErrorActionPreference = 'Stop'
if (-not ('AegisClick' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class AegisClick
{
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hWnd, ref POINT p);

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X, Y; }

    public static string ClickAt(IntPtr hWnd, int relX, int relY)
    {
        ShowWindow(hWnd, 9); // SW_RESTORE
        SetForegroundWindow(hWnd);
        RECT r;
        if (!GetWindowRect(hWnd, out r)) return "no rect";
        int x = r.Left + relX;
        int y = r.Top + relY;
        SetCursorPos(x, y);
        System.Threading.Thread.Sleep(120);
        mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero); // left down
        System.Threading.Thread.Sleep(60);
        mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero); // left up
        return string.Format("clicked {0},{1} (window {2},{3} size {4}x{5})", x, y, r.Left, r.Top, r.Right - r.Left, r.Bottom - r.Top);
    }
}
'@
}

$hwnd = if ($Handle -ne 0) { [IntPtr]$Handle } else { [IntPtr]::Zero }
if ($hwnd -eq [IntPtr]::Zero) { throw 'handle required' }
[AegisClick]::ClickAt($hwnd, $RelX, $RelY)
