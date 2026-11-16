# Correct SOCKS5 probe: consumes the whole CONNECT reply, then performs a real
# HTTPS request through the tunnel and reports the HTTP status line.

param(
    [int]$SocksPort = 2081,
    [string]$Target = 'cloudflare.com',
    [int]$TargetPort = 443
)

$ErrorActionPreference = 'Stop'
$client = New-Object System.Net.Sockets.TcpClient
$client.Connect('127.0.0.1', $SocksPort)
$s = $client.GetStream()
$s.ReadTimeout = 20000; $s.WriteTimeout = 20000

function Read-Exact($stream, [int]$count) {
    $buf = New-Object byte[] $count
    $read = 0
    while ($read -lt $count) {
        $n = $stream.Read($buf, $read, $count - $read)
        if ($n -le 0) { throw "stream closed after $read/$count bytes" }
        $read += $n
    }
    return $buf
}

$s.Write([byte[]](5, 1, 0), 0, 3)
$greet = Read-Exact $s 2
if ($greet[0] -ne 5) { throw "bad socks greeting: $($greet[0])" }

$hb = [System.Text.Encoding]::ASCII.GetBytes($Target)
$req = New-Object System.Collections.Generic.List[byte]
foreach ($b in [byte[]](5, 1, 0, 3, [byte]$hb.Length)) { $req.Add($b) }
foreach ($b in $hb) { $req.Add($b) }
$req.Add([byte][math]::Floor($TargetPort / 256)); $req.Add([byte]($TargetPort % 256))
$s.Write($req.ToArray(), 0, $req.Count)

$head = Read-Exact $s 4
$rep = $head[1]
$atyp = $head[3]
if ($rep -ne 0) { $client.Close(); "CONNECT failed, reply=$rep"; exit 2 }

# consume BND.ADDR + BND.PORT depending on address type
$skip = switch ($atyp) {
    1 { 4 + 2 }
    4 { 16 + 2 }
    3 { $len = (Read-Exact $s 1)[0]; $len + 2 }
    default { throw "unknown atyp $atyp" }
}
[void](Read-Exact $s $skip)
"CONNECT ok (socks reply=0, atyp=$atyp)"

$tls = New-Object System.Net.Security.SslStream($s, $false, { param($a, $b, $c, $d) $true })
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$tls.AuthenticateAsClient($Target)
$sw.Stop()
"TLS: $($tls.SslProtocol) / $($tls.CipherAlgorithm) / cert=$($tls.RemoteCertificate.Subject) / $($sw.ElapsedMilliseconds) ms"

$http = [System.Text.Encoding]::ASCII.GetBytes("HEAD / HTTP/1.1`r`nHost: $Target`r`nConnection: close`r`n`r`n")
$tls.Write($http, 0, $http.Length)
$buf = New-Object byte[] 512
$n = $tls.Read($buf, 0, 512)
$status = [System.Text.Encoding]::ASCII.GetString($buf, 0, $n).Split("`r")[0]
"HTTP status: $status"
$tls.Close(); $client.Close()
exit 0
