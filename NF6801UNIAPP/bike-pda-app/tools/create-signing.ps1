param(
    [string]$SigningDirectory = 'F:\AndroidSigning\bike-pda',
    [string]$Keytool = 'I:\Program Files\Java\jdk-21.0.11\bin\keytool.exe'
)
$ErrorActionPreference = 'Stop'
$directory = [IO.Path]::GetFullPath($SigningDirectory)
$store = Join-Path $directory 'bike-pda-release.jks'
$protectedPassword = Join-Path $directory 'password.dpapi'
$certificate = Join-Path $directory 'bike-pda-release.cer'
$metadata = Join-Path $directory 'signing-public.json'
foreach ($path in @($store, $protectedPassword, $certificate, $metadata)) {
    if (Test-Path -LiteralPath $path) { throw "文件已存在，保留原文件并停止：$path" }
}
if (!(Test-Path -LiteralPath $Keytool)) { throw '找不到 keytool，请指定已安装 JDK 的 keytool.exe 路径' }
if (!(Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
# 私钥与 DPAPI 密文只允许当前 Windows 用户和 SYSTEM 访问。
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
foreach ($identity in @($sid, [Security.Principal.SecurityIdentifier]'S-1-5-18')) {
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $directory -AclObject $acl
$random = New-Object byte[] 36
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($random)
$rng.Dispose()
$plain = [Convert]::ToBase64String($random)
try {
    # 密码不进入命令行参数、日志或仓库，两个签名密码保持一致。
    $secure = ConvertTo-SecureString -String $plain -AsPlainText -Force
    $secure | ConvertFrom-SecureString | Set-Content -LiteralPath $protectedPassword -Encoding ASCII -NoNewline
    $env:BIKE_KEYTOOL_PASSWORD = $plain
    & $Keytool -genkeypair -keystore $store -storetype JKS -alias bike-pda-release -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=BIKE PDA, OU=Mobile, O=BIKE, C=CN' -storepass:env BIKE_KEYTOOL_PASSWORD -keypass:env BIKE_KEYTOOL_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw '签名生成失败；保留已有文件，请检查状态后处理，禁止覆盖重试' }
    & $Keytool -exportcert -keystore $store -alias bike-pda-release -storepass:env BIKE_KEYTOOL_PASSWORD -file $certificate
    if ($LASTEXITCODE -ne 0) { throw '公钥证书导出失败；私钥已保留' }
    $cert = New-Object Security.Cryptography.X509Certificates.X509Certificate2($certificate)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    $fingerprint = [BitConverter]::ToString($sha256.ComputeHash($cert.RawData)).Replace('-', ':')
    $sha256.Dispose()
    $public = [ordered]@{
        applicationId = 'com.bike.pda'
        keyAlias = 'bike-pda-release'
        storeFile = $store
        passwordFile = $protectedPassword
        passwordProtection = 'Windows DPAPI CurrentUser; store/key passwords are identical'
        certificateSha1 = ($cert.Thumbprint -replace '(..)(?!$)', '$1:')
        certificateSha256 = $fingerprint
        expires = $cert.NotAfter.ToString('yyyy-MM-dd')
    }
    $public | ConvertTo-Json | Set-Content -LiteralPath $metadata -Encoding UTF8
    Write-Output "已创建独立 BIKE 签名：$store"
    Write-Output "公开证书及指纹：$metadata"
    Write-Output '密码已使用当前 Windows 用户的 DPAPI 加密保存，未输出明文。'
} finally {
    Remove-Item Env:BIKE_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue
    $plain = $null
    [Array]::Clear($random, 0, $random.Length)
}
