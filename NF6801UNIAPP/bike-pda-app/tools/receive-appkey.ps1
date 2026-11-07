$ErrorActionPreference = 'Stop'
$destination = 'F:\AndroidSigning\bike-pda\dcloud-appkey.dpapi'
if (Test-Path -LiteralPath $destination) { throw 'Existing AppKey is preserved' }
$value = [Console]::In.ReadToEnd().Trim()
if ($value -notmatch '^[A-Za-z0-9+/_=-]{24,}$') { throw 'Invalid AppKey format' }
$secure = ConvertTo-SecureString -String $value -AsPlainText -Force
$encrypted = ConvertFrom-SecureString -SecureString $secure
$stream = [IO.File]::Open($destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    $bytes = [Text.Encoding]::ASCII.GetBytes($encrypted)
    $stream.Write($bytes, 0, $bytes.Length)
} finally { $stream.Dispose(); $value = $null }
Write-Output 'Saved'
