# 独立部署与运维

推荐先按根目录 README 验证本机流程，再部署正式实例。部署前阅读生产需求核查报告，本指南不意味着待办功能已验收。

## 环境

本机：Java21、Maven3.9+、Node22、pnpm9.15.9、Python3.10+、MySQL8.0/8.4。容器：Docker Engine/Desktop 与 Compose v2，至少预留4GB内存用于构建和运行，实际生产规格按负载和图片量评估。腾讯云资源建议及购买链接见建设方案，价格需以购买时页面为准。

无需其他项目服务、私有 Maven BOM、Nacos、Redis 或 MQ。正式数据库与附件持久化存储由部署方提供。

## 配置初始化

运行 `python tools/configure.py` 生成被 Git 忽略的 `.env`。不要覆盖已有实例配置，也不要把 `.env` 上传到聊天、工单或代码库。文件由 `KEY=value` 构成，不支持 PowerShell 表达式。生成密码只使用十六进制字符；手工设置涉及特殊字符时需分别考虑 Shell 与 Compose 转义。

必填：数据库地址/账号/密码、JWT密钥、管理员初始密码。生产使用 `BIKE_FLYWAY_LOCATIONS=classpath:db/migration-prod`。JWT至少32字节。初始管理员固定种子用户名为 `admin`，不应仅改 `BIKE_ADMIN_USERNAME` 就期待自动创建新用户。

`BIKE_ADMIN_PASSWORD` 在管理员尚未完成首次改密时写入；已改密账号不会被重启覆盖。使用生产迁移目录但不设置密码会拒绝启动。提供 BCrypt 哈希时设 `BIKE_ADMIN_PASSWORD_IS_HASH=true`；Compose 如需该模式，须自行增加对应环境项，默认使用生成的明文初始密码。

## 数据库准备

Docker Compose 自动创建 `bike` 库及专用 `bike` 用户。不要将 `BIKE_DB_USERNAME` 配成 root，MySQL 镜像的 MYSQL_USER 用于普通应用账号。

本机方式可用 MySQL Workbench、DBeaver 或数据库控制台创建 `bike`：字符集 utf8mb4，排序规则 utf8mb4_unicode_ci；创建专用用户，授权该库 DDL、读写、索引和引用等迁移所需权限。不要对已有生产库执行测试数据导入。业务建表与数据初始化由 Flyway 完成，勿手工导入开发/生产两套脚本。

两套 SQL 目录 V1–V15 各有根目录镜像；V16 是应用注册的 Java 迁移，执行 Mapper XML 中的结构 SQL。开发迁移包含样例；生产迁移不包含业务样例。切换目录必须使用新库，避免校验和冲突。已有实例升级先备份，新增版本而非编辑历史版本。

## Docker Compose

从根目录执行：

```sh
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 server
curl http://127.0.0.1:18080/actuator/health
```

Web5173、H55174、API18080。默认只绑定127.0.0.1，数据库不映射端口。容器间 API 固定18080，`BIKE_SERVER_PORT` 仅修改宿主机映射。前端请求 `/api` 由 Nginx 代理到 server，无跨域要求；SPA 深链接回退到 index.html。

MySQL健康后后端才启动；前端可能早于后端就绪，暂时502时查日志和健康接口。数据库首次初始化可能需几分钟。镜像构建要求外网能访问 Maven/npm/Docker Registry。

`mysql-data` 保存数据库，`production-files` 保存本地图片。`docker compose down` 仅停服务和移除容器/网络，不带删除卷参数。修改 MYSQL_PASSWORD/ROOT_PASSWORD 环境变量不会更改已初始化卷中的数据库账号密码，轮换需在数据库同步操作。

本机无Docker，已在 [GitHub Actions](https://github.com/bufujiugan/e-bike/actions/runs/35337290704) 完成真实容器构建与启动，MySQL8.4、API健康、两个前端与Nginx登录冒烟全部通过。此结果不代替现场设备和生产容量验收。

## 本机与服务器进程

构建 `mvn -B -f bike-server/pom.xml verify`，在仓库根目录以 PowerShell 运行 `tools/start-local.ps1 server`。脚本读取 `.env`，在 bike-server 工作目录运行可执行 JAR。另开终端运行脚本的 web/h5 参数。

Linux/macOS Bash 只加载自己确认的 `.env`：

```sh
while IFS='=' read -r key value; do
  value=${value%$'\r'}
  case "$key" in BIKE_*) export "$key=$value" ;; esac
done < .env
cd bike-server
java -jar target/bike-server-0.1.0-SNAPSHOT.jar
```

正式运行交给 systemd、Windows 服务管理器或容器；服务用户只获得所需权限，配置工作目录与环境文件。Web/H5 用 `pnpm build` 后的 dist 静态文件交给 Nginx，两个站点分别设置根目录，`/api/` 代理到实际 API 地址并保持完整请求路径。不要把 Vite 开发服务器当公网生产服务。

## HTTPS 与网络

域名分别指向 Web/H5 入口，由外层 Nginx/负载均衡终结 TLS，并转发到5173/5174。前端请求同域 `/api`。只开放必要入口，数据库保持内网，API管理/Swagger仅运维可达。按实际部署设置 `BIKE_BIND_ADDRESS`，不要因为 PDA 访问失败就直接将所有端口暴露公网。

PDA使用服务器的局域网地址或域名，localhost指PDA自身。Windows打印客户端访问远程服务必须HTTPS；反向代理保留Authorization，附件限制至少12MB，请求超时按实际上传和导出需求配置。

## 图片与打印

本地默认 `bike-server/storage/production`，正式设置 `BIKE_PRODUCTION_FILE_ROOT` 为独立持久目录；Compose 固定到持久卷。可选COS设置storage=cos并注入地域、私有桶和专用凭据。切换存储不会自动迁移历史图片，应保留原目录和旧桶读取能力。

Windows打印客户端见 [生产部署说明](生产端验收与部署.md#自动打印客户端)。真实打印机驱动、纸张/硬边距、条码识读及断纸需要现场测试；UNKNOWN先核对再补打，不能自动重复出纸。

## 备份、恢复与升级

- 数据库：使用云数据库自动备份或 MySQL 官方逻辑/物理备份工具，确保包含 `bike_schema_history` 和全部业务表。备份账号和凭据放受保护的客户端配置，避免命令行明文口令。
- 附件：定期备份 `production-files`/本地目录；COS开启版本管理或等价恢复机制。
- 配置：受控保存 `.env`、反向代理配置、镜像/代码版本、备份时间；备份文件不提交仓库。
- 一致性：维护窗口停业务写入与打印客户端，取得数据库及对应附件快照；检查可恢复性后再恢复业务。
- 恢复：在新实例恢复库与附件、同版配置和代码，检查Flyway状态、health、登录、图片读取、库存、追溯及打印任务状态，再切流量。
- 升级：先在数据库恢复副本执行新迁移和冒烟，备份正式实例，记录提交SHA，部署新程序。
- 回滚：若新迁移/业务写入已生效，旧程序可能绕过预占和隔离，必须停写评估；不直接替换旧JAR、不删除新增表、不盲目Flyway repair。

## 验收标准

健康UP、首次登录和改密成功、权限上下文正常、迁移到22、生产空库没有开发车辆。之后维护基础资料，验收四码任务、刷新恢复、跨工位、抢件、隔离、返工、出入库、附件、追溯和打印。当前补全及验收状态以completion-worklog.md为准，云MySQL/COS与实物设备另外签认。


## 上传失败核对运维（V49）

数据库连接预算在主连接池之外另加最多2个upload-intent连接，用于文件写入前持久化意图，避免主业务事务并发占满连接池后死等。V49给已有附件/归档对象键建立引用检查索引，升级大库须预留建索引窗口。FILE_REVIEW_MANAGE应仅显式授予负责全业务文件元数据核对的内部运维人员；在系统管理“上传失败核对”处理REVIEW，原因与版本必填，清理申请和历史先提交，之后才删除未绑定文件。BOUND始终禁止在此清理。重启后自动核对超时PENDING并重试已批准清理，旧版未登记残留须另行盘点。详见[file-upload-reconciliation.md](file-upload-reconciliation.md)。

V49新增接口与表，不更改既有附件上传/下载请求；必须先成功迁移再运行新后端。已执行的V49四份迁移禁止改写。回退旧后端会停止上传意图登记及核对重试，不能视为功能等价回滚：须先暂停上传、归档和清理操作，保存待核对状态、数据库及对象备份，评估后回退应用；保留V49表、历史和对象，不删除表或用Flyway repair绕过校验。恢复V49应用后再核对积压记录及回退期间未登记对象。
