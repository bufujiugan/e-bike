# PI 订单关联和付款优先占库发布记录

## 发布结果

2026-11-05 16:19:20（Asia/Shanghai）完成 Windows 生产实例升级，线上应用提交为 `6913d35470aaa2690fc3f2bcc07ed4d1b502ceb1`。本记录随后独立提交，不改变线上应用构建版本。

- 仓库：https://github.com/bufujiugan/e-bike
- 分支：`codex/pi-business-flow-20261103`，本次未合并到 `main`。
- `6793c60`：SKU 预锁及付款优先占库，库存不足的未付款需求按整单处理并记录原因。
- `0928455`：PI 关联来源订单、自动带入信息、确认及履约一致性校验。
- `6913d35`：Windows 服务和域名部署配置。
- 发布目录：`C:\bike\releases\20261105-6913d35-pi-binding`。
- 生效入口：`C:\bike\current` junction。
- 原发布目录：`C:\bike\releases\20261105-423ca81`，仍保留。
- 原入口保留为：`C:\bike\current-before-20261105-161852`。
- 无新增数据库迁移；启动时验证已有 56 条迁移，数据库已是目标版本。

## 连接与访问

| 项目 | 信息 |
| --- | --- |
| 服务器 | 阿里云北京，Windows Server 2022 Datacenter |
| 实例 | `i-2ze5xv8sh0zix3w4szuc` |
| 公网 IP | `59.110.166.139` |
| SSH | `ssh Administrator@59.110.166.139`，端口 `22`，本次连接成功 |
| RDP | `59.110.166.139:3389`，账号 `Administrator`；已检查服务器监听，未单独测试公网远程桌面 |
| 管理后台 | http://ebike.nfwms.com |
| PDA | http://pda.nfwms.com |
| Web | Nginx 共用 `80` 端口，当前使用 HTTP |
| 后端 | `127.0.0.1:18080`，仅本机访问 |
| MySQL | `127.0.0.1:3306`，数据库 `bike`，仅本机访问 |

SSH 主机指纹：`SHA256:16kHI7Uge6x8YE53moPwqXFy6rX0HW8+rArENXDRHJw`。密码、token、数据库密钥不写入仓库或发布记录。

## 备份和目录

切换前停止 Nginx 和后端写入，数据库保持运行，备份到 `C:\bike\backups\20261105-161852-pi-binding`：

- `bike.sql`：事务一致的数据库备份，包含 routines、events 和 triggers。
- `storage.zip`：业务附件。
- `config.zip`、`services.zip`：配置及服务包装目录，包含敏感配置，仅留在服务器，目录权限限制为 SYSTEM 和 Administrators。
- `backup-hashes.json`：备份文件 SHA-256。
- `business-before.txt`、`business-after.txt`：切换前后关键业务快照，内容一致。

数据库目录 `C:\bike\data\mysql`；附件目录 `C:\bike\storage\production`；配置 `C:\bike\config`；服务 `C:\bike\services`；日志 `C:\bike\logs`。三个 Windows 服务为 `BikeMySQL`、`BikeServer`、`BikeNginx`，验收时均为 Running。

## 实际验证

发布前的自动化验证详见 `pi-order-binding-20261105.md`：325 项常规测试及独立 MySQL 的 27 个相关场景通过，前端类型检查和构建通过。依赖环境的跳过项目没有计入通过数。

服务器增量包包含 23 个变化的 JAR 条目，依赖和迁移均未变化。重建 JAR 后对全部 839 个文件条目的 SHA-256 与本地构建逐一核对；42 项前端及部署文件校验通过。重建时修正了压缩工具对空条目的处理，完整校验通过后才切换。

- 线上 JAR SHA-256：`e4b53c6bbe072be766c386e0741b0dccc9e079dd45245c61a6de08863bcc8a94`。JAR 容器元数据与本地包可能不同，各条目内容全部一致。
- 两域名首页 HTTP 200，健康检查均为 `UP`；公网后台全部 35 个文件与构建产物 SHA-256 一致。
- PDA 沿用原发布的 3 个文件，服务器逐文件比对一致，并核验公网内容。
- 管理员 API 和真实浏览器登录成功；浏览器新建 PI 默认勾选“关联已有订单”，提示先选客户和原订单；打开后取消，未保存测试单。
- 浏览器控制台错误和警告均为 0；新后端启动段至 16:24 检查时没有 ERROR 或 Exception。
- PI 1 仍关联订单 1，代理商 2（代理商-2026110502），SKU 1、数量 5，已收款 25,000 元，状态 `CONFIRMED`、库存占用 `PAID_HELD`。
- PDA 使用的 `/api/orders/stock-out-options` 能按原订单编号查询到订单 1、数量 5、已出库 0。
- PI 流程当前为 4/11，下一步“确认出库所需购销合同”；`releaseStatus=BLOCKED` 是尚未完成真实业务条件，本次没有绕过合同、凭证或放行校验。

本次部署未再次修复业务数据，未创建测试订单、收款或出库记录。登录会产生正常登录审计。未执行真实 PDA 扫码、打印或实物出库验收。

线上截图：`output/playwright/pi-order-binding-production-20261105.png`。详细校验结果位于本地 `tmp/pi-binding-deploy-20261105/`；服务器发布结果为新发布目录中的 `deployment-result.json`。

## 回退步骤

本次切换脚本已配置失败时自动恢复原 junction，但上线后未触发回退。后续人工回退前，应先核对新规则下新增的预锁及自动取消记录；回退应用不会恢复已取消订单，不能直接还原旧数据库覆盖新增业务。

确认兼容后，在管理员 PowerShell 中停 `BikeNginx`、停 `BikeServer`，核对 `C:\bike\current` 确实是指向本次发布的 junction，且原保留入口存在。将当前 junction 移到一个新的保留名称，再将 `C:\bike\current-before-20261105-161852` 移回 `C:\bike\current`；启动 `BikeServer`，等待本机 `/actuator/health` 为 `UP`，再启动 `BikeNginx` 并核对两站。保持数据库和附件原位，不删除发布目录。

检查命令：

```powershell
Get-Service BikeMySQL,BikeServer,BikeNginx
Get-Item C:\bike\current | Select-Object FullName,Target
Invoke-RestMethod http://127.0.0.1:18080/actuator/health
```
