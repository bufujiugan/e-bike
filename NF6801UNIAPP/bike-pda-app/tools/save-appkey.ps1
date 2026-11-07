$ErrorActionPreference = 'Stop'
$destination = 'F:\AndroidSigning\bike-pda\dcloud-appkey.dpapi'
if (Test-Path -LiteralPath $destination) { throw 'AppKey 文件已存在，停止以保留既有配置' }
$value = Get-Clipboard -Raw
if ($value -notmatch '^[A-Za-z0-9+/_=-]{24,}$') { throw '剪贴板内容不是预期的 AppKey，未保存任何内容' }
$secure = ConvertTo-SecureString -String $value -AsPlainText -Force
$secure | ConvertFrom-SecureString | Set-Content -LiteralPath $destination -Encoding ASCII -NoNewline
$value = $null
Write-Output 'AppKey 已使用当前 Windows 用户 DPAPI 加密保存，未输出明文。'
