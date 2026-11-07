param(
    [string]$Cli = 'F:\bike\tmp\hbuilderx-5.33\portable\HBuilderX\cli.exe'
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$release = Get-Content -LiteralPath (Join-Path $project 'android-release.json') -Raw | ConvertFrom-Json
$manifest = Get-Content -LiteralPath (Join-Path $project 'manifest.json') -Raw | ConvertFrom-Json
$started = Get-Date
if (!(Test-Path -LiteralPath $Cli)) { throw '请先安装官方 HBuilderX 5.33，并指定 Cli 路径' }
if (!$manifest.appid -or $manifest.appid -eq '__UNI__9B0CDE4') { throw '需要项目自有 AppID' }
if ($manifest.'app-plus'.renderer -ne 'native') { throw '只允许原生渲染工程打包' }
if (!(Test-Path -LiteralPath $release.storeFile)) { throw 'BIKE 专用签名文件不存在' }
$secure = Get-Content -LiteralPath $release.protectedPasswordFile -Raw | ConvertTo-SecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$configuration = Join-Path ([IO.Path]::GetDirectoryName($release.storeFile)) ('pack-' + [Guid]::NewGuid().ToString('N') + '.json')
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    # 官方安心打包：业务资源和签名留在本机，云端构建模块与原生插件。
    $options = @{
        project = $project; platform = 'android'; safemode = $true; iscustom = $false
        sourceMap = $false; isconfusion = $false; splashads = $false; rpads = $false; unimpads = $false
        android = @{
            packagename = $release.applicationId; androidpacktype = '0'
            certalias = $release.keyAlias; certfile = $release.storeFile
            certpassword = $password; storepassword = $password
        }
    }
    # 临时配置仅存在于已限制 ACL 的签名目录；密码不出现在命令行参数。
    $json = $options | ConvertTo-Json -Depth 5
    [IO.File]::WriteAllText($configuration, $json, (New-Object Text.UTF8Encoding($false)))
    & $Cli pack --config $configuration 2>&1 | ForEach-Object {
        $line = $_.ToString().Replace($password, '[已隐藏签名密码]')
        Write-Output ($line -replace '(?i)(token|oauthToken)=([^&\s"<>]+)', '$1=[已隐藏]')
    }
    $packExitCode = $LASTEXITCODE
    if ($packExitCode -ne 0) { throw "HBuilderX 打包命令失败，退出码 $packExitCode" }
    $outputDirectory = Join-Path $project 'unpackage/release/apk'
    $candidates = @(Get-ChildItem -LiteralPath $outputDirectory -Filter '*.apk' -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $started -and $_.Name -notmatch '_cm\.apk$' })
    if (!$candidates.Count) { throw '本轮未生成 APK；请检查登录、插件安装或打包日志，不能将命令退出 0 视为打包成功' }
    $candidates | ForEach-Object { Write-Output ('本轮 APK 候选产物：' + $_.FullName) }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $password = $null; $json = $null; $options = $null
    if (Test-Path -LiteralPath $configuration) { Remove-Item -LiteralPath $configuration }
}
