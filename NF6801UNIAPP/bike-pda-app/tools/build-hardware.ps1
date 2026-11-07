param(
    [string]$JavaDirectory = 'I:/Program Files/Java/jdk-21.0.11',
    [string]$AndroidJar = 'F:/android-sdk/platforms/android-29/android.jar',
    [string]$AsmJar = 'C:/Users/J/.m2/repository/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar'
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$vendor = [IO.Path]::GetFullPath((Join-Path $project '../vendor-source/QS_PDA_601-608/nativePlugins/Qs601Module/android/n601-release.aar'))
$build = Join-Path $project ('unpackage/hardware-build/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $build | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($vendor)
try { [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), (Join-Path $build 'vendor.jar')) } finally { $archive.Dispose() }
$classPath = $AndroidJar + ';' + (Join-Path $build 'vendor.jar')
$sources = @(Get-ChildItem -LiteralPath (Join-Path $project 'native-compat/src/com/bike/pda/hardware') -Filter '*.java' -File | ForEach-Object { $_.FullName })
& (Join-Path $JavaDirectory 'bin/javac.exe') -encoding UTF-8 -source 8 -target 8 -classpath $classPath -d $build @sources
if ($LASTEXITCODE -ne 0) { throw '608 原生兼容层编译失败' }
& (Join-Path $JavaDirectory 'bin/javac.exe') -encoding UTF-8 -classpath $AsmJar -d $build (Join-Path $project 'native-compat/PatchQs608.java')
if ($LASTEXITCODE -ne 0) { throw 'AAR 适配工具编译失败' }
$target = Join-Path $project 'nativePlugins/Qs601Module/android/n601-release.aar'
& (Join-Path $JavaDirectory 'bin/java.exe') -classpath ($build + ';' + $AsmJar) PatchQs608 $vendor $build $target
if ($LASTEXITCODE -ne 0) { throw '608 AAR 适配失败' }
Get-FileHash -LiteralPath $target -Algorithm SHA256 | Select-Object Path, Hash
