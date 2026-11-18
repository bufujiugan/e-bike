# PI 维护快捷入口与收款凭证检查发布记录

2026-11-18 20:13:04（Asia/Shanghai）完成生产切换，随后完成服务器、公网及登录后只读验收。

## 版本与范围

- 仓库：`bufujiugan/e-bike`，分支：`codex/pi-business-flow-20261103`。
- 应用提交：`0721212569f38e3920b7bf918b64900e40ec2114`，已推送 GitHub。
- GitHub Release：`deploy-20261118-0721212-pi-shortcuts`，资源：`bundle-0721212.zip`。
- 提交了当前全部未提交源码：物流和业务附件维护快捷入口、跳转后返回原表单、收款凭证检查与快捷上传事件。
- 物流弹窗提示旁直接显示“去维护物流信息”；附件字段下直接显示“没有需要的附件？去维护业务单据附件”，空下拉中也提供入口。点击定位原有维护页签。
- 管理端静态资源更新；后端 JAR、数据库结构和业务接口无变更。
- 本地业务表格、SDK 压缩包、历史截图与运行产物未纳入源码提交。

## 发布与回退位置

- 新版本：`C:\bike\releases\20261118-0721212-pi-shortcuts`。
- 旧版本：`C:\bike\releases\20261118-71107bd-pi-progress`。
- 当前入口：`C:\bike\current`。
- 旧入口：`C:\bike\current-before-20261118-201231`。
- 备份：`C:\bike\backups\20261118-201231-pi-shortcuts`。
- Bundle SHA-256：`0778daf592f9047f83b508d8e26afdd044fdf8322c37cac0f89f2101909c1e12`。
- Web SHA-256：`68d3fe53a851e3dd5ca041333751dda1bdbb613249f0d65f82bcf2e5a63d0d45`。
- 后端 JAR SHA-256：`c03f184f1da7278d301daaba85e2d61455bf44dbbd42f2ac70abdd2541cbc84b`。
- 云助手预检任务：`t-bj073he97j3lse8`；部署任务：`t-bj073hef594cf7k`，成功执行、退出码 0。
- 发布前确认线上提交为 `71107bd7d39e350f33f80c40d4d377e27034be00`，服务正常，新版本目录不存在；部署脚本核验现网清单、备份后切换，异常自动恢复旧入口。

## 验证结果

- `npm --prefix bike-web run build` 通过（TypeScript 与生产构建）；`git diff --check` 通过。
- 本地浏览器验证物流提示旁、附件选择框下及空下拉入口跳转原页签，返回保留填写内容。
- 服务器发布状态 `DEPLOYED`；三个服务 `BikeMySQL`、`BikeServer`、`BikeNginx` 均为 Running，最新启动日志错误数为 0。
- 54 个发布文件和 4 份备份散列检查通过；部署前后业务快照一致；V58 成功、失败迁移 0、欧洲业务表 3 张，均未改变。
- 公网 `ebike.nfwms.com` 与 `pda.nfwms.com` 首页 HTTP 200、健康状态 UP；分别 44、3 个静态文件与发布清单 SHA-256 一致。
- 两站点 PI、仓库选项、履约配置、欧洲工厂订单接口匿名访问均为 401。
- 用户通过内置浏览器登录后，只读打开实际 PI，物流提示旁入口可见，点击成功定位“物流信息”页签，控制台无错误。
- 已确认收款的实际 PI 放行弹窗显示“缺少凭证”和快捷上传入口，缺少凭证时确认放行按钮不可用。
- 用户截图所示附件节点已继续办理，本次未在生产修改流程或创建验收单据；附件入口交互依据本地模拟验证，线上静态资源散列已确认。
- 本次未上传生产附件、未提交放行或物流业务操作。

本地发布材料与结果：`tmp/deploy-pi-shortcuts-20261118/`。服务器新版本目录保留 `deployment-result.json`、`release-manifest.json`、`verification-20261118.json`。

如需回退，先核对上线后的业务状态，再将当前入口切回旧 release 并重启服务；保留所有 release 和备份目录。
