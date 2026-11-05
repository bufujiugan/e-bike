$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = 'C:\bike'
$credentials = Get-Content "$root\config\credentials.json" -Raw | ConvertFrom-Json
$checks = @()
foreach ($domain in 'ebike.nfwms.com','pda.nfwms.com') {
    $headers = @{ Host = $domain }
    $homeResponse = Invoke-WebRequest 'http://127.0.0.1/' -Headers $headers -UseBasicParsing -TimeoutSec 15
    $health = Invoke-RestMethod 'http://127.0.0.1/actuator/health' -Headers $headers -TimeoutSec 15
    $login = Invoke-RestMethod 'http://127.0.0.1/api/auth/login' -Headers $headers -Method Post -ContentType 'application/json' -Body (@{username='admin';password=$credentials.admin} | ConvertTo-Json)
    if (-not $login.data.accessToken) { throw "登录验证失败：$domain" }
    $headers.Authorization = 'Bearer ' + $login.data.accessToken
    $context = Invoke-RestMethod 'http://127.0.0.1/api/auth/context' -Headers $headers
    Invoke-RestMethod 'http://127.0.0.1/api/auth/logout' -Headers $headers -Method Post | Out-Null
    $checks += [pscustomobject]@{
        domain=$domain; home=$homeResponse.StatusCode; health=$health.status;
        login='OK'; username=$context.data.username; roles=$context.data.roles;
        mustChangePassword=$context.data.mustChangePassword
    }
}
$checks | ConvertTo-Json -Depth 4
Get-CimInstance Win32_Service | Where-Object { $_.Name -in 'BikeMySQL','BikeServer','BikeNginx' } | Select-Object Name,State,StartMode,StartName | ConvertTo-Json
Get-NetTCPConnection -State Listen -LocalPort 80,3306,18080 | Select-Object LocalAddress,LocalPort | ConvertTo-Json
[xml]$mapper = Get-Content "$root\current\deploy\VerificationMapper.xml" -Raw
try {
    $env:MYSQL_PWD = $credentials.database
    & "$root\runtime\mysql-8.4.11-winx64\bin\mysql.exe" --host=127.0.0.1 --user=bike --database=bike --batch ("--execute=" + $mapper.mapper.select.InnerText)
    if ($LASTEXITCODE -ne 0) { throw '数据库验证查询失败。' }
} finally { $env:MYSQL_PWD = $null }
Write-Output 'VERIFICATION_PASSED'
