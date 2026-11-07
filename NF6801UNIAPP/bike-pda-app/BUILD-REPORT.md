# BIKE PDA 构建记录

## 2026-11-02：模拟回归修复版 1.0.3 / 103

- 官方安心打包于20:39:56完成，原始产物 `unpackage/release/apk/__UNI__9B4F0FE__20261102201532.apk`。
- 交付 `dist/BIKE-PDA-1.0.3-arm64.apk`，15,854,687字节；源与交付副本SHA256一致：`C5E6E9C3D36956F2F408D17D4393C221AFEC60CF16CB80FF5FD916762BE20BB7`。
- 原AppID、包名、发布签名保持；Android版本及嵌入资源版本均1.0.3/103，native渲染与12个页面齐全，兼容层608-3，无签名私钥和测试类混入。
- `tools/verify-hardware-dex.ps1`确认正式DEX的`PrintUtils$ReadThread.run`直接进入`Qs608Receiver.readLoop`；其余五个扫码/打印入口检查通过。1.0.2负向校验按预期拒绝。
- 旧AAR控制回包和真实码在200ms窗口内粘包丢码可重复，新版4,410个接收边界场景通过，包含1,000轮共10,000个条码随机分片；799种点阵高度与串口发送异常测试通过。
- 实际发布AAR执行11类硬件接口模拟，并将207个实际模拟广播回放到真实page/device代码；44项应用测试全部执行，0失败、0跳过。完整模拟连续重复3轮通过。
- 发布AAR SHA256：`451699D56B38D763D8C9CA561C0BB08D40793764A17E4E18B21C25D620856F04`。生产兼容类从AAR加载，Android/外设接口替身只在测试classpath；相对原厂AAR新增类全部属于生产兼容层。
- 修复范围限定串口接收、扫码重试边界、延迟扫描与打印竞争、接收故障停止发送。业务API、服务端规则和数据不变。
- 正式签名、原厂资料与全部旧APK保留；只删除经hash比对一致的单个缓存签名副本，临时打包配置计数为0。未付费加速，未提交/推送或下线旧网站。
- 真机NOT_RUN；系统模拟器尝试因无虚拟化驱动及软件模拟启动失败而未完成。没有安装驱动或修改系统虚拟化配置。详见 `REPAIR-608-1.0.3.md`。

## 2026-11-02：608 扫码与打印修复版 1.0.2 / 102

- 官方安心打包于 17:50:21 完成；原始产物 `unpackage/release/apk/__UNI__9B4F0FE__20261102173336.apk`。
- 交付 `dist/BIKE-PDA-1.0.2-arm64.apk`，15,854,687 字节；源文件与交付副本 SHA256 一致：`162333B80E903EFB54A7015505CC9E994B0801E7BDEBFC2E408C3D108A97EEB2`。
- 保持 AppID `__UNI__9B4F0FE`、包名 `com.bike.pda` 和原发布证书，版本 1.0.2 / 102，非 debuggable；支持覆盖安装。
- `tools/verify-apk.ps1` 验证签名、版本、12 个 native 页面、无业务 HTML、608-2 修复标识、厂商 arm64 库和无私钥文件全部通过。
- 正式 APK 的 DEX 反汇编确认 6 个入口调用：`UpdateModule.preparePrint/scan`、`PrintUtils$2.handleMessage`、`PrintUtils$3.handleMessage`、`PrintUtils.printBitmap/send`，均指向新版兼容层，不只是检查类名存在。
- 41 项本地自动化测试通过；Java 协议测试覆盖 1–799 点高的 570 点宽点阵及起止指令；串口模拟覆盖顺序、分包、持续忙超时和部分发送失败不重放。
- AAR 重建、独立 DEX 转换、应用资源编译与发布配置检查通过；正式完整打包成功。最终 AAR SHA256：`C16C50A17FA021449C497D02B803F780B96271D1ACCEB3BF2C7FEDB88418FE4B`。
- 处理视频中 `##55UP` 控制回包误入业务、打印缺少一票一控封包、点阵尾部空间不足与发送错误静默返回；默认兼容用户指定 80×100mm / 570 点配置。详见 `REPAIR-608-1.0.2.md`。
- 临时打包配置已自动清理；单个缓存签名经比对与原件一致后清理，正式签名与旧 APK 保留。未购买加速，未部署服务器、修改生产数据或下线旧站。
- 真机验收：NOT_RUN。电脑未连接设备；源码、模拟测试和正式包验证通过不代表扫码灯、识码、实际出纸及完整业务已经现场验收。

## 2026-11-02：608 修复版 1.0.1 / 101

- 已交付 `dist/BIKE-PDA-1.0.1-arm64.apk`，15,842,399 字节；原版保留。
- SHA256：`457F6135F9ACA786E8A5450AD012BF89EBAFBB317A78F6D7B7D3385987EC569D`。
- 33 项 mock 测试通过，原生兼容层 Java 编译和 DEX 转换通过（单独 DEX 转换存在未传入完整依赖的警告，完整云打包成功）。
- 官方安心打包于 14:54:03 完成。包名、版本、原发布证书、12 个 native 页面、608 原生兼容类、自检页面资源、串口库与服务包可见性通过产物检查。
- 608 补丁来源和兼容边界见 `native-compat/README.md`；安装后操作见 `REPAIR-608-1.0.1.md`。
- 本轮改动后的 AAR 不再与原版相同；厂商原始 AAR 保留在 vendor-source。下文 AAR 同 hash 的记录仅适用于 1.0.0。
- 单个缓存签名副本已清理，正式签名原件保留。未购买加速，未改生产数据，未下线旧站。
- 真机验收：NOT_RUN。源码差异及静态通过不能当作扫描灯、扫码或实际出纸成功的证据。

## 以下为 1.0.0 历史记录

检查日期：2026-10-29。

## 身份与配置

- AppID：`__UNI__9B4F0FE`，已在用户自己的 DCloud 账号创建。
- Android：`com.bike.pda`，正式版平台信息已登记。
- 版本：`1.0.0` / `100`。
- 发布签名：`F:\AndroidSigning\bike-pda\bike-pda-release.jks`，别名 `bike-pda-release`。
- SHA256：`79:3D:E6:39:8B:94:AC:C9:0A:5F:1C:A3:81:BA:9E:73:F2:B8:50:DD:AF:B3:D5:76:96:87:63:02:09:AC:A9:DD`。
- 签名密码、离线 AppKey 分别以 DPAPI 保存到签名目录；未输出明文。
- 官方 HBuilderX：5.33.2026102401，采用安心打包，专用证书本地签名。

## 已完成验证

- 12 个页面路由全部为 nvue；业务源码无网页容器、iframe 或网页桥接。
- npm 原生资源编译成功；HBuilderX 云打包前资源编译成功。
- HBuilderX 产物 AppID 正确、`plus.renderer=native`、`compilerVersion=5.33`。
- 25 项自动化测试通过，包括无幂等写入保护、PI 原 requestId 重试、打印防重发和初始化中断保护。
- 厂商 AAR 与原始示例 SHA256 一致：`74742FBBD0DAC6CFB709EF780E5F49B60EE5502E91EE2FA1B27E11B0CF1022D8`。
- 签名可解锁且别名验证成功，Android 图标和本地插件配置齐全。

## APK 状态

2026-10-29 18:07:54 官方安心打包完成，已在本机使用专用证书签名并通过静态校验。

- 交付文件：`F:\bike\NF6801UNIAPP\bike-pda-app\dist\BIKE-PDA-1.0.0-arm64.apk`。
- 原始产物：`unpackage/release/apk/__UNI__9B4F0FE__20261029174719.apk`。
- 大小：15,826,015 字节（约 15.1 MiB）。
- APK SHA256：`A004EBEEAC7E0E48F735820C59B9B2FFD67EC6E32C6AFE191C5A0DBE55269ACB`。
- 复制后校验与原始产物一致。
- v1 / v2 / v3 签名验证通过，发布证书指纹与项目专用证书一致。
- 应用名 `BIKE PDA`，包名 `com.bike.pda`，版本 `1.0.0` / `100`，非 debuggable。
- ABI：仅 `arm64-v8a`；minSdkVersion 21，targetSdkVersion 30。
- APK 内 12 个原生页面齐全，`renderer=native`；业务资源无 HTML。
- `Qs601Module` → `com.qs.n601.UpdateModule` 已注册，DEX 中存在对应类，厂商 `libzyapi_common.so` 已包含。
- APK 内不含 `.jks`、`.keystore`、`.dpapi` 文件。
- 公开验证结果：`dist/BIKE-PDA-1.0.0-arm64.verification.json`。

校验脚本明确调用 `java.exe -jar apksigner.jar`，避免旧版 Android SDK 的 bat 因缺失 `find_java.bat` 静默退出；JSON 文件以 UTF-8 读取，兼容 Windows PowerShell 5。

本次打包临时密码 JSON 已随打包脚本退出清理。单个临时签名副本 `unpackage/cache/wgt/__UNI__9B4F0FE/.manifest/google-keystore.jks` 已在比对原件一致后删除；`F:\AndroidSigning\bike-pda\bike-pda-release.jks` 原件完整保留。

2026-10-29 本轮核对默认 API 的 `/actuator/health` 返回 HTTP 200、`UP`。该检查仅说明服务健康，不等同于业务验收。

## 验收边界

ADB 当前未连接设备。扫码、打印、纸张与黑标、相机，以及真实服务器的完整业务流程尚未完成现场验收。没有下线旧 PDA 网站、删除 bike-h5、部署后端、提交或推送代码。
