# A 服务器部署记录（2026-10-27）

部署代码：`9f15625`，本地工作区 `F:\bike`。部署前工作区干净，未修改业务代码。服务器为 `95.169.12.38`，SSH 用户 `root`、端口 `22`。

## 访问与 DNS

- Web：`http://e-bike.gpt20260801.top`
- PDA：`http://pda.gpt20260801.top`
- 两个入口使用现有 HAProxy 的 80 端口。此次未为新子域名配置 HTTPS。
- 2026-10-27 11:29（北京时间）已在 **Cloudflare** 中添加 `e-bike`、`pda` 两条 A 记录，均指向 `95.169.12.38`，代理状态“仅 DNS”，TTL 自动。
- 权威 DNS 为 `april.ns.cloudflare.com`、`gabriel.ns.cloudflare.com`。原有主域名和 `www` 的 A 记录及“已代理”状态均保留，未改动 NS。
- 公共递归 DNS `1.1.1.1` 已查询到两个正确地址；不使用 Host 覆盖或 DNS 映射时，两个域名的 `/actuator/health` 均返回 `UP`，内置浏览器已直接通过两个公网域名打开登录页。
- DNS 保存结果截图：`output/playwright/server-a-cloudflare-dns-20261027.png`。

## 隔离与目录

| 组件 | 容器 | 内部访问 | 内存上限 |
| --- | --- | --- | --- |
| 数据库 | `e-bike-mysql` | 仅 `e-bike` Docker 网络，未发布宿主机端口 | 768 MB |
| 后端 | `e-bike-server` | `127.0.0.1:19080` → `18080` | 1 GB |
| Web | `e-bike-web` | `127.0.0.1:19173` → `80` | 96 MB |
| PDA | `e-bike-h5` | `127.0.0.1:19174` → `80` | 96 MB |

- 发布目录：`/opt/e-bike/releases/20261027-9f15625`
- 当前发布指针：`/opt/e-bike/current`
- 持久数据库：`/opt/e-bike/shared/mysql`
- 持久附件：`/opt/e-bike/shared/production`
- 生产环境配置：`/opt/e-bike/shared/server.env`
- 数据库配置：`/opt/e-bike/shared/mysql.env`
- 初始管理员凭据：`/opt/e-bike/shared/initial-login.txt`，权限 `0600`。
- 本机私有凭据文件：`F:\bike\tmp\server-a-initial-login.txt`，已移除继承权限，仅当前用户与 SYSTEM 可读写；不纳入 Git。
- 使用独立随机数据库密码、管理员密码和 JWT 密钥，生产迁移目录 `classpath:db/migration-prod`。没有导入本地业务数据。
- 运行容器均配置 `unless-stopped` 重启策略及日志轮转（10 MB × 3）。Java 使用非 root 用户。
- 构建容器 `e-bike-build-20261027` 已成功退出，构建时限制 2 CPU / 2 GB。
- 因服务器没有 Compose 插件，本次使用 Docker 命令部署，没有升级或重启现有 Docker 服务。

## 入口配置

修改 `/etc/haproxy/haproxy.cfg` 的 `production_http`，只扩充两个子域名白名单、HTTP 路由与对应 backend。已有 HTTPS 站点配置保留。

原有域名继续保持 HTTP 跳转 HTTPS；两个 BIKE 域名直接通过 HTTP 提供服务。配置经过 `haproxy -c` 校验后使用 `systemctl reload haproxy` 平滑加载。

BIKE 的 Nginx 配置位于发布目录 `deploy/nginx.conf`，补充只信任独立 Docker 网关的真实 IP 设置，保留客户端 IP 供审计及登录限流使用。

配置备份：`/opt/e-bike/backups/haproxy.before-routing-20261027.cfg`。最终变更差异：发布目录 `haproxy.diff`。

## 验证结果

- Web、PDA 的 TypeScript 检查与 Vite 生产构建通过。Web 存在大包提示，不影响构建。
- Java 21 / Maven `verify` 成功：475 项测试，282 项通过，193 项因专项测试环境条件未满足而跳过，0 失败、0 错误。
- MySQL 8.4 全新独立库成功完成 V1–V55 共 55 个迁移。
- `/actuator/health` 返回 `UP`；两个入口 HTML 返回 200。
- 通过两个域名的 Host 路由，管理员登录、当前用户查询、首次改密标记及退出验证通过。验收 token 已退出失效。
- 首轮浏览器验证 Web、PDA 登录页，控制台无错误，通过浏览器单独设置域名到 IP 的映射，未修改系统 hosts；DNS 配置完成后已补做无映射的公网浏览器访问及 API 健康检查。
- 截图：`output/playwright/server-a-web-login-20261027.png`、`output/playwright/server-a-pda-login-20261027.png`。
- 原有 15 个运行容器的 ID 均保持不变。
- 原有 6 个域名的 HTTPS 首页状态码与字节数保持一致：`gpt20260701.top` 为 301/169；`www.gpt20260701.top`、`direct.gpt20260701.top` 为 200/30227；`token8888.top` 为 200/17434；`gpt20260801.top`、`www.gpt20260801.top` 为 200/3350。
- 以上为部署与入口验收，不代表现场扫码、实际打印、完整业务流程或业务数据迁移验收。

## 运维与回滚

检查服务：

```sh
docker ps --filter name=e-bike
docker logs --tail 100 e-bike-server
curl -fsS http://127.0.0.1:19080/actuator/health
```

构建日志、镜像 ID、JAR 校验和与配置差异保存在发布目录的 `build.log`、`manifest.json`、`haproxy.diff`。部署及入口变更脚本保存在同目录的 `deploy.py`、`route.py`；这些是首次部署脚本，包含存在性保护，不应重复运行来升级现有实例。

初始备份：

- `/opt/e-bike/backups/bike-initial-20261027.sql`
- `/opt/e-bike/backups/production-initial-20261027.tar.gz`

这些备份只覆盖初始部署状态，正式业务产生后应另做数据库和附件一致性备份。

若需要撤回 BIKE 对外入口，先对比当前 HAProxy 配置与本次保存的差异，仅撤回 BIKE 新增内容，执行 `haproxy -c -f /etc/haproxy/haproxy.cfg` 成功后平滑 reload。若确认此后没有其他人修改 HAProxy，才可使用完整备份恢复；不要盲目覆盖后来新增的其他站点。

如需停 BIKE，仅对 `e-bike-web`、`e-bike-h5`、`e-bike-server`、`e-bike-mysql` 分别执行 `docker stop`，保留持久目录及备份。不要停止现有项目或删除数据库目录。升级后的数据库回滚需要匹配应用、数据库和附件的版本，不应仅替换旧 JAR。

首次使用账号为 `admin`，密码见私有文件，首次登录必须改密；之后在 Web 中配置业务资料和 PDA 工位账号。
