# E-Bike 生产库存与追溯系统

[![Build and test](https://github.com/bufujiugan/e-bike/actions/workflows/build.yml/badge.svg)](https://github.com/bufujiugan/e-bike/actions/workflows/build.yml)

面向电动自行车工厂的独立部署项目：以车架 SN 为主线，关联电机、电池、仪表，支持生产扫码、聚合码、标签、库存、出入库、代理商协同、返工和追溯。包含 Java 后端、Web 管理端、PDA/H5 作业端、Windows 打印客户端，以及需求原件、会议纪要、沟通记录、原型和验收资料。

**当前版本：0.1.0-SNAPSHOT；最新已验证数据库迁移为 V45，项目仍在补全。** SKU库存承诺、锁定转PI、多车型实际出库、物流批次、PI修订、售后公司范围与附件、查询看板、旧件退回、版本方案配件审批、完整发运、仓库最小数据范围及Email事务队列已有实现和专项验证。PI受控模板与固定导出归档、客户商务资料与PI带入、收款方式/撤回及物流附件已专项验证；原SKU数量修订、币种保护和内部客户授权范围已专项验证。V45默认不给普通岗位全部客户范围，需明确分配客户名单或独立全范围角色。新增SKU/客户转移、业务看板/列表导出、普通岗位完整页面和现场交付仍有待办，尚未完成全项目两轮最终需求验收。Email默认关闭外发，正式SMTP需另行配置验收。最新证据和缺口见 [实施与验收台账](docs/completion-worklog.md)，原始缺口见 [生产需求核查报告](docs/生产端需求核查报告_2026-09-16.md)。

## 导航

- [快速启动](#快速启动)
- [功能与边界](#功能与边界)
- [技术栈和目录](#技术栈和目录)
- [配置与数据库](#配置与数据库)
- [首次使用](#首次使用)
- [测试与验证](#测试与验证)
- [部署和运维](docs/deployment.md)
- [全部资料索引](docs/README.md)
- [需求沟通与决策](docs/communication/需求决策与版本关系.md)
- [常见问题](#常见问题)

## 快速启动

克隆项目：

```sh
git clone https://github.com/bufujiugan/e-bike.git
cd e-bike
python tools/configure.py
```

`configure.py` 仅使用 Python 标准库，生成本机 `.env` 和随机密码/密钥，已有文件不会覆盖。管理员用户名为 `admin`，初始密码在 `.env` 的 `BIKE_ADMIN_PASSWORD` 中；第一次登录必须改密。所有示例均从仓库根目录执行，不依赖 `F:/bike` 或原开发机器。

### 方式一：Docker Compose

需要 Docker Engine/Desktop、Compose v2、Python 3.10+；不必在宿主机安装 Java、Node 或 MySQL。

```sh
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 server
```

首次启动会下载镜像、构建三个应用、初始化 MySQL 并自动迁移。后端健康后再登录；Web 先启动时短暂出现 502 可查看 server 日志。

| 入口 | 默认地址 |
| --- | --- |
| Web 管理端 | http://localhost:5173 |
| PDA/H5 | http://localhost:5174 |
| 后端健康检查 | http://localhost:18080/actuator/health |
| OpenAPI JSON | http://localhost:18080/v3/api-docs |
| Swagger UI | http://localhost:18080/swagger-ui/index.html |

默认绑定本机回环地址、使用生产迁移目录，不导入开发业务样例。数据库不暴露宿主机端口；数据库与图片用独立持久卷。停止并保留数据：`docker compose down`。不要删除持久卷来解决启动问题。

已在 [GitHub Actions 全新 Linux 环境](https://github.com/bufujiugan/e-bike/actions/runs/35337290704) 验证容器镜像构建、MySQL8.4初始化、API健康、Web/H5入口及经Nginx的首次登录。本机独立库验证和完整证据见 [验证记录](docs/verification-2026-09-18.md)。

### 方式二：本机开发

需要 JDK 21、Maven 3.9+、Node.js 22 LTS、pnpm 9.15.9、Python 3.10+、MySQL 8.0/8.4。后端依赖公开 Maven 仓库，无私有 BOM，无 Nacos/Redis/MQ 强制依赖。

1. 使用数据库管理工具创建 UTF-8 数据库 `bike` 和专用用户，授权该库的建表/迁移及业务读写。数据库初始化配置见 [部署指南](docs/deployment.md)。
2. 在 `.env` 中设置实际的 `BIKE_DB_URL`、`BIKE_DB_USERNAME`、`BIKE_DB_PASSWORD`。保留随机 JWT 密钥和初始管理员密码。
3. 安装依赖并构建：

```sh
mvn -B -f bike-server/pom.xml verify
npm install -g pnpm@9.15.9
pnpm --dir bike-web install --frozen-lockfile
pnpm --dir bike-web build
pnpm --dir bike-h5 install --frozen-lockfile
pnpm --dir bike-h5 build
```

Windows 分别打开三个 PowerShell 终端，在根目录运行：

```powershell
pwsh -File tools/start-local.ps1 server
pwsh -File tools/start-local.ps1 web
pwsh -File tools/start-local.ps1 h5
```

没有 PowerShell 7 时可以使用 `powershell -ExecutionPolicy Bypass -File ...`。脚本读取 `.env`，后端运行打包 JAR，前端以回环地址启动开发服务器；每个终端按 Ctrl+C 停止对应进程。

Linux/macOS Bash 后端示例（仅加载自己生成并确认内容的 `.env`）：

```sh
while IFS='=' read -r key value; do
  value=${value%$'\r'}
  case "$key" in BIKE_*) export "$key=$value" ;; esac
done < .env
cd bike-server
java -jar target/bike-server-0.1.0-SNAPSHOT.jar
```

另开终端分别运行 `pnpm --dir bike-web dev --host 127.0.0.1` 和 `pnpm --dir bike-h5 dev --host 127.0.0.1`。Vite 的 `/api` 代理默认指向 `18080`；更改后端端口时同步修改两个 `vite.config.ts`。`.env` **不会由 Spring Boot 自动加载**，必须用启动脚本或导入环境变量。

## 功能与边界

| 模块 | 当前代码能力 | 说明 |
| --- | --- | --- |
| 基础管理 | 车型、客户、供应商、代理商、仓库库位、用户角色、工位、SN 规则 | 首次部署需维护真实基础资料 |
| 生产 | SKU、批次、四码任意顺序、任务恢复/交接、部件预占、聚合码 | 旧组装入口已适配新任务，历史数据仍待验收 |
| 标签与打印 | QR/EAN/UPC、两种可配置标签、持久队列、补打、原因与记录 | Windows 客户端；SUBMITTED 仅代表队列提交 |
| 质量与返工 | 异常照片、隔离/解除、换件、旧件复检、历史绑定 | 旧件默认隔离，复检合格后可复用 |
| 仓储 | 入库、出库、更正、实物/锁定/可用库存 | 出库必须客户和订单；销售锁库占用闭环仍待补全 |
| 业务协同 | 单车型订单、按车型数量锁库、代理商隔离、采购意向 | 不能等同于 PRD 中多 SKU/PI 销售流程 |
| 查询与交换 | 追溯 API、看板、Excel 导入导出 | 已补完整追溯、错误行及筛选导出，完整岗位和规模验收待完成 |
| PI | 真实 SKU 明细与资料快照、草稿编辑、确认、收款、发货放行、PDF/Excel | 已接入实际出库校验；多车型履约、版本修订、收款凭证和正式模板仍待补齐 |
| 售后 | 案例申报、范围隔离、技术方案、审批、补件发货、留言与归档 | 基础 API 和页面已实现；完整附件、公司成员和销售范围、旧件与收费仍待补齐 |

核心规则：车架 SN 始终是业务主标识；部件不能同时绑定两台车；二维码不替代车架主线；返工保留历史。初期锁库按车型与数量，不锁具体车辆 SN 或零部件。出库先选择客户与订单，再扫码校验、批量确认。

### 操作流程

```mermaid
flowchart LR
  A[维护车型和 SKU] --> B[登记部件及质检]
  B --> C[建立生产任务]
  C --> D[任意顺序扫描四码]
  D --> E[绑定整车并生成聚合码]
  E --> F[标签预览 / 打印队列]
  F --> G[入库]
  G --> H[选择客户及订单后出库]
  E --> I[异常隔离 / 返工 / 历史追溯]
```

## 技术栈和目录

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.4.5、Spring Security、JWT、MyBatis-Plus 3.5.12（SQL 放 XML）、Flyway |
| 数据 | MySQL 8.x；附件本地目录或可选腾讯云 COS |
| Web | Vue 3、TypeScript、Vite 6、Element Plus、ECharts |
| PDA/H5 | Vue 3、TypeScript、Vite 6、Vant |
| 其他 | Apache POI、ZXing、OpenAPI、Playwright 打印渲染 |

```text
.
├── bike-server/              后端与测试，Mapper XML，Flyway 迁移
├── bike-web/                 Web 管理端
├── bike-h5/                  PDA / 手机作业端
├── db/                      开发/生产 SQL 迁移镜像（V16 为 Java 迁移）
├── deploy/                  Dockerfile 与 Nginx 配置
├── compose.yaml             MySQL + API + Web + H5
├── tools/                   本地配置/启动、验收工具、Windows 打印客户端
├── docs/                    需求、会议、沟通、设计、部署、验证、归档清单
├── output/pdf/              客户沟通和功能清单 PDF
├── output/prototype/        可独立打开的原型及其源文件
├── output/playwright/       历史截图与原型界面图
├── E-Bike_高保真交互原型.html  单文件静态原型
└── bike_codex_project_spec.md 原始开发任务书
```

## 配置与数据库

| 环境变量 | 用途 / 默认值 |
| --- | --- |
| `BIKE_DB_URL` / `BIKE_DB_USERNAME` / `BIKE_DB_PASSWORD` | JDBC 地址与数据库账号；Compose 使用内部 MySQL |
| `BIKE_JWT_SECRET` | 必填，至少32字节；生成器创建随机64位十六进制字符串 |
| `BIKE_ADMIN_PASSWORD` | 初次登录的管理员密码；生产迁移启动必填 |
| `BIKE_ADMIN_PASSWORD_IS_HASH` | 提供 BCrypt 哈希时设 true，默认 false |
| `BIKE_FLYWAY_LOCATIONS` | 配置模板默认 `classpath:db/migration-prod` |
| `BIKE_SERVER_PORT` | 本机后端 / Compose 对外端口，默认18080 |
| `BIKE_JWT_TTL` | Token 有效期，默认 `PT8H` |
| `BIKE_PRODUCTION_STORAGE` | `local` 或 `cos`，默认 local |
| `BIKE_PRODUCTION_FILE_ROOT` | 本机默认 `./storage/production`，相对后端工作目录 |
| `BIKE_UPLOAD_MAX_FILE_SIZE` / `BIKE_UPLOAD_MAX_REQUEST_SIZE` | HTTP 上传上限，默认 50MB / 160MB；各业务仍单独限制文件大小和内容 |
| `BIKE_COS_REGION` / `BIKE_COS_BUCKET` / `BIKE_COS_SECRET_ID` / `BIKE_COS_SECRET_KEY` | 使用 COS 时全部配置 |
| `BIKE_MYSQL_ROOT_PASSWORD` | 仅 Compose 初始化数据库使用 |
| `BIKE_BIND_ADDRESS` / `BIKE_WEB_PORT` / `BIKE_H5_PORT` | Compose 对外地址和端口，默认127.0.0.1 / 5173 / 5174 |

后端原始 `application.yml` 默认开发迁移；本仓库启动配置显式选用生产迁移以避免混入样例。若需演示数据，在**首次启动一个全新测试库前**改为 `classpath:db/migration`。已迁移库不要切换开发/生产目录，二者 V4 等版本校验和不同。

V1–V15 SQL 在 `db` 和后端 resources 保持镜像；V16 为 Java Flyway 迁移，结构 SQL 位于 `mapper/ProductionSchemaMapper.xml`。不手工导入所有目录、不重复执行旧 SQL、不修改已运行迁移。数据库迁移记录表为 `bike_schema_history`。

## 首次使用

1. 健康检查返回 `UP`，使用 `admin` 和本机生成的初始密码登录，按页面完成改密。
2. 维护车型、仓库/库位、工位、用户角色；生产空库不含真实车型或订单。
3. 在 Web 生产中心建立 SKU，配置规格、EAN/UPC、标签模板，登记已质检部件。
4. PDA/H5 建立任务并扫描电机、电池、仪表、车架，顺序不限；未知新车架明确选择车架类型。
5. 完成绑定后在生产中心核对车辆、标签和打印任务，再验收入库、订单出库和追溯。
6. 实物打印需要 Windows 驱动和岗位账号；见 [打印客户端说明](docs/生产端验收与部署.md#自动打印客户端)。

`tools/print-client` 的 `npm ci` 安装已锁定依赖；`npx playwright install chromium` 安装渲染浏览器。登录 Token 放环境变量，客户端轮询打印队列；远程连接要求 HTTPS。最终出纸尺寸、条码识读和故障恢复必须现场验收。

## 测试与验证

```sh
mvn -B -f bike-server/pom.xml verify
pnpm --dir bike-web build
pnpm --dir bike-h5 build
```

本轮（2026-09-18）后端110项测试通过、Web/H5类型检查与生产构建通过。更完整的独立库、登录、迁移和配置检查结果见 [验证记录](docs/verification-2026-09-18.md)。GitHub Actions 使用 Java21/Node22 和锁文件独立构建。

[历史验收工具](tools) 中 `production_*.py` 为2026-09-16专用环境工具，会创建测试数据或修改验收账号，**不作为通用部署命令，不得用于正式数据**。相关数据库端口、账号和报告路径均属历史环境。后续生产页面缺口、云服务与硬件验收单独跟踪。

## 部署和运维

详细步骤见 [部署指南](docs/deployment.md)。生产使用 HTTPS、独立服务账号和私有附件存储；备份数据库、附件及部署配置。默认 Compose 只绑定本机；局域网/公网访问应明确调整监听并配置防火墙和 TLS。

升级前备份并记录代码版本，新增迁移按版本执行。回滚不得绕过新生产预占/隔离规则；不能只换回旧 JAR。数据库和打印设备之间不具备“恰好出纸一次”保证，未知结果先人工核对。

## 常见问题

- **JWT 密钥为空或 WeakKeyException**：确认启动进程加载 `.env`，密钥至少32字节；勿仅双击 JAR。
- **数据库连接失败**：确认数据库已启动、库名与账号授权正确；Compose 内用 mysql:3306，本机用实际地址。
- **Flyway checksum mismatch**：检查是否切换迁移目录或修改旧文件；从正确备份/版本恢复，不直接 repair。
- **登录不接受 `.env` 初始密码**：管理员已改密后不会被重启覆盖；使用改后的密码。修改 `.env` 不会重置现有账号。
- **PDA 打不开页面**：localhost 指当前设备本身；现场使用服务器可达地址与 HTTPS。
- **打印状态为 SUBMITTED 却未出纸**：该状态只表示已提交系统队列，检查驱动、纸张、打印机和现场记录。
- **Web 构建有大包警告**：当前 ECharts/组件库主包超过500KB，构建仍成功；后续按路由拆分优化。
- **售后入口与静态原型不同**：真实业务入口为 `/after-sales`，需相应权限；`output/prototype` 中页面仍只用于范围评审，不连接正式系统。

## 资料、协作与授权

所有原始需求、会议纪要、客户沟通 PDF、需求变更、方案和原型入口见 [资料总索引](docs/README.md)。[归档清单](docs/archive-manifest.json) 提供文件分类和 SHA-256。

开发约束见 [CONTRIBUTING.md](CONTRIBUTING.md)。当前未声明开源许可证，代码及客户资料的使用授权由权利人确认；公开可读不等于授予任意使用许可。数据库、运行凭据、日志和缓存不入库，业务数据由部署方自行提供。

