# 独立项目归档验证记录

日期：2026-09-18。范围：代码构建、独立初始化、资料归档与发布准备。未修改业务 API、事务、DTO 或历史数据库迁移。

| 验证 | 本次结果 |
| --- | --- |
| `mvn -B verify` | 110项测试通过，0失败、0错误、0跳过；可执行JAR打包成功 |
| Web `pnpm build` | TypeScript检查及Vite构建通过；保留主包大于500KB提示 |
| H5 `pnpm build` | TypeScript检查及Vite构建通过 |
| 新配置生成 | 随机生成本机密码及JWT密钥，`.env`被忽略；不会覆盖已有配置 |
| 独立运行 | 通过 `tools/start-local.ps1 server` 运行当前打包JAR，使用新建数据库和专用应用账号 |
| 数据库 | 本机MariaDB11.4.5兼容库，独立13318端口；生产目录从空库迁移V1–V16成功 |
| 注释 | 该库表注释/列注释缺失数量均为0 |
| 初始业务数据 | 生产SKU空列表，整车数量0；未导入开发业务样例 |
| 认证 | admin初次登录要求改密；改密后重新登录成功；`/api/auth/context`成功；匿名访问401 |
| 健康与OpenAPI | `/actuator/health`为UP，OpenAPI包含110个路径 |
| SQL镜像 | 根目录与resources的开发/生产V1–V15文件逐字节一致 |
| 配置结构 | Compose与Actions YAML解析通过；Bash读取配置兼容JDBC特殊字符和Windows换行 |
| GitHub Actions | [运行35337290704](https://github.com/bufujiugan/e-bike/actions/runs/35337290704) 四个任务全部成功：后端、Web、H5、Compose冒烟 |
| 实际容器验证 | MySQL8.4、三个镜像构建、API健康、Web/H5入口和经Nginx的首次登录通过 |
| 原始资料 | 需求DOC/图片/PDF、售后原文、09-15纪要、沟通PDF、原型及截图纳入资料索引 |
| 凭据边界 | 实际数据库地址/口令不进入需求沟通归档；`.env`、日志、数据库文件、浏览器会话及缓存不提交 |

运行结果见 [独立启动JSON](evidence/2026-09-18-independent-startup.json)。验证使用新建的本地隔离实例，没有连接或修改原业务数据库。

## 未验证范围

本机没有Docker，容器与MySQL8.4验证在GitHub Actions的全新Linux环境完成；不等于正式腾讯云数据库环境验证。真实PDA、扫码枪、标签打印机、COS、现场网络及完整生产页面验收没有在本次重做。

生产侧已知缺口仍以 [09-16核查报告](生产端需求核查报告_2026-09-16.md) 为准。PI与完整售后需求未因归档而实现。历史验收图片、报告与原型只代表其对应时间和场景。

## 发布与兼容修正

已推送到公开仓库 main，代码提交 `ccf2081` 的完整CI通过。首次CI发现前端workspace缺少packages字段，两端补充后全新安装/构建与容器运行通过。文档归档清单使用Git规范化后的文件字节计算SHA-256。保留原始需求Markdown换行和TXT末尾格式，其他差异检查无错误。临时验证进程已停止，没有删除本机数据库与原始材料。
