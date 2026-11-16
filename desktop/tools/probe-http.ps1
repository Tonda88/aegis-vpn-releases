# Correct SOCKS5 HTTP probe: consumes the full CONNECT reply, then sends a plain
# HTTP/1.0 request and prints the HTTP status line (or the failure).

param(
    [int]$SocksPort = 2082,
    [string]$Target = 'ya.ru',
    [int]$TargetPort = 80
)

$ErrorActionPreference = 'Stop'
$client = New-Object System.Net.Sockets.TcpClient
$client.Connect('127.0.0.1', $SocksPort)
$s = $client.GetStream()
$s.ReadTimeout = 15000; $s.WriteTimeout = 15000

function Read-Exact($stream, [int]$count) {
    $buf = New-Object byte[] $count
    $read = 0
    while ($read -lt $count) {
        $n = $stream.Read($buf, $read, $count - $read)
        if ($n -le 0) { throw "closed after $read/$count" }
        $read += $n
    }
    return $buf
}

$s.Write([byte[]](5, 1, 0), 0, 3)
[void](Read-Exact $s 2)

$hb = [System.Text.Encoding]::ASCII.GetBytes($Target)
$req = New-Object System.Collections.Generic.List[byte]
foreach ($b in [byte[]](5, 1, 0, 3, [byte]$hb.Length)) { $req.Add($b) }
foreach ($b in $hb) { $req.Add($b) }
$req.Add([byte][math]::Floor($TargetPort / 256)); $req.Add([byte]($TargetPort % 256))
$s.Write($req.ToArray(), 0, $req.Count)

$head = Read-Exact $s 4
$rep = $head[1]; $atyp = $head[3]
if ($rep -ne 0) { "CONNECT failed reply=$rep"; $client.Close(); exit 2 }
$skip = switch ($atyp) {
    1 { 6 }
    4 { 18 }
    3 { $len = (Read-Exact $s 1)[0]; $len + 2 }
    default { throw "atyp $atyp" }
}
[void](Read-Exact $s $skip)

$http = [System.Text.Encoding]::ASCII.GetBytes("GET / HTTP/1.0`r`nHost: $Target`r`nUser-Agent: probe`r`n`r`n")
$s.Write($http, 0, $http.Length)
$buf = New-Object byte[] 512
$n = 0
try { $n = $s.Read($buf, 0, 512) } catch { "read failed: " + $_.Exception.Message }
"bytes=$n"
if ($n -gt 0) {
    $text = [System.Text.Encoding]::UTF8.GetString($buf, 0, $n)
    "first line: " + $text.Split("`r")[0]
    "head: " + ($text.Substring(0, [math]::Min(120, $text.Length)) -replace "`r?`n", ' | ')
}
$client.Close()
