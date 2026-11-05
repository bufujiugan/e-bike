param([string]$Root = 'C:\bike')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
if ($Root -ne 'C:\bike') { throw '此实例配置限定在 C:\bike，其他路径须先调整 nginx.conf。' }
$manifest = Get-Content "$Root\downloads\manifest.json" -Raw | ConvertFrom-Json
foreach ($artifact in $manifest) {
    $path = Join-Path "$Root\downloads" $artifact.file
    if ((Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $artifact.sha256) {
        throw "安装包校验失败：$($artifact.file)"
    }
}
Write-Output 'All dependency hashes verified'
$signature = Get-AuthenticodeSignature "$Root\downloads\vc_redist.x64.exe"
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Microsoft Corporation') {
    throw 'VC++ 运行库的微软签名验证失败。'
}
$process = Start-Process "$Root\downloads\vc_redist.x64.exe" -ArgumentList '/install','/quiet','/norestart' -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -notin 0,1638,3010) { throw "VC++ 安装失败：$($process.ExitCode)" }
New-Item -ItemType Directory -Path "$Root\runtime" -Force | Out-Null
foreach ($artifact in $manifest | Where-Object { $_.file.EndsWith('.zip') }) {
    Write-Output "Extracting $($artifact.file)"
    Expand-Archive -LiteralPath (Join-Path "$Root\downloads" $artifact.file) -DestinationPath "$Root\runtime"
}
$ErrorActionPreference = 'Continue'
& "$Root\runtime\jdk-21.0.13+9-jre\bin\java.exe" -version 2>&1 | ForEach-Object { "$_" }
$javaExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($javaExit -ne 0) { throw 'Java 运行库验证失败。' }
& "$Root\runtime\mysql-8.4.11-winx64\bin\mysqld.exe" --version
if ($LASTEXITCODE -ne 0) { throw 'MySQL 运行库验证失败。' }
Write-Output 'RUNTIME_READY'
