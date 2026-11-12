# 生产单操作样式与总码便捷操作发布

2026-11-12 14:46:04（北京时间）完成部署，随后完成服务与线上浏览器验收。

## 发布信息

- GitHub 分支：codex/pi-business-flow-20261103。
- 功能提交：4fa9902e606a184f077fec08873802b4478b2e37；发布前已推送并核对远程 SHA。
- 当前版本：C:/bike/releases/20261112-4fa9902-workbench-actions。
- 上一版本：C:/bike/releases/20261112-9c5547c-production-stock-status。
- 发布包 SHA-256：246e593296ea8b2687b87253d625bc1423e214d47970ed75ce99d34a40c9f3f9。
- 后端 JAR SHA-256：da183bcf74c9d4f0e35d97810e79f2e7c75a3f320c7b4b7f4ec14a6a5658cc51。

本次仅更新完整 web，沿用上一版后端、PDA 和部署配置；不涉及数据库迁移。源码与操作说明见 production-workbench-actions-20261112.md。

## 发布检查

- 备份：C:/bike/backups/20261112-144537-workbench-actions，包含数据库、storage、config、services，四份备份散列核验通过。
- 发布前后业务快照一致；迁移 V57 成功，失败数为 0。
- 50 个 web/pda/deploy 文件散列、后端 JAR 散列通过。
- BikeServer、BikeNginx、BikeMySQL 均 Running；两站首页 200，健康 UP；抽查受保护接口匿名返回 401；启动日志无 ERROR/Exception。
- 线上三个环节主按钮均为 190×36 px、8 px 圆角；已入库车不显示入库表单。
- 实际待入库车 YCB040160TAUM00001 自动带入本车总码，HTTP 页面的复制按钮成功复制相同总码。
- 浏览器验收业务写请求为 0，未实际办理入库或出库。桌面、手机布局及切车、刷新、错误码拦截在发布前验证通过。

## 回退

旧入口保留在 C:/bike/current-before-20261112-144537。停止入口和后端，核实当前 junction 后重命名保留，恢复旧入口并启动服务、检查健康。回退无需改库，不得用备份覆盖上线后的业务数据。

本地验证证据：tmp/workbench-actions-20261112/、tmp/deploy-workbench-actions-20261112/，截图保留在 output/playwright；服务器证据位于版本目录中的 deployment-result.json、release-manifest.json、verification-20261112.json。资料、SDK 和临时文件未提交 GitHub。
