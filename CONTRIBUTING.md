# 开发与协作

先读 README、项目任务书、需求决策和生产需求核查报告。默认使用简体中文文档、注释及沟通，类名和字段保留英文。

## 修改前

分析类、方法、调用链与影响范围，多文件变更先列出文件和原因。公共 API、DTO、字段、枚举和 RPC 变更须说明兼容和回滚；优先向后兼容。不要重构无关代码或覆盖已有本机改动，不批量删除文件/目录。

## 实现约束

- Controller 保持简洁，禁止直接调用 Mapper；业务流程与事务由 ServiceImpl 承担。
- Mapper 接口只定义方法签名，SQL 与动态条件落到 Mapper.xml；禁止 Wrapper 拼接 SQL。
- Java 命名表达业务语义；类 UpperCamelCase，变量/方法 lowerCamelCase，常量 UPPER_SNAKE_CASE，包名小写。
- 状态更新关注事务、幂等、并发及部分失败；定时任务、附件和打印额外分析一致性。
- 不吞异常，不向前端暴露内部异常；系统异常日志保留堆栈和业务编号，不记录凭据。
- 已执行 Flyway 迁移不可直接改写；新增版本并同步适用的 SQL 镜像。V16 是 Java 迁移，不存在 V16 SQL 镜像。

## 提交前验证

```sh
mvn -B -f bike-server/pom.xml verify
pnpm --dir bike-web install --frozen-lockfile
pnpm --dir bike-web build
pnpm --dir bike-h5 install --frozen-lockfile
pnpm --dir bike-h5 build
git diff --check
```

API/数据库/页面变更还应验证具体业务路径。硬件出纸、扫码枪和云服务必须独立验收，不能用单元测试或截图替代。历史 `tools/production_*.py` 会写验收数据，仅供阅读或在专用隔离环境使用。

更新相应需求/接口/部署文档，在 PR 写清问题、最终行为、影响范围、测试结果和未验证部分。不将 `.env`、运行日志、数据库文件或密钥提交 Git。
