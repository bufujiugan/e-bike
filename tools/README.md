# 工具目录

- `configure.py`：标准库生成随机本机配置，不覆盖已有 .env。
- `start-local.ps1`：读取根目录 .env 并启动 server/web/h5；每个组件单独终端。
- `print-client/`：Windows 标签打印客户端，按生产部署文档配置。
- `production_acceptance.py`、`production_smoke.py`、`production_boundaries.py`：2026-09-16 历史专用隔离验收工具，包含固定验收库名、端口和公开测试口令，不属于生产凭据或通用安装步骤。会写数据、改密码或调整任务状态，不得指向正式数据；也不能直接套用本次新部署库。补充依赖见 requirements-acceptance.txt。

历史验收工具还要求对应测试数据、顺序及 Mapper XML。新安装使用根目录 README 的步骤；生产操作使用页面和受保护 API，不通过验收脚本代替。
