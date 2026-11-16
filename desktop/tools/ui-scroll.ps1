# Sends mouse-wheel scroll to the Aegis VPN window at window-relative coordinates.

param(
    [long]$Handle = 0,
    [int]$RelX = 170,
    [int]$RelY = 400,
    [int]$Notches = 10
)
$ErrorActionPreference = 'Stop'
if (-not ('AegisWheel' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class AegisWheel
{
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, int dx, int dy, int data, UIntPtr extra);

    public static string Scroll(IntPtr hWnd, int relX, int relY, int notches)
    {
        ShowWindow(hWnd, 9);
        SetForegroundWindow(hWnd);
        RECT r;
        if (!GetWindowRect(hWnd, out r)) return "no rect";
        int x = r.Left + relX;
        int y = r.Top + relY;
        SetCursorPos(x, y);
        System.Threading.Thread.Sleep(150);
        for (int i = 0; i < Math.Abs(notches); i++)
        {
            int delta = notches > 0 ? -120 : 120; // negative = scroll down
            mouse_event(0x0800, 0, 0, delta, UIntPtr.Zero);
            System.Threading.Thread.Sleep(80);
        }
        return string.Format("scrolled {0} notches at {1},{2}", notches, x, y);
    }
}
'@
}
if ($Handle -eq 0) { throw 'handle required' }
[AegisWheel]::Scroll([IntPtr]$Handle, $RelX, $RelY, $Notches)
