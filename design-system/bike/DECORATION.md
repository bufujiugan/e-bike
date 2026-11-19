# 账号装饰图片

## 使用方法

1. 点击右上角账号头像，选择「更换装饰图片」。
2. 点击「选择自己的图片」，查看页面大图和侧栏小图预览。
3. 点击「保存图片」后，工作台、运营概览和公共页面横幅、侧栏同步生效。
4. 要使用原来的黄色小宠物，点击「恢复默认」再「保存图片」。取消或直接关闭不会保存当前预览。

仅对当前登录账号生效，图片保存在服务端；换设备登录同一服务时可重新加载。已打开的其他设备页面需刷新或重新登录。登录前的公共背景仍显示默认图片。

支持 PNG / JPG / WebP，最大 2 MB，单边不超过 4096 像素。服务器按比例缩小到最长边不超过 384px，不放大原图、不裁切，保留透明背景，重新编码为 PNG。推荐透明底 PNG。

## 接口与兼容性

- 新增 `GET /api/auth/decoration`：返回 `{ imageDataUrl: string | null }`，沿用统一 ApiResponse 包装；未设置时为 null。响应使用 `Cache-Control: no-store`。
- 新增 multipart `PUT /api/auth/decoration`：上传字段 `file`，保存并返回规范化后的图片。
- 新增 `DELETE /api/auth/decoration`：恢复默认，可重复调用。
- 仅从 `CurrentOperator.id()` 获取当前账号，不接受目标用户 ID 或客户端文件名作为存储路径。
- 文件路径为 `bike.production.file-root/decorations/user-{userId}.png`，与头像隔离。先写同目录暂存文件，再原子替换；并发操作以最后完成的文件操作为准。替换失败保留旧文件。
- 无数据库迁移，未修改现有头像、密码、权限、订单等接口或字段。
- 前端由 `AppLayout.vue` 提供账号范围的响应式图片状态；账号切换/卸载时使旧请求失效，避免上个账号的异步结果覆盖新账号。
- 回退时移除新入口和图片状态注入即可恢复静态默认图；独立图片文件不会影响既有头像或业务数据。

## 本机验收（2026-11-19）

- Web TypeScript + Vite 构建通过：`output/decoration-web-build.log`。
- 装饰图片 5 项服务测试、既有头像 4 项回归测试通过：`output/decoration-server-package.log`。覆盖持久化、账号隔离、重复恢复、格式/大小/尺寸拒绝、认证、透明度、保留比例与不放大小图。
- 原包文件被运行服务占用；改为独立产物后打包成功：`bike-server/target/bike-server-decoration-preview-20261119.jar`，日志 `output/decoration-preview-package.log`。
- 本机新预览 API 使用 127.0.0.1:18082，沿用独立预览数据库，关闭本次启动的 Flyway；Web 5173 的本机代理已指向新 API。原 18081 服务保留。
- 浏览器实测：上传预览、保存后工作台/侧栏立即更新、刷新后保留、运营页同步、更换预览后取消保留原图、恢复默认；390px 弹窗宽 358px，无页面横向溢出。
- 另一个独立 HTTP 登录会话读取到同账号已保存图片，验证服务端读取及 no-store；未进行真实手机设备实测。
- 测试图片已恢复为默认，截图在 `outputs/decoration-upload-20261119/`。
- 功能仅本机运行，尚未部署生产。
