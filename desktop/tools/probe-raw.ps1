# Raw dump of the first bytes returned through a SOCKS5 tunnel.

param(
    [int]$SocksPort = 2081,
    [string]$Target = 'cloudflare.com',
    [int]$TargetPort = 443
)

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect('127.0.0.1', $SocksPort)
$s = $client.GetStream()
$s.ReadTimeout = 12000; $s.WriteTimeout = 12000
$s.Write([byte[]](5, 1, 0), 0, 3)
$g = New-Object byte[] 2; [void]$s.Read($g, 0, 2)

$hb = [System.Text.Encoding]::ASCII.GetBytes($Target)
$h = New-Object System.Collections.Generic.List[byte]
foreach ($b in [byte[]](5, 1, 0, 3, [byte]$hb.Length)) { $h.Add($b) }
foreach ($b in $hb) { $h.Add($b) }
$h.Add([byte][math]::Floor($TargetPort / 256)); $h.Add([byte]($TargetPort % 256))
$s.Write($h.ToArray(), 0, $h.Count)
$rep = New-Object byte[] 4
[void]$s.Read($rep, 0, 4)
"CONNECT reply byte = $($rep[1])"

# send a TLS ClientHello-shaped payload; a real proxy passes it to the target
$hello = New-Object byte[] 5
$hello[0] = 22; $hello[1] = 3; $hello[2] = 3; $hello[3] = 0; $hello[4] = 0
$s.Write($hello, 0, $hello.Length)

$buf = New-Object byte[] 300
$n = 0
try { $n = $s.Read($buf, 0, 300) } catch { "read failed: " + $_.Exception.Message }
"bytes read = $n"
if ($n -gt 0) {
    $text = [System.Text.Encoding]::UTF8.GetString($buf, 0, $n)
    $printable = -join ($text.ToCharArray() | ForEach-Object { if ([int]$_ -ge 32 -and [int]$_ -lt 127) { $_ } else { '.' } })
    "raw(printable): $printable"
    "hex: " + (($buf[0..([math]::Min($n - 1, 32))] | ForEach-Object { '{0:X2}' -f $_ }) -join ' ')
}
$client.Close()
