# PI 履约进度条恢复发布记录

2026-11-18 10:46:54（Asia/Shanghai）完成生产切换和服务器验收，10:48 完成公网资源、健康状态与匿名接口保护检查。

## 版本与范围

- GitHub：`bufujiugan/e-bike`，分支 `codex/pi-business-flow-20261103`。
- 应用提交：`71107bd7d39e350f33f80c40d4d377e27034be00`，已推送 GitHub。
- GitHub Release：`deploy-20261118-71107bd-pi-progress`，发布资源为 `bundle-71107bd.zip`。
- 欧洲 PI 简化流程恢复“履约进度”展示，显示已完成数、总数和百分比进度条。
- 本次仅替换管理端静态资源，不修改 Controller API、DTO、数据库字段、后端 JAR、数据库迁移和业务数据。

## 构建与发布前验证

- `bike-web` 生产构建通过。
- 桌面端和 390px 移动端页面验证通过，控制台错误数为 0。
- 发布前生产入口为 `C:\bike\releases\20261117-f6c1a54-optional-trade-terms`，提交为 `f6c1a5406219c2760a9d6cd963e6bd94f7c1df37`。
- 发布前 JAR SHA-256 为 `c03f184f1da7278d301daaba85e2d61455bf44dbbd42f2ac70abdd2541cbc84b`；`BikeMySQL`、`BikeServer`、`BikeNginx` 均为 Running，新 release 尚不存在。

## 发布位置与校验值

- 新版本：`C:\bike\releases\20261118-71107bd-pi-progress`。
- 旧版本：`C:\bike\releases\20261117-f6c1a54-optional-trade-terms`。
- 当前入口：`C:\bike\current`；旧入口：`C:\bike\current-before-20261118-104624`。
- 备份：`C:\bike\backups\20261118-104624-pi-progress`。
- Bundle SHA-256：`93cc023fb4e8710c3ddb2c238b07bebd3bcecbc8a6cce908273033c172495e73`。
- Web Delta SHA-256：`93038591e99c4876d52e26cc4175d0bcac030eeb838a95134be748346961c82f`。
- 部署脚本 SHA-256：`3e0a824bd2c619adb2ebc1d36254aa1c521fdc8e69e161b2210d332f05e60bbb`。
- 验收脚本 SHA-256：`4cd396d792c90446ed97423c6ed90936b295c75d1ac5d315bdc544f5cf92709c`。
- 启动脚本 SHA-256：`a18d233632fcaf6aeecf1d4a2f8125da784688c07d817dcfc8a2e69d7d9c11aa`。
- 云助手预检任务：`t-bj073fzut6a3uo0`；正式部署任务：`t-bj073fzzoftmoe8`，退出码 0。

## 线上验收

- 发布状态为 `DEPLOYED`，线上提交为 `71107bd7d39e350f33f80c40d4d377e27034be00`。
- `BikeMySQL`、`BikeServer`、`BikeNginx` 均为 Running；最新启动日志 ERROR／Exception 为 0。
- `ebike.nfwms.com`、`pda.nfwms.com` 首页 HTTP 200，健康状态均为 UP。
- 公网管理端 44 个静态文件、PDA 端 3 个静态文件与发布清单 SHA-256 一致。
- `/api/europe/factory-orders`、`/api/pis`、`/api/pis/warehouse-options`、`/api/pi-fulfillment-profiles` 匿名访问均返回 401。
- 部署前后业务快照一致；V58 状态、失败迁移数和欧洲业务表数量未变化；54 个发布文件、4 份备份散列校验通过。
- 后端 JAR 保持不变，SHA-256 仍为 `c03f184f1da7278d301daaba85e2d61455bf44dbbd42f2ac70abdd2541cbc84b`。
- 登录生产业务系统后打开 `PI0B2C8F8A951C49B9A4A72338545CC6A2 · V1`，页面显示“履约进度 7/7”、完整的 100% 进度条，六个简化办理节点均为完成状态；浏览器控制台错误数为 0。

## 回退与证据

本次没有新增迁移。若新版本出现问题，先停止相关写入并核对上线后的业务数据，再将 `C:\bike\current` 切回旧 release 并重启服务；部署脚本异常时会自动恢复旧入口。回退不得删除 release 或备份目录。

服务器版本目录保存 `deployment-result.json`、`release-manifest.json`、`verification-20261118.json`。本地发布材料及公网验收结果位于 `tmp/deploy-pi-progress-20261118/`，其中 `public-verification.json` 记录公网校验结果。
