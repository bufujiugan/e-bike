# 608 原生兼容层

本层继续通过 `Qs601Module` 调用厂商的串口和打印实现，不使用网页或 WebView。

## 已证实的源码差异

- `608资料/608硬解码扫描打印/QS608Demo_studio1.rar` 中的 `PrintUtils.SERVICECONNECTION`，在设置 GPIO 72/150/151/158 前调用 `CtrlPrinterOn(false)`。
- 同一 Demo 的 `com/sdk/ICommService.aidl` 第三个方法为 `boolean CtrlPrinterOn(boolean isOn)`；旧 UniApp AAR 只有前两个 AIDL 方法，上电时没有该调用。
- 新旧 `MyApp.getSerialPort()` 均为 `/dev/ttyS1` / 230400，扫描触发均为 GPIO 167 后延迟 100ms 发送 `1B 23 23 35 35 44 4E`。本补丁不另开串口、不猜测新指令。
- 硬解码 Demo 返回 `com.qs.scancode` / `data`；软解码 Demo 返回 `com.android.server.scannerservice.broadcast` / `scannerdata`。额外兼容旧 SDK 中的 `com.qs.scandode`。广播拼写差异不能单独解释扫描灯不亮。
- 厂商明确提醒串口单例和应用间争用。页面退出只取消订阅，不再重复执行 `closeGpio` 和 `closeDevice`。

## 实现

`Qs608Hardware.java` 复用 `PrintUtils`，通过现有 Binder 的第三个 AIDL transaction 补齐 608 上电操作；只初始化一次，检查 Binder/串口并等待标签模式初始化。`CtrlPrinterOn` 的布尔返回值仅记录，不擅自推断其业务语义。

`PatchQs608.java` 使用 ASM 替换 `UpdateModule.init`、`scan` 的实现，保留原接口签名，追加 `hardwareStatus(callback)`。不更换业务 API。扫描 callback 仅表示触发请求状态，真实条码仍取广播；打印结果仍由操作员核对，不能将调用成功认定为出纸成功。

`scan` 的 callback 从原来回显入参改为 JSON 状态字符串，影响范围只有当前 APK 的 `common/device.js`，二者必须整包一起发布，不允许只热更新 JS。原厂工程及 AAR 未改，回滚时使用原 AAR 并回滚对应 JS（Android 降级不应要求用户卸载丢失草稿）。

使用 `tools/build-hardware.ps1` 从 `../vendor-source/QS_PDA_601-608/nativePlugins/Qs601Module/android/n601-release.aar` 重建。原 AAR SHA256：`74742FBBD0DAC6CFB709EF780E5F49B60EE5502E91EE2FA1B27E11B0CF1022D8`。1.0.1 AAR SHA256：`1C745ECEF2C409A7F71A6BAC7BC623CB65989E4D1BF193CC998D10E6ACD569E1`。

## 1.0.2 / 608-2 的追加修复

用户视频确认 `##55UP` 被误填入整车聚合码。`Qs608Protocol` 识别厂商控制帧；`Qs608Hardware.onDecodedScan` 取代旧SDK解码出口，只有真实码才广播和提示音，JS接收层二次过滤。保留已存在的API签名，新增 `preparePrint(callback)`；新JS和AAR必须整包配套。

608 Demo `printBitmap` 对位图添加一票一控起始 `1D2353D17AF84D`、可选标签走纸 `1D0C`、结束 `1D2345`。旧 AAR 缺少起止封包。本版保留原插件 Paper 绘制，替换其 `printBitmap` 为兼容实现，并精确分配 ESC * 24点列数据空间，修复570点宽的部分高度截断尾行问题。

`Qs608Transport` 沿用SDK已打开的串口、1000字节分包和打印机忙电平，增加可观察完成、10秒持续忙超时、IO异常锁定。`SENT`仅表示全部字节写入串口，不能作为实际打印成功；部分失败不自动重打、不自动回报业务成功。

1.0.2 AAR SHA256：`C16C50A17FA021449C497D02B803F780B96271D1ACCEB3BF2C7FEDB88418FE4B`。
执行 `tools/test-hardware.ps1` 验证控制帧、真实条码、完整打印封包、1–799点高标签尾点，以及串口分包/持续忙/部分写入失败。测试使用模拟硬件，不能代替现场验收。安装和验收见 `../REPAIR-608-1.0.2.md`。

## 1.0.3 / 608-3 模拟验证修复

替换`PrintUtils$ReadThread.run`为`Qs608Receiver.readLoop`，只使用原串口输入流。新增`Qs608ReceiveBuffer`处理跨read分片、CR/LF和空闲结束、控制/打印状态与真实码分离；去掉旧读线程提前过滤和200ms文本粘包逻辑。无歧义边界才交付条码，异常/超长/不完整帧不拼造业务码。

`tools/test-hardware-integration.ps1`直接加载发布AAR中的兼容类与被替换的原厂线程，模拟Android/Binder/串口后，把实际广播回放到page/device代码。配合`tools/test-hardware.ps1`覆盖4,410个分帧场景、10,000码随机分片、11类硬件状态/异常模拟和44个应用测试。见`../REPAIR-608-1.0.3.md`。

1.0.3 AAR SHA256：`451699D56B38D763D8C9CA561C0BB08D40793764A17E4E18B21C25D620856F04`。模拟验证不等于已在Android设备上验收。

## 打印与验收（现场）

设置中显式选择 608 的 80×100mm 版式：203 DPI、570 点宽、间隙模式。标签按完整字段重新排版，保留 EAN/UPC 图形与二维码，不改变后端历史快照；打印前显示旧模板尺寸及新输出规格，并要求确认。底部保留至少 7mm 的计算余量，同时保守预留起始偏移点数，实际走纸仍需现场校准。字段过长则拒绝发送，不截断、不无限缩小。

先在设置页打印带“自检”字样的非业务标签，然后扫码。自检不访问写入 API。完全退出/强制停止厂商 Demo 后再测，不自动卸载 Demo。

这只是依据 Demo 完成的源码修复及静态/自动化验证；没有 USB 真机时，不能声称扫描灯、解码、实际出纸、条码可读性或跳纸已验收。
