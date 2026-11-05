param([string]$Release = '20261105-423ca81')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = 'C:\bike'
$releasePath = "$root\releases\$Release"
if (Test-Path "$root\current") { throw 'current 已存在，请使用升级流程，禁止覆盖已有部署。' }
if (Get-Service BikeMySQL,BikeServer,BikeNginx -ErrorAction SilentlyContinue) { throw 'BIKE 服务已存在，停止首次安装。' }
if (Test-Path "$root\config\credentials.json") { throw '实例凭据已存在，禁止重新初始化数据库。' }
if (Get-NetTCPConnection -LocalPort 80,3306,18080 -State Listen -ErrorAction SilentlyContinue) { throw '部署端口已被使用。' }

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}
function Write-Utf8File([string]$Path, [string]$Content) {
    [System.IO.File]::WriteAllText($Path, $Content, (New-Object System.Text.UTF8Encoding($false)))
}
function Assert-Exit([string]$Operation) {
    if ($LASTEXITCODE -ne 0) { throw "$Operation 失败，退出码 $LASTEXITCODE" }
}
function Wait-Healthy {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try {
            if ((Invoke-RestMethod 'http://127.0.0.1:18080/actuator/health' -TimeoutSec 3).status -eq 'UP') { return }
        } catch { Start-Sleep -Seconds 2 }
    }
    throw '后端健康检查超时，请查看日志。'
}

# 服务使用本机低权限账户，凭据和运行目录不向普通桌面用户开放。
& icacls.exe $root /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-19:(OI)(CI)RX' | Out-Null
Assert-Exit '设置部署目录权限'
foreach ($directory in 'data\mysql','logs','storage\production','runtime\nginx-1.28.0\logs','runtime\nginx-1.28.0\temp') {
    New-Item -ItemType Directory -Path "$root\$directory" -Force | Out-Null
    & icacls.exe "$root\$directory" /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
    Assert-Exit '设置服务写入目录权限'
}
$credentials = @{ database = (New-RandomSecret); root = (New-RandomSecret); jwt = (New-RandomSecret); admin = (New-RandomSecret) }
Write-Utf8File "$root\config\credentials.json" ($credentials | ConvertTo-Json)
Write-Utf8File "$root\config\admin-access.local" "URL=http://ebike.nfwms.com`r`nUSERNAME=admin`r`nINITIAL_PASSWORD=$($credentials.admin)`r`n"
foreach ($protectedFile in 'credentials.json','admin-access.local') {
    & icacls.exe "$root\config\$protectedFile" /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
    Assert-Exit '设置凭据文件权限'
}
$mysql = "$root\runtime\mysql-8.4.11-winx64\bin\mysqld.exe"
$mysqlConfig = @"
[mysqld]
basedir=C:/bike/runtime/mysql-8.4.11-winx64
datadir=C:/bike/data/mysql
bind-address=127.0.0.1
port=3306
mysqlx=0
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
default-time-zone=+08:00
max_connections=50
innodb_buffer_pool_size=256M
log-error=C:/bike/logs/mysql-error.log
"@
Write-Utf8File "$root\config\my.ini" $mysqlConfig
& $mysql "--defaults-file=$root\config\my.ini" --initialize-insecure
Assert-Exit 'MySQL 数据目录初始化'
[xml]$mapper = Get-Content "$releasePath\deploy\ProvisioningMapper.xml" -Raw
$initializeSql = $mapper.mapper.update.InnerText.Replace('${ROOT_PASSWORD}', $credentials.root).Replace('${APP_PASSWORD}', $credentials.database)
Write-Utf8File "$root\config\bootstrap.local" $initializeSql.Trim()
Write-Utf8File "$root\config\my.ini" ($mysqlConfig + "`r`ninit-file=C:/bike/config/bootstrap.local`r`n")
& $mysql --install BikeMySQL "--defaults-file=$root\config\my.ini"
Assert-Exit '注册 MySQL 服务'
& sc.exe config BikeMySQL obj= 'NT AUTHORITY\LocalService' | Out-Null
Assert-Exit '设置 MySQL 服务账户'
& sc.exe failure BikeMySQL reset= 86400 actions= restart/5000/restart/15000/restart/60000 | Out-Null
Start-Service BikeMySQL
Start-Sleep -Seconds 4
if ((Get-Service BikeMySQL).Status -ne 'Running') { throw 'MySQL 首次启动失败。' }
# 初始化文件只执行一次，随后删除这个明确路径的临时文件。
Write-Utf8File "$root\config\my.ini" $mysqlConfig
Stop-Service BikeMySQL
Remove-Item -LiteralPath 'C:\bike\config\bootstrap.local'
Start-Service BikeMySQL

New-Item -ItemType Junction -Path "$root\current" -Target $releasePath | Out-Null
$backendEnvironment = @{
    SERVER_ADDRESS = '127.0.0.1'; BIKE_SERVER_PORT = '18080';
    BIKE_DB_URL = 'jdbc:mysql://127.0.0.1:3306/bike?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true';
    BIKE_DB_USERNAME = 'bike'; BIKE_DB_PASSWORD = $credentials.database;
    BIKE_JWT_SECRET = $credentials.jwt; BIKE_ADMIN_PASSWORD = $credentials.admin;
    BIKE_FLYWAY_LOCATIONS = 'classpath:db/migration-prod';
    BIKE_PRODUCTION_FILE_ROOT = 'C:/bike/storage/production';
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '12'; SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE = '2'
}
$environmentXml = ($backendEnvironment.GetEnumerator() | ForEach-Object {
    '<env name="' + $_.Key + '" value="' + [System.Security.SecurityElement]::Escape($_.Value) + '"/>'
}) -join "`r`n"
$serverXml = @"
<service>
 <id>BikeServer</id><name>BIKE Application Server</name>
 <description>BIKE backend API service</description>
 <executable>C:\bike\runtime\jdk-21.0.13+9-jre\bin\java.exe</executable>
 <arguments>-Xms128m -Xmx768m -Duser.timezone=Asia/Shanghai -jar C:\bike\current\server\app.jar</arguments>
 <workingdirectory>C:\bike\current</workingdirectory>
 <startmode>Automatic</startmode><delayedAutoStart>true</delayedAutoStart>
 <depend>BikeMySQL</depend><stoptimeout>45 sec</stoptimeout>
 <serviceaccount><domain>NT AUTHORITY</domain><user>LocalService</user></serviceaccount>
 <logpath>C:\bike\logs</logpath><log mode="roll-by-size"><sizeThreshold>10240</sizeThreshold><keepFiles>8</keepFiles></log>
 <onfailure action="restart" delay="10 sec"/>
 $environmentXml
</service>
"@
Write-Utf8File "$root\services\BikeServer.xml" $serverXml
Copy-Item "$root\downloads\WinSW-x64.exe" "$root\services\BikeServer.exe"
& "$root\services\BikeServer.exe" install
Assert-Exit '注册后端服务'
Start-Service BikeServer
Wait-Healthy

Copy-Item "$releasePath\deploy\nginx.conf" "$root\config\nginx.conf"
$nginxXml = @"
<service>
 <id>BikeNginx</id><name>BIKE Nginx</name>
 <description>BIKE admin and PDA HTTP sites</description>
 <executable>C:\bike\runtime\nginx-1.28.0\nginx.exe</executable>
 <startarguments>-p C:/bike/runtime/nginx-1.28.0/ -c C:/bike/config/nginx.conf</startarguments>
 <stopexecutable>C:\bike\runtime\nginx-1.28.0\nginx.exe</stopexecutable>
 <stoparguments>-p C:/bike/runtime/nginx-1.28.0/ -c C:/bike/config/nginx.conf -s quit</stoparguments>
 <workingdirectory>C:\bike\runtime\nginx-1.28.0</workingdirectory>
 <startmode>Automatic</startmode><delayedAutoStart>true</delayedAutoStart>
 <depend>BikeServer</depend><stoptimeout>30 sec</stoptimeout>
 <serviceaccount><domain>NT AUTHORITY</domain><user>LocalService</user></serviceaccount>
 <logpath>C:\bike\logs</logpath><log mode="roll-by-size"><sizeThreshold>10240</sizeThreshold><keepFiles>8</keepFiles></log>
 <onfailure action="restart" delay="10 sec"/>
</service>
"@
Write-Utf8File "$root\services\BikeNginx.xml" $nginxXml
Copy-Item "$root\downloads\WinSW-x64.exe" "$root\services\BikeNginx.exe"
$ErrorActionPreference = 'Continue'
& "$root\runtime\nginx-1.28.0\nginx.exe" -p C:/bike/runtime/nginx-1.28.0/ -c C:/bike/config/nginx.conf -t 2>&1 | ForEach-Object { "$_" }
$ErrorActionPreference = 'Stop'
Assert-Exit 'Nginx 配置检查'
& "$root\services\BikeNginx.exe" install
Assert-Exit '注册 Nginx 服务'
Start-Service BikeNginx
if (-not (Get-NetFirewallRule -Name 'Bike-HTTP-80' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name 'Bike-HTTP-80' -DisplayName 'BIKE HTTP 80' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 80 | Out-Null
}
Write-Output 'BIKE_SERVICES_READY'
Get-Service BikeMySQL,BikeServer,BikeNginx | Format-Table Name,Status,StartType
