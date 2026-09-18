param(
    [ValidateSet('server', 'web', 'h5')][string]$Component = 'server'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$configPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $configPath)) { throw '请先运行 python tools/configure.py 并配置数据库。' }
foreach ($line in Get-Content -LiteralPath $configPath -Encoding UTF8) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
foreach ($name in @('BIKE_DB_PASSWORD', 'BIKE_JWT_SECRET', 'BIKE_ADMIN_PASSWORD')) {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value) -or $value -eq 'CHANGE_ME') { throw "缺少有效配置：$name" }
}
Push-Location (Join-Path $projectRoot "bike-$Component")
try {
    if ($Component -eq 'server') {
        $jarPath = Join-Path (Get-Location) 'target/bike-server-0.1.0-SNAPSHOT.jar'
        if (-not (Test-Path -LiteralPath $jarPath)) { throw '请先运行 mvn -f bike-server/pom.xml verify。' }
        & java -jar $jarPath
    } else {
        & pnpm dev --host 127.0.0.1
    }
    if ($LASTEXITCODE -ne 0) { throw "$Component 进程退出，代码 $LASTEXITCODE" }
} finally { Pop-Location }
