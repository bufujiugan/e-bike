param(
    [string]$ToolchainDirectory = '',
    [string]$JdkDirectory = $env:JAVA_HOME,
    [switch]$SkipFrontend
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$androidProject = Join-Path $projectRoot 'bike-pda-android'
if (!$ToolchainDirectory) { $ToolchainDirectory = Join-Path $projectRoot 'output/android-toolchain/android-11' }
$ToolchainDirectory = [IO.Path]::GetFullPath($ToolchainDirectory)
$javaBin = Join-Path $JdkDirectory 'bin'
foreach ($file in @('aapt.exe','aidl.exe','d8.bat','zipalign.exe','apksigner.bat','android.jar','framework.aidl')) {
    if (!(Test-Path (Join-Path $ToolchainDirectory $file))) { throw "缺少 $file。先执行 tools/prepare-pda-toolchain.ps1，或指定包含 Android 30 SDK 与 Build Tools 30.0.3 的目录。" }
}
function Invoke-Checked([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "命令失败：$Command（退出码 $LASTEXITCODE）" }
}
if (!$SkipFrontend) {
    Invoke-Checked 'pnpm' @('--dir', (Join-Path $projectRoot 'bike-h5'), 'install', '--frozen-lockfile')
    Invoke-Checked 'pnpm' @('--dir', (Join-Path $projectRoot 'bike-h5'), 'build:pda')
}
$dist = Join-Path $projectRoot 'bike-h5/dist'
if (!(Test-Path "$dist/index.html") -or !(Select-String -LiteralPath "$dist/index.html" -SimpleMatch '/__bike_pda__/' -Quiet)) { throw '请先执行 pnpm --dir bike-h5 build:pda' }
$build = Join-Path $projectRoot ('output/pda-build/' + [guid]::NewGuid().ToString('N'))
foreach ($dir in @('generated','classes','dex','assets/www')) { New-Item -ItemType Directory -Force (Join-Path $build $dir) | Out-Null }
Copy-Item "$dist/*" "$build/assets/www" -Recurse
$main = Join-Path $androidProject 'app/src/main'
$sdkJar = Join-Path $androidProject 'app/libs/qs601sdk.jar'
Invoke-Checked "$ToolchainDirectory/aidl.exe" @("-p$ToolchainDirectory/framework.aidl", "-I$main/aidl", "-o$build/generated", "$main/aidl/com/sdk/ICommService.aidl")
Invoke-Checked "$ToolchainDirectory/aapt.exe" @('package','-f','-M',"$main/AndroidManifest.xml",'-S',"$main/res",'-A',"$build/assets",'-I',"$ToolchainDirectory/android.jar",'-J',"$build/generated",'-F',"$build/unsigned.apk")
$sources = @(Get-ChildItem "$main/java","$build/generated" -Filter '*.java' -Recurse | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
[IO.File]::WriteAllLines("$build/sources.txt", $sources, [Text.UTF8Encoding]::new($false))
Invoke-Checked "$javaBin/javac.exe" @('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',"$ToolchainDirectory/core-lambda-stubs.jar;$ToolchainDirectory/android.jar",'-classpath',$sdkJar,'-d',"$build/classes","@$build/sources.txt")
Invoke-Checked "$javaBin/jar.exe" @('cf',"$build/classes.jar",'-C',"$build/classes",'.')
Invoke-Checked "$javaBin/java.exe" @('-cp',"$ToolchainDirectory/lib/d8.jar",'com.android.tools.r8.D8','--release','--min-api','23','--lib',"$ToolchainDirectory/android.jar",'--output',"$build/dex","$build/classes.jar",$sdkJar)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$build/unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try {
    Get-ChildItem "$build/dex" -Filter '*.dex' | ForEach-Object { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $_.Name) | Out-Null }
    foreach ($abi in @('armeabi-v7a','arm64-v8a')) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$main/jniLibs/$abi/libserial_port.so", "lib/$abi/libserial_port.so") | Out-Null
    }
} finally { $zip.Dispose() }
Invoke-Checked "$ToolchainDirectory/zipalign.exe" @('-f','4',"$build/unsigned.apk","$build/aligned.apk")
# 独立适配版使用本机持久签名，密码及私钥不入库、不输出。后续升级必须备份并复用。
$signConfig = Join-Path $androidProject 'signing.local'
$keyStore = Join-Path $androidProject 'pda608-release.jks'
if (!(Test-Path $signConfig)) {
    if (Test-Path $keyStore) { throw '签名配置缺失，拒绝覆盖已有私钥。请恢复 signing.local。' }
    $secret = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    @{ password = $secret } | ConvertTo-Json | Set-Content -LiteralPath $signConfig -Encoding UTF8
}
$env:BIKE_PDA_SIGN_PASSWORD = (Get-Content $signConfig -Raw | ConvertFrom-Json).password
try {
    if (!(Test-Path $keyStore)) {
        Invoke-Checked "$javaBin/keytool.exe" @('-genkeypair','-keystore',$keyStore,'-storepass:env','BIKE_PDA_SIGN_PASSWORD','-keypass:env','BIKE_PDA_SIGN_PASSWORD','-alias','bike-pda608','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=BIKE PDA 608, O=BIKE, C=CN')
    }
    $deliverables = Join-Path $projectRoot 'output/apk'
    New-Item -ItemType Directory -Force $deliverables | Out-Null
    [xml]$pdaManifest = Get-Content "$main/AndroidManifest.xml" -Raw
    $pdaVersion = $pdaManifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android') -replace '-608$', ''
    $apk = Join-Path $deliverables "BIKE-PDA-608-$pdaVersion.apk"
    Invoke-Checked "$javaBin/java.exe" @('-jar',"$ToolchainDirectory/lib/apksigner.jar",'sign','--ks',$keyStore,'--ks-key-alias','bike-pda608','--ks-pass','env:BIKE_PDA_SIGN_PASSWORD','--key-pass','env:BIKE_PDA_SIGN_PASSWORD','--out',$apk,"$build/aligned.apk")
    Invoke-Checked "$javaBin/java.exe" @('-jar',"$ToolchainDirectory/lib/apksigner.jar",'verify','--verbose','--print-certs',$apk)
    Invoke-Checked "$ToolchainDirectory/zipalign.exe" @('-c','4',$apk)
    (Get-FileHash $apk -Algorithm SHA256).Hash | Set-Content "$apk.sha256" -Encoding ASCII
    Write-Output "已生成：$apk"
} finally { Remove-Item Env:BIKE_PDA_SIGN_PASSWORD -ErrorAction SilentlyContinue }
