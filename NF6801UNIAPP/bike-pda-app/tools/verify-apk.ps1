param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [string]$BuildTools = 'F:\android-sdk\build-tools\29.0.2',
    [string]$JavaDirectory = 'I:\Program Files\Java\jdk-21.0.11'
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$release = Get-Content -LiteralPath (Join-Path $project 'android-release.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$sourceManifest = Get-Content -LiteralPath (Join-Path $project 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$publicCertificate = Get-Content -LiteralPath (Join-Path ([IO.Path]::GetDirectoryName($release.storeFile)) 'signing-public.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaDirectory
    # 旧版 bat 依赖已移除的 find_java.bat，可能静默退出；直接调用官方校验器。
    $javaExecutable = Join-Path $JavaDirectory 'bin/java.exe'
    $signerJar = Join-Path $BuildTools 'lib/apksigner.jar'
    if (!(Test-Path -LiteralPath $javaExecutable) -or !(Test-Path -LiteralPath $signerJar)) { throw '缺少 Java 或 apksigner.jar' }
    $signature = & $javaExecutable -jar $signerJar verify --verbose --print-certs $apkPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'APK 签名验证失败' }
    $signatureText = $signature -join "`n"
    if ($signatureText -notmatch '(?m)^Verifies\s*$') { throw '签名校验器未返回成功结果' }
    $expected = $publicCertificate.certificateSha256.Replace(':','').ToLowerInvariant()
    if ($signatureText -notmatch ('(?i)certificate SHA-256 digest:\s*' + [regex]::Escape($expected))) { throw 'APK 签名与 BIKE 专用证书不一致' }
    $badging = & (Join-Path $BuildTools 'aapt.exe') dump badging $apkPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw '无法读取 APK 信息' }
    $badgingText = $badging -join "`n"
    if ($badgingText -notmatch ("package: name='" + [regex]::Escape($release.applicationId) + "'")) { throw 'APK 包名不正确' }
    if ($badgingText -notmatch ("versionCode='" + [regex]::Escape($sourceManifest.versionCode) + "'")) { throw 'APK versionCode 不一致' }
    if ($badgingText -notmatch ("versionName='" + [regex]::Escape($sourceManifest.versionName) + "'")) { throw 'APK versionName 不一致' }
    if ($badgingText -match 'application-debuggable') { throw 'APK 启用了 debuggable，不是发布配置' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($apkPath)
    try {
        $prefix = 'assets/apps/' + $sourceManifest.appid + '/www/'
        $manifestEntry = $zip.GetEntry($prefix + 'manifest.json')
        if (!$manifestEntry) { throw 'APK 缺少本项目资源 manifest' }
        $reader = New-Object IO.StreamReader($manifestEntry.Open())
        try { $compiled = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        if ($compiled.id -ne $sourceManifest.appid -or $compiled.plus.renderer -ne 'native') { throw 'APK 应用身份或 native 渲染配置不正确' }
        if ($compiled.version.name -ne $sourceManifest.versionName -or [string]$compiled.version.code -ne [string]$sourceManifest.versionCode) { throw 'APK 内嵌业务资源版本不一致' }
        $pages = Get-Content -LiteralPath (Join-Path $project 'pages.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($page in $pages.pages) {
            if (!$zip.GetEntry($prefix + $page.path + '.js')) { throw ('APK 缺少原生页面：' + $page.path) }
        }
        if (!$zip.GetEntry('lib/arm64-v8a/libzyapi_common.so')) { throw 'APK 缺少厂商 arm64-v8a 硬件库' }
        if (@($zip.Entries | Where-Object { $_.FullName -match '\.(jks|keystore|dpapi)$' }).Count) { throw 'APK 不得包含签名私钥或本地密码文件' }
        if (@($zip.Entries | Where-Object { $_.FullName.StartsWith($prefix) -and $_.FullName -match '\.html?$' }).Count) { throw '业务资源中出现网页文件，需要检查原生渲染要求' }
        $hasModule = $false
        $has608 = $false
        $nativeFixMarkers = @('Lcom/bike/pda/hardware/Qs608Protocol;', 'Lcom/bike/pda/hardware/Qs608Transport;', 'preparePrint', 'onDecodedScan', 'rasterize')
        if ([int]$sourceManifest.versionCode -ge 103) {
            $nativeFixMarkers += @('Lcom/bike/pda/hardware/Qs608Receiver;', 'Lcom/bike/pda/hardware/Qs608ReceiveBuffer;', 'readLoop', 'onReceiveFailure')
        }
        $foundFixMarkers = @{}
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notmatch '^classes\d*\.dex$') { continue }
            $memory = New-Object IO.MemoryStream
            $inputStream = $entry.Open()
            try {
                $inputStream.CopyTo($memory)
                $dexText = [Text.Encoding]::ASCII.GetString($memory.ToArray())
                foreach ($testMarker in @('LHardwareSimulation;', 'LQs608ReceiveBufferTest;', 'LQs608ProtocolTest;', 'LQs608TransportTest;', 'LScanReceptionAudit;')) {
                    if ($dexText.Contains($testMarker)) { throw ('APK混入测试类：'+$testMarker) }
                }
                if ($dexText.Contains('Lcom/qs/n601/UpdateModule;')) { $hasModule = $true }
                $expectedCompat = if ([int]$sourceManifest.versionCode -ge 103) { '608-3' } elseif ([int]$sourceManifest.versionCode -ge 102) { '608-2' } else { '608-1' }
                if ($dexText.Contains('Lcom/bike/pda/hardware/Qs608Hardware;') -and $dexText.Contains($expectedCompat)) { $has608 = $true }
                foreach ($marker in $nativeFixMarkers) { if ($dexText.Contains($marker)) { $foundFixMarkers[$marker] = $true } }
            } finally { $inputStream.Dispose(); $memory.Dispose() }
        }
        if (!$hasModule) { throw 'APK 中未找到 Qs601Module 对应的原生类' }
        if ([int]$sourceManifest.versionCode -ge 101 -and !$has608) { throw 'APK 缺少本轮 608 硬件兼容层，可能命中旧插件缓存' }
        if ([int]$sourceManifest.versionCode -ge 102) {
            foreach ($marker in $nativeFixMarkers) { if (!$foundFixMarkers[$marker]) { throw ('APK 缺少 1.0.2 修复标识：' + $marker) } }
        }
        if (!$zip.GetEntry('lib/arm64-v8a/libserial_port.so')) { throw 'APK 缺少厂商串口库' }
        if ([int]$sourceManifest.versionCode -ge 101) {
            $settingsReader = New-Object IO.StreamReader($zip.GetEntry($prefix + 'pages/settings/settings.js').Open())
            try { $settingsSource = $settingsReader.ReadToEnd() } finally { $settingsReader.Dispose() }
            if (!$settingsSource.Contains('608_80X100') -or !$settingsSource.Contains('hardwareStatus') -or !$settingsSource.Contains('BIKE-PDA-SELF-TEST')) { throw 'APK 设置页缺少本轮硬件自检或608版式' }
            if ([int]$sourceManifest.versionCode -ge 102 -and (!$settingsSource.Contains($expectedCompat) -or !$settingsSource.Contains('preparePrint') -or !$settingsSource.Contains('##55'))) { throw 'APK 设置页未包含当前原生状态/控制帧过滤逻辑' }
        }
    } finally { $zip.Dispose() }
    [ordered]@{
        apk = $apkPath; applicationId = $release.applicationId; appid = $sourceManifest.appid
        versionName = $sourceManifest.versionName; versionCode = $sourceManifest.versionCode; sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
        signingCertificate = $publicCertificate.certificateSha256
        nativePages = $pages.pages.Count; hardwareModule = 'Qs601Module'; abi = 'arm64-v8a'
        staticVerification = 'PASS'; realDeviceAcceptance = 'NOT_RUN'
    } | ConvertTo-Json
} finally { $env:JAVA_HOME = $previousJava }
