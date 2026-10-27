# 当前账号头像

## 使用

点击右上角账号菜单 → 修改头像（也可从个人资料进入）→ 选择图片 → 保存头像。
支持 JPG、PNG、WebP，最大 2 MB，宽高均不超过 4096 像素。
服务端居中裁切并重新编码为 256×256 PNG；界面以圆形显示。
选择图片、恢复默认均先预览，保存后才生效；关闭弹窗丢弃未保存的改动。
顶栏、下拉菜单及个人资料同步展示头像，刷新或重新登录后仍从服务器读取。

## 新增接口

接口要求登录且已完成首次改密，只操作认证上下文中的当前账号，不接受 userId 或用户名参数。
现有账号、密码、权限及业务接口均不变。

| 方法 | 路径 | 请求 | 响应 data |
| --- | --- | --- | --- |
| GET | /api/auth/avatar | 无 | imageDataUrl：PNG Data URL 或 null |
| PUT | /api/auth/avatar | multipart/form-data，file 文件字段 | imageDataUrl：新头像 |
| DELETE | /api/auth/avatar | 无 | imageDataUrl：null |

沿用 ApiResponse，成功 code 为 SUCCESS。GET 禁止缓存。
失败代码包括 AVATAR_TOO_LARGE、AVATAR_IMAGE_INVALID、AVATAR_DIMENSIONS_INVALID、UNAUTHORIZED；
存储异常由现有全局异常处理返回 SYSTEM_ERROR，日志保留原始异常。

## 存储与一致性

- 文件保存在现有 BIKE_PRODUCTION_FILE_ROOT 目录的 avatars/user-{当前用户ID}.png。
- 头像使用该本地持久化目录，不走生产附件业务及 COS；Docker 已有 production-files 数据卷覆盖此目录。多实例部署须共享此目录，并随其他持久化文件备份。
- 不新增数据库字段或迁移。Controller 只接收请求与返回结果，处理流程在 AccountAvatarServiceImpl。
- 保存先生成完整缩略图，再在同目录原子替换；失败保留原头像。并发保存以最后成功替换者为准，重复恢复默认无副作用。
- 文件路径不使用客户端文件名；按内容校验图片格式，在解码前检查尺寸，重新编码去除原始附带数据。
- 回滚可恢复 AccountMenu.vue、移除新增头像接口和 API 封装，已保存文件可保留；无需数据库回滚。

## 2026-10-20 验证

- AccountAvatarServiceTest：4 项测试通过，覆盖重建服务后读取、256×256 编码、账号隔离、幂等恢复、伪造/空/超大/超尺寸图片拒绝、未登录保护。
- pnpm --dir bike-web build、mvn -f bike-server/pom.xml -DskipTests package、git diff --check 通过。前端保留既有包体积提示。
- 本地后端已更新，/actuator/health 返回 UP，5173 提供最新前端构建。
- Playwright（Edge）17 项断言通过，包含真实上传及刷新恢复、顶栏与下拉同步、取消不写入、前端大小/格式校验、失败重试、真实恢复默认、后端伪造图片/匿名请求拒绝及移动端显示。
- 保存失败使用浏览器拦截模拟 500；成功上传和恢复默认走真实服务。验收后恢复原账号头像，不保留测试图片。
- 截图：output/playwright/account-avatar-editor.png、account-avatar-menu.png、account-avatar-mobile.png。
