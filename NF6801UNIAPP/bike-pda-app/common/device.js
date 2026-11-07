import { deviceConfig } from "./storage.js";
import { isBarcodePayload } from "./scan-payload.js";
export function sdk() {
  const module = uni.requireNativePlugin("Qs601Module");
  if (!module || typeof module.init !== "function")
    throw new Error(
      "当前安装包未包含 Qs601Module，请安装带设备插件的自定义基座或 APK",
    );
  return module;
}
export function createDevice() {
  let receiver = null,
    activity = null,
    opened = false,
    readyAt = 0,
    generation = 0,
    decodedSequence = 0,
    lastCode = "",
    lastCodeAt = 0;
  const close = () => {
    generation++;
    opened = false;
    if (receiver && activity) {
      try {
        activity.unregisterReceiver(receiver);
      } catch (cause) {
        console.error("扫码广播注销失败", cause);
      }
    }
    receiver = null;
    activity = null;
    // 厂商串口是进程级单例。页面仅注销自己的订阅，不关闭其他页面使用的串口。
  };
  const nativeCall = (method, ...args) =>
    new Promise((resolve, reject) => {
      const timeout = setTimeout(
        () => reject(new Error("设备接口响应超时，请重启应用")),
        4000,
      );
      try {
        const module = sdk();
        if (typeof module[method] !== "function")
          throw new Error("请安装包含 608 兼容层的新版 APK");
        module[method](...args, (value) => {
          clearTimeout(timeout);
          try {
            resolve(typeof value === "string" ? JSON.parse(value) : value);
          } catch (cause) {
            reject(new Error("设备返回状态格式异常"));
          }
        });
      } catch (cause) {
        clearTimeout(timeout);
        reject(cause);
      }
    });
  const waitReady = async () => {
    const current = generation;
    if (!opened) throw new Error("设备未就绪");
    await new Promise((resolve) =>
      setTimeout(resolve, Math.max(0, readyAt - Date.now())),
    );
    for (let attempt = 0; attempt < 40; attempt++) {
      if (!opened || current !== generation)
        throw new Error("设备已关闭，请返回后重试");
      const status = await nativeCall("hardwareStatus");
      if (!opened || current !== generation)
        throw new Error("设备已关闭，请返回后重试");
      if (status?.ready) return;
      if (!status?.initializing)
        throw new Error(status?.message || "608 硬件未就绪");
      await new Promise((resolve) => setTimeout(resolve, 200));
    }
    throw new Error("608 硬件初始化超时，请完全退出厂商 Demo 后重启应用");
  };
  return {
    open(onScan) {
      close();
      const current = generation;
      lastCode = "";
      try {
        const config = deviceConfig(),
          module = sdk();
        // 厂商 SDK 初始化硬件后延迟 2500ms 配置标签模式。
        opened = true;
        module.init("", config.blackMark, config.offset);
        readyAt = Date.now() + 2700;
        if (onScan) {
          activity = plus.android.runtimeMainActivity();
          const IntentFilter = plus.android.importClass(
              "android.content.IntentFilter",
            ),
            filter = new IntentFilter();
          for (const action of [
            "com.qs.scandode",
            "com.qs.scancode",
            "com.android.server.scannerservice.broadcast",
          ])
            filter.addAction(action);
          receiver = plus.android.implements(
            "io.dcloud.feature.internal.reflect.BroadcastReceiver",
            {
              onReceive(context, intent) {
                plus.android.importClass(intent);
                const value = String(
                  intent.getStringExtra("data") ||
                    intent.getStringExtra("scannerdata") ||
                    intent.getStringExtra("code") ||
                    "",
                ).trim();
                const now = Date.now();
                if (!isBarcodePayload(value) || !opened || current !== generation) return;
                if (value === lastCode && now - lastCodeAt < 800) return;
                lastCode = value;
                lastCodeAt = now;
                decodedSequence++;
                onScan(value);
              },
            },
          );
          activity.registerReceiver(receiver, filter);
        }
      } catch (cause) {
        close();
        throw cause;
      }
    },
    close,
    async scan() {
      const current = generation;
      await waitReady();
      if (!opened || current !== generation) throw new Error("设备已关闭，请返回后重试");
      const before = decodedSequence;
      // 用户主动重扫是新请求，不被上次广播的800ms去重窗口吞掉。
      lastCode = "";
      const status = await nativeCall("scan", "");
      if (!status?.ready) throw new Error(status?.message || "扫描头未就绪");
      // 触发回调仅代表请求，必须等到真实条码；控制回包不解除等待。
      for (let attempt = 0; attempt < 50; attempt++) {
        if (!opened || current !== generation) throw new Error("设备已关闭，请返回后重试");
        if (decodedSequence !== before) return;
        const latest = await nativeCall("hardwareStatus");
        if (!latest?.ready || latest.scanState === "FAILED")
          throw new Error(latest.scanMessage || latest.message || "扫描指令发送失败");
        await new Promise((resolve) => setTimeout(resolve, 200));
      }
      if (decodedSequence !== before) return;
      throw new Error("未收到有效条码（控制回包已忽略），请检查扫描灯、对准条码后重试");
    },
    async print(paper) {
      const current = generation;
      await waitReady();
      const prepared = await nativeCall("preparePrint");
      if (!prepared?.ready || prepared.printState !== "PREPARING")
        throw new Error(prepared?.message || "打印机尚未准备好");
      sdk().printPaper(JSON.stringify(paper), deviceConfig().blackMark);
      for (let attempt = 0; attempt < 150; attempt++) {
        if (!opened || current !== generation)
          throw new Error("页面已关闭，打印结果需要核对；不会自动重打");
        const status = await nativeCall("hardwareStatus");
        if (status?.printSequence !== prepared.printSequence)
          throw new Error("打印请求已变化，请核对实物；不会自动重打");
        if (!status.ready || status.printState === "FAILED")
          throw new Error(status.printMessage || status.message || "打印发送失败，请核对实物");
        // 仅确认所有字节已写入串口，不把该状态回报为实物打印成功。
        if (status.printState === "SENT") return;
        await new Promise((resolve) => setTimeout(resolve, 200));
      }
      throw new Error("打印发送结果未确认，请核对实物；禁止直接重打原任务");
    },
    ready: waitReady,
  };
}
