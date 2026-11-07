param([string]$JavaDirectory = 'I:/Program Files/Java/jdk-21.0.11')
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$build = Join-Path $project ('unpackage/hardware-tests/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $build | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $project 'native-compat/tests') -Recurse -Filter '*.java' -File | ForEach-Object { $_.FullName })
$sources += Join-Path $project 'native-compat/src/com/bike/pda/hardware/Qs608Protocol.java'
$sources += Join-Path $project 'native-compat/src/com/bike/pda/hardware/Qs608Transport.java'
$sources += Join-Path $project 'native-compat/src/com/bike/pda/hardware/Qs608ReceiveBuffer.java'
& (Join-Path $JavaDirectory 'bin/javac.exe') -encoding UTF-8 -d $build @sources
if ($LASTEXITCODE -ne 0) { throw '硬件协议测试编译失败' }
& (Join-Path $JavaDirectory 'bin/java.exe') -cp $build Qs608ProtocolTest
if ($LASTEXITCODE -ne 0) { throw '协议/点阵测试失败' }
& (Join-Path $JavaDirectory 'bin/java.exe') -cp $build Qs608ReceiveBufferTest
if ($LASTEXITCODE -ne 0) { throw '接收分帧测试失败' }
foreach ($scenario in @('success', 'busy', 'write-error')) {
    & (Join-Path $JavaDirectory 'bin/java.exe') -cp $build Qs608TransportTest $scenario
    if ($LASTEXITCODE -ne 0) { throw ('串口测试失败：' + $scenario) }
}
