# BIKE PDA 原生 APK 工程

本工程使用 NF6801UNIAPP 的厂商 SDK 和 UniApp Vue 2 nvue 原生渲染。12 个业务路由均为 `.nvue`，`manifest.json` 设置 `renderer: native`。页面不会打开 PDA 网站、嵌入 web-view 或执行网页脚本；业务通过 `uni.request` / `uni.uploadFile` 直接请求 bike-server。

原生资源编译成功不等于已生成 APK，更不等于完成真机验收。安装包交付前必须检查 Android 构建和签名，正式投入使用前必须验证安装启动和硬件行为。

## 当前安装包

2026-11-02 20:39:56 已生成模拟回归修复版 `dist/BIKE-PDA-1.0.3-arm64.apk`（103），15,854,687 字节，使用原发布签名。包名、版本、12 个原生页面、608-3 兼容层、串口库和自检资源检查通过；正式 APK 的 DEX 反汇编确认厂商读线程实际进入新接收器，并确认其余五个扫码/打印入口调用。旧 APK 保留作对照，不建议继续使用已复现漏码的1.0.2。详见 `REPAIR-608-1.0.3.md` 和 `dist/BIKE-PDA-1.0.3-arm64.verification.json`。

已在本机完成4,410个分帧场景、10,000码随机连续输入、11类实际AAR硬件接口模拟，以及44项包含原生广播到真实页面回放的测试；完整模拟连续重复3轮通过。使用 `tools/test-hardware.ps1` 和 `tools/test-hardware-integration.ps1` 可重跑。Android模拟器因虚拟化环境限制未成功启动，物理设备仍未连接；模拟通过不等于实物扫描灯或出纸验收。

在 NF6801 上覆盖安装新版，不先卸载 BIKE PDA，以保留作业草稿和打印记录。完全退出厂商 Demo 后，在设置中点击“应用本机 80×100mm 标签参数”并保存（570 点、203 DPI、间隙标签模式），再分别进行扫码自检和打印自检。当前电脑未连接设备，扫码灯、实际出纸、条码可读性、走纸和完整业务流程仍需真机验收；验收前保留旧 PDA 网站。

## 功能与源码

| 功能 | 页面 | 主要行为 |
| --- | --- | --- |
| 登录、权限、强制改密 | login / password / index | 账号上下文、工位权限、401 退出 |
| 生产组装 | assembly | 四码扫码、工单搜索、任务恢复与交接、完成总码 |
| 入库 | stock-in | 仓库库位选择、车辆校验、提交前失效保护 |
| 订单出库 | stock-out | 客户订单选择、按车架去重、数量限制 |
| PI 出库 | pi | 跨页保存队列、稳定 requestId、超时原请求重试 |
| 返工 | rework | 车辆与部件分开扫码、填写原因 |
| 追溯 | trace | 车辆、部件、库存及业务履历 |
| 生产异常 | issues | 登记隔离、拍照与相册、图片独立重试 |
| 打印 | prints | 任务快照、本机排版、领取、SDK 发送、人工结果回报、补打 |
| 设置 | settings | API 地址、终端编号、纸宽、有效点数、DPI、黑标 |

默认 API 地址配置在 `config.js`，使用管理端反向代理，与 PDA 网站独立。操作草稿按 API 服务器与账号隔离。无幂等 API 的未知写入结果会锁定，必须查任务或追溯核对后再解除，禁止直接重复提交。

## 开发验证

```powershell
npm ci
npm test
npm run build:app
npm run check:release
```

资源位于 `dist/build/app-plus`。构建脚本检查每个 nvue 路由的本轮产物，避免编译器异常返回 0 被误判为成功。`check:release` 检查当前安心打包配置，追加 `-- --offline` 可检查备用离线配置；不执行打包、不输出密码或 AppKey。自动化测试使用 mock，不能代替真实服务器和设备验收。

## 应用身份和签名

- 应用名：BIKE PDA；AppID：`__UNI__9B4F0FE`（项目自有账号创建）。
- Android 包名：`com.bike.pda`，记录于 `android-release.json`，实际打包时必须使用该值。
- 发布私钥：`F:\AndroidSigning\bike-pda\bike-pda-release.jks`。
- 别名：`bike-pda-release`；RSA 2048；有效期截至 2054-03-16。
- 密码：key/store 使用同一随机密码，保存在同目录 `password.dpapi`，仅当前 Windows 用户可解密；没有放入仓库或日志。
- 公开证书和指纹：同目录 `bike-pda-release.cer`、`signing-public.json`。

`tools/create-signing.ps1` 遇到既有文件会停止，不能覆盖现有证书。当前证书已经生成，后续直接复用。备份私钥时同时完成可恢复的密码保管：只把 DPAPI 文件复制到另一台电脑不能解密；在当前 Windows 账号仍可用时，应通过受控的本地流程将密码保存到自己的密码管理器或制作使用个人备份密码的加密证书备份。不要在聊天、终端日志或 Git 中粘贴密码。

## 打包要求

### 当前采用：官方安心打包

官方 HBuilderX 5.33 和 `app-safe-pack` 已安装在 `F:\bike\tmp\hbuilderx-5.33\portable\HBuilderX`。登录项目所属 DCloud 账号后，在本工程目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/package-apk.ps1
```

该脚本从 DPAPI 读取签名密码，临时配置只写入已限制访问的签名目录，命令结束后删除单个临时文件。启用 `safemode`，不上传业务源码和私钥，只提交原生模块配置和厂商插件；不启用广告或代码加密服务。产物在 `unpackage/release/apk`，必须继续运行 `tools/verify-apk.ps1 -Apk <实际文件路径>` 检查。

参考：https://ask.dcloud.net.cn/article/37979

### 备用：完整离线工程

1. 使用与编译器 5.33 匹配的官方 Android 离线 SDK，或匹配版本的 HBuilderX 原生打包。不要混用 SDK 和编译资源版本。
2. 在 DCloud 平台登记 `com.bike.pda` 和 `signing-public.json` 的 SHA1 / SHA256；离线打包 AppKey 必须绑定同一 AppID、包名和证书。不能复用厂商 Demo 身份。
3. 离线工程按照官方 `HBuilder-Integrate-AS` 配置基础库、nvue 引擎、Camera、Gallery 和 Native.js；将本工程原生资源放在 `assets/apps/__UNI__9B4F0FE/www`，同步 `dcloud_control.xml`。
4. 集成 `nativePlugins/Qs601Module/android/n601-release.aar`、arm64-v8a 下的 `libzyapi_common.so`，注册模块 `Qs601Module` → `com.qs.n601.UpdateModule`。标准调试基座没有本插件。
5. 使用本项目包名与专用签名，配置 INTERNET / CAMERA 等所需权限。当前 API 为 HTTP，已设置明文访问；迁移 HTTPS 后应统一调整。
6. APK 完成后使用 `apksigner verify --verbose --print-certs` 核验签名，并检查包名、版本、ABI、页面资源、AAR 和 SO。再进入真机验收。

官方文档：
- https://nativesupport.dcloud.net.cn/AppDocs/usesdk/android.html
- https://nativesupport.dcloud.net.cn/AppDocs/usesdk/appkey.html
- https://nativesupport.dcloud.net.cn/AppDocs/download/android.html
- https://uniapp.dcloud.net.cn/tutorial/nvue-outline.html

## 设备接口和验收限制

初始化使用 `Qs601Module.init('', blackMark, offset)`。厂商 SDK 内部在 2500ms 后设置黑标，因此打印前至少等待初始化完成。扫码调用 `scan('ok', callback)`；物理扫码通过 `com.qs.scancode` 广播的 `data` 字段接收；页面离开时注销广播并释放硬件。

1.0.2 打印先调用 `preparePrint(callback)`，再使用 `printPaper(JSON.stringify(paper), deviceConfig().blackMark)`，并查询本次序号对应的 `hardwareStatus`。纸张模式使用本机配置，不硬编码黑标模式。`SENT` 只表示串口字节发送完成；SDK 无可靠出纸成功回调，因此仍需操作员核对实物并回报，不能自动标记业务任务成功。EAN13 / UPCA 使用已核实的线段原语绘制。模板尺寸超过设备能力时拒绝打印，不擅自缩放截断。

首次使用应配置终端编号、实际纸宽和有效打印点数。当前仅配置 arm64-v8a，NF6801 实际 ABI、打印宽度、黑标偏移、走纸和实物清晰度均需现场验证。

详细清单见 `ACCEPTANCE.md`。在全部业务与真实设备验收通过前，保留 `bike-h5` 和现有 PDA 网站。本工程未执行下线、部署或数据迁移。
