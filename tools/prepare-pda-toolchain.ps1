param([string]$Destination = '')
$ErrorActionPreference = 'Stop'
if (!$Destination) { $Destination = Join-Path (Split-Path $PSScriptRoot -Parent) 'output/android-toolchain' }
New-Item -ItemType Directory -Force $Destination | Out-Null
# 来源为 Google Android SDK 仓库 repository2-1.xml；下载后核对仓库公布的散列。
$archives = @(
    @{ Name='build-tools.zip'; Url='https://dl.google.com/android/repository/91936d4ee3ccc839f0addd53c9ebf087b1e39251.build-tools_r30.0.3-windows.zip'; Sha1='FC165C721B8D2DA55E6FEDE467526C81F562BE7B' },
    @{ Name='platform.zip'; Url='https://dl.google.com/android/repository/platform-30_r03.zip'; Sha1='E7C6280901DCFA511AF098D67DD88C4DFCBC6EA2' }
)
foreach ($archive in $archives) {
    $path = Join-Path $Destination $archive.Name
    if (!(Test-Path $path)) { Invoke-WebRequest -UseBasicParsing -Uri $archive.Url -OutFile $path }
    if ((Get-FileHash $path -Algorithm SHA1).Hash -ne $archive.Sha1) { throw "下载文件校验失败：$path" }
    Expand-Archive -LiteralPath $path -DestinationPath $Destination -Force
}
Write-Output "工具链已准备：$Destination/android-11"
