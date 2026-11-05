# 阿里云 Windows 实例部署记录（2026-11-05）

## 访问入口

- 管理后台：`http://ebike.nfwms.com`
- PDA 网页：`http://pda.nfwms.com`
- 两个域名均解析到 `59.110.166.139`，共用 Nginx 的 TCP 80 端口，以 `server_name` 区分站点。
- 实例：`i-2ze5xv8sh0zix3w4szuc`，北京地域，Windows Server 2022 Datacenter，2 vCPU / 4 GiB，公网带宽 1 Mbps。

## 发布与持久化

应用源码提交为 `423ca816a2aef96f423ee17e69fae934f7397048`，分支 `codex/pi-business-flow-20261103`。后端 JAR、管理后台和 PDA 采用 2026-11-03 已构建产物；未修改业务代码、数据库历史迁移或重打 APK。本次新增 `deploy/windows` 中的部署配置、安装及验收脚本。

发布目录：`C:\bike\releases\20261105-423ca81`；`C:\bike\current` 为指向该目录的 junction。数据库存放于 `C:\bike\data\mysql`，图片及附件存放于 `C:\bike\storage\production`，配置与日志分别位于 `C:\bike\config` 和 `C:\bike\logs`。

这是独立新库，未迁移旧服务器的数据库、账号或附件。生产初始化后只有 `admin` 管理员；车辆和 PI 数量均为 0。工位账号及业务基础资料需在后台配置。初始密码保存在服务器 `C:\bike\config\admin-access.local`，并交付到本机受限权限文件 `F:\bike\tmp\aliyun-20261105\admin-access.local`。首次登录必须修改密码。凭据不包含在本文或代码库中。

## 运行环境

- Eclipse Temurin JRE 21.0.13+9。
- MySQL Community Server 8.4.11。
- Nginx 1.28.0、WinSW 2.12.0。
- 官方 Microsoft VC++ x64 运行库，安装前验证微软 Authenticode 签名。

MySQL、后端、Nginx 分别注册为 `BikeMySQL`、`BikeServer`、`BikeNginx` Windows 服务，均配置自动启动、异常重启和 `NT AUTHORITY\LocalService` 运行账户。后端依赖 MySQL，Nginx 依赖后端。MySQL 仅监听 `127.0.0.1:3306`，后端仅监听 `127.0.0.1:18080`，Nginx 监听 `0.0.0.0:80`。应用 JVM 堆上限为 768 MiB，MySQL buffer pool 为 256 MiB。

通过已登录的 Workbench 核对实例及 SSH 主机指纹后，安装 Windows OpenSSH，再使用后台 SSH/SFTP 完成部署。SSH 登录限制为当前运维来源 IP 的 Administrator 用户，并验证了新连接登录成功。Windows 防火墙原先为关闭状态，本次未改变该全局状态；数据库和 API 通过回环绑定保持不对外监听。控制台核实安全组已有 HTTP 80 放行规则，本次未修改云安全组。

## 校验与验收

1. 运行环境包 SHA-256 全部匹配；JRE 另与官方校验文件核对。
2. 原始双域名发布包 `bike-20261105-423ca81-domains.zip` 的 SHA-256 为 `1df0a18a9bab3ec6a1cfbe556f98307fdd6016e482b6edb06b1ccf0479225889`，包内 43 个文件校验通过。现场补齐 WinSW 必填描述字段并完善验收脚本后，最终 46 个文件按 `deployment-manifest-final.json` 再次逐个校验通过，原始包清单保留。
3. Flyway 正式迁移目录成功执行 56 项迁移，到达 V56，失败数为 0。历史 SQL 存在 MySQL `VALUES()` 弃用提示，不影响本次迁移。
4. 两个公网首页 HTTP 200、健康接口 `UP`；两份 HTML 和各自入口 JS/CSS 与本地产物逐字节一致。匿名访问 `/api/pi` 均返回 401。
5. 分别通过两个 Nginx Host 验证管理员登录、`SUPER_ADMIN` 权限上下文、首次改密标记和退出接口；未输出 token 或密码。
6. 按 Nginx → 后端 → MySQL 顺序停止，再通过启动 Nginx 验证依赖服务恢复。重启后两站健康、登录、数据库迁移与空业务数据检查再次通过。配置了自动启动，但未重启整个操作系统。
7. 浏览器实际渲染管理后台和 PDA 登录页面均正常。最终后端日志中 ERROR / Exception 行数为 0；Nginx 保留重启期间两次健康轮询暂时连接不到后端的记录，后端就绪后恢复正常。

本地证据：

- `output/aliyun-20261105-public-verification.json`
- `output/aliyun-20261105-server-verification.log`
- `output/aliyun-20261105-restart-verification.log`
- `output/aliyun-20261105-final-log-check.log`

重启验证的早期日志统计曾将 SQL 提示中的 `Error Code` 匹配为错误，最终区分大小写的日志检查结果为准。

## 运维边界

按用户要求提供 HTTP 80，未配置 HTTPS。本次未迁移历史业务数据、未建立定时备份、未执行真实生产单据或 PDA 真机扫码打印验收。公网带宽为现有 1 Mbps，未变更付费规格。后续升级、备份及回滚操作见 `deploy/windows/README.md`。
