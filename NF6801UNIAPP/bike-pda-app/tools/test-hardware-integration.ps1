param([string]$JavaDirectory='I:/Program Files/Java/jdk-21.0.11')
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$build=Join-Path $project ('unpackage/hardware-simulation/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $build | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead((Join-Path $project 'nativePlugins/Qs601Module/android/n601-release.aar'))
$jar=Join-Path $build 'release-classes.jar'
try { [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('classes.jar'),$jar) } finally { $zip.Dispose() }
$sources=@(Get-ChildItem -LiteralPath (Join-Path $project 'native-compat/simulation') -Recurse -Filter '*.java' -File | ForEach-Object { $_.FullName })
& (Join-Path $JavaDirectory 'bin/javac.exe') -encoding UTF-8 -classpath $jar -d $build @sources
if($LASTEXITCODE -ne 0) { throw '硬件集成模拟编译失败' }
# 不重新编译任何 com.bike 类；执行将被发布的 AAR 内实际接收和兼容实现。
foreach($scenario in @('scan','read-failure','write-failure','busy','disconnect','print','service-timeout','scan-print-race','read-failure-print','physical-key','control-only')) {
    & (Join-Path $JavaDirectory 'bin/java.exe') -cp ($build+';'+$jar) HardwareSimulation $scenario | Where-Object { $_ -notlike 'SCAN_TRACE=*' }
    if($LASTEXITCODE -ne 0) { throw ('硬件集成模拟失败：'+$scenario) }
}
$previousClasspath=$env:BIKE_SIMULATION_CLASSPATH
$previousJava=$env:BIKE_SIMULATION_JAVA
try {
    $env:BIKE_SIMULATION_CLASSPATH=$build+';'+$jar
    $env:BIKE_SIMULATION_JAVA=Join-Path $JavaDirectory 'bin/java.exe'
    & node --experimental-default-type=module --test (Join-Path $project 'tests/*.test.mjs')
    if($LASTEXITCODE -ne 0) { throw '实际原生回码到页面链路回放失败' }
} finally { $env:BIKE_SIMULATION_CLASSPATH=$previousClasspath; $env:BIKE_SIMULATION_JAVA=$previousJava }
Write-Output ('Simulation artifacts: '+$build)
