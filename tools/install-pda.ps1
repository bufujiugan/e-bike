param([string]$Adb = 'adb', [string]$Serial = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
[xml]$pdaManifest = Get-Content (Join-Path $projectRoot 'bike-pda-android/app/src/main/AndroidManifest.xml') -Raw
$pdaVersion = $pdaManifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android') -replace '-608$', ''
$apk = Join-Path $projectRoot "output/apk/BIKE-PDA-608-$pdaVersion.apk"
if (!(Test-Path $apk)) { throw '请先运行 tools/build-pda.ps1 生成安装包' }
$devices = & $Adb devices
if ($LASTEXITCODE -ne 0) { throw 'ADB 不可用' }
if (!$Serial) {
    $ready = @($devices | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($ready.Count -ne 1) { throw '请通过USB连接手持机并允许调试授权；多台设备时请指定 -Serial。未执行安装。' }
    $Serial = $ready[0]
}
$sdk = (& $Adb -s $Serial shell getprop ro.build.version.sdk).Trim()
$abis = (& $Adb -s $Serial shell getprop ro.product.cpu.abilist).Trim()
if ($sdk -notmatch '^\d+$' -or [int]$sdk -lt 23) { throw '需要 Android 6.0 / API 23 及以上' }
if ($abis -notmatch 'armeabi-v7a|arm64-v8a') { throw "设备架构不在官方SDK支持范围：$abis" }
Write-Output "目标设备：$Serial · Android API $sdk · $abis"
& $Adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw '安装失败；保留原应用，不执行卸载或清除数据' }
& $Adb -s $Serial shell am start -n com.bike.pda608/.MainActivity
if ($LASTEXITCODE -ne 0) { throw '已安装，但启动失败，请检查 logcat' }
