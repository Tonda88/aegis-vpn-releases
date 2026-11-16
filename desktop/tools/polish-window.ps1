# Blends the native Windows frame into the app background so no light border /
# title-bar strip remains around the dark window.
#
# Usage: polish-window.ps1 -WindowTitle "Aegis VPN"
#   or:  polish-window.ps1 -ProcessName "java"
#
# Currently supported (no-op on systems that reject the DWM attributes):
#   * DWMWA_BORDER_COLOR   -> removes the light 1px window border (Win11 22000+)
#   * DWMWA_CAPTION_COLOR  -> paints the native title bar with the app colour
#   * DWMWA_TEXT_COLOR     -> light text on that title bar
#   * DWMWA_WINDOW_CORNER_PREFERENCE -> square corners (no light outline)
# On Windows 10 all calls fail softly and the window is left untouched.

param(
    [string]$WindowTitle = 'Aegis VPN',
    [string]$ProcessName = ''
)

$ErrorActionPreference = 'Stop'

if (-not ('AegisWindowPolisher' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class AegisWindowPolisher
{
    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowTextLength(IntPtr hWnd);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    // DWMWA_USE_IMMERSIVE_DARK_MODE: 20 on Win10 2004+, 19 on older builds.
    private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private const int DWMWA_BORDER_COLOR = 34;
    private const int DWMWA_CAPTION_COLOR = 35;
    private const int DWMWA_TEXT_COLOR = 36;
    private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;

    private const int DWMWCP_DONOTROUND = 1;
    private const int DWMWA_COLOR_NONE = unchecked((int)0xFFFFFFFE);
    private const int DWMWA_COLOR_DEFAULT = unchecked((int)0xFFFFFFFF);

    private static int _caption = 0x090914;
    private static int _text = 0xF3F0FF;

    public static IntPtr FindByTitle(string title)
    {
        return FindWindow(null, title);
    }

    public static IntPtr FindByProcess(string processName)
    {
        if (string.IsNullOrWhiteSpace(processName)) return IntPtr.Zero;
        IntPtr found = IntPtr.Zero;
        EnumWindows((hWnd, lParam) =>
        {
            if (!IsWindowVisible(hWnd)) return true;
            uint pid;
            GetWindowThreadProcessId(hWnd, out pid);
            try
            {
                var p = System.Diagnostics.Process.GetProcessById((int)pid);
                if (string.Equals(p.ProcessName, processName, StringComparison.OrdinalIgnoreCase)
                    && GetWindowTextLength(hWnd) > 0)
                {
                    found = hWnd;
                    return false;
                }
            }
            catch { }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    public static string Apply(IntPtr hWnd, int captionArgb, int textArgb)
    {
        if (hWnd == IntPtr.Zero) return "window not found";
        _caption = captionArgb & 0xFFFFFF;
        _text = textArgb & 0xFFFFFF;

        var results = new System.Text.StringBuilder();

        // 1) native dark title bar
        Set(hWnd, DWMWA_USE_IMMERSIVE_DARK_MODE, 1, results, "dark");
        // 2) no light border around the window
        Set(hWnd, DWMWA_BORDER_COLOR, DWMWA_COLOR_NONE, results, "border");
        // 3) caption in app colour
        Set(hWnd, DWMWA_CAPTION_COLOR, _caption, results, "caption");
        // 4) readable caption text
        Set(hWnd, DWMWA_TEXT_COLOR, _text, results, "text");
        // 5) square corners
        Set(hWnd, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_DONOTROUND, results, "corner");

        return results.ToString();
    }

    private static void Set(IntPtr hWnd, int attr, int value, System.Text.StringBuilder log, string name)
    {
        int v = value;
        try
        {
            int hr = DwmSetWindowAttribute(hWnd, attr, ref v, sizeof(int));
            log.Append(name).Append(hr == 0 ? ":ok " : (":hr=" + hr + " "));
        }
        catch (Exception ex)
        {
            log.Append(name).Append(":err=").Append(ex.Message).Append(' ');
        }
    }
}
'@
}

$hwnd = [IntPtr]::Zero
if ($ProcessName -ne '') {
    $hwnd = [AegisWindowPolisher]::FindByProcess($ProcessName)
} else {
    $hwnd = [AegisWindowPolisher]::FindByTitle($WindowTitle)
}

if ($hwnd -eq [IntPtr]::Zero) {
    Write-Output "polish: window not found"
    exit 1
}

$result = [AegisWindowPolisher]::Apply($hwnd, 0x090914, 0xF3F0FF)
Write-Output "polish: hwnd=$hwnd $result"
exit 0
