# Windows Server 部署

适用于本次 `C:\bike` 独立实例。管理后台和 PDA 分别由 `ebike.nfwms.com`、`pda.nfwms.com` 共用 Nginx 的 80 端口。后端和 MySQL 仅监听 `127.0.0.1`。

## 首次安装

1. 将审核过的运行环境包放到 `C:\bike\downloads`，同时提供 `manifest.json`（每条记录含 `file`、`source`、`sha256`）。安装程序核对包散列，另检查 VC++ 安装包的微软签名。
2. 发布目录采用 `C:\bike\releases\<版本>`，包含 `server\app.jar`、`web`、`pda`、`deploy`。两个前端均按根路径构建。
3. 使用管理员 PowerShell 运行 `install-runtime.ps1`，再运行 `install-services.ps1 -Release <版本>`。脚本用于首次安装，检测到已有实例、凭据或占用端口时停止；不得用来覆盖已有数据库。
4. 执行 `verify-deployment.ps1`，确认两站登录、健康检查、服务账户、监听地址和迁移结果；再从公网核对静态资源与实际页面。

本次版本：Temurin JRE 21.0.13+9、MySQL 8.4.11、Nginx 1.28.0、WinSW 2.12.0。运行环境包来自 Eclipse Adoptium、Oracle MySQL、Nginx、WinSW 和 Microsoft 官方来源。JRE 额外对照官方 SHA-256 文件验证。

## 目录及服务

- `C:\bike\current`：指向当前发布目录的 junction。
- `C:\bike\data\mysql`：数据库持久目录。
- `C:\bike\storage\production`：图片和附件持久目录。
- `C:\bike\config`：MySQL/Nginx 配置和实例凭据。
- `C:\bike\services`：服务包装程序和后端环境配置；包含凭据，不得分享或提交代码库。
- `C:\bike\logs`：应用、数据库及 Nginx 日志。
- `BikeMySQL`、`BikeServer`、`BikeNginx`：Windows 自动启动服务，使用 `NT AUTHORITY\LocalService` 运行。后端依赖数据库，Nginx 依赖后端。

初始应用用户名为 `admin`，密码保存在 `C:\bike\config\admin-access.local`，首次登录必须修改。服务器 Administrator 凭据不写入这些配置。数据库初始化 SQL 和验收 SQL 分别维护在 `ProvisioningMapper.xml` 与 `VerificationMapper.xml`；运行时只生成单个临时初始化文件，使用后按明确路径删除。

## 运维及升级

修改 Nginx 配置前保存旧文件，执行 `nginx.exe -p C:/bike/runtime/nginx-1.28.0/ -c C:/bike/config/nginx.conf -t`，通过后使用同一路径参数执行 `-s reload`。两个站点共用后端，业务数据也共用。

升级前暂停业务写入，备份数据库、附件、配置和当前版本信息，先在恢复副本验证迁移。新发布放在新的目录，停应用后切换 junction，再启动并验证。不得删除数据库目录或批量清理发布目录。数据迁移已生效时，回退应用前需先评估旧版兼容性。

当前文件不建立定时备份，也不表示真实 PDA 扫码打印已验收。部署记录列出本次实际执行的验证范围。
