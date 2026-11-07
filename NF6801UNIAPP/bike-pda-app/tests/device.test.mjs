import test from "node:test";
import assert from "node:assert/strict";
import { createDevice } from "../common/device.js";
test("黑标初始化期间不提前打印，页面退出后取消尚未发送的标签", async () => {
  const originalNow = Date.now;
  const originalTimeout = globalThis.setTimeout;
  let releaseWait;
  let delay;
  let prints = 0;
  Date.now = () => 1000;
  globalThis.setTimeout = (callback, milliseconds) => {
    releaseWait = callback;
    delay = milliseconds;
  };
  globalThis.uni = {
    getStorageSync: () => null,
    requireNativePlugin: () => ({
      init() {},
      closeGpio() {},
      closeDevice() {},
      printPaper() {
        prints++;
      },
    }),
  };
  const device = createDevice();
  try {
    device.open();
    const pending = device.print({
      attrs: { width: 400, height: 600 },
      elements: [],
    });
    assert.ok(delay >= 2500);
    assert.equal(prints, 0);
    device.close();
    releaseWait();
    await assert.rejects(pending, /设备已关闭/);
    assert.equal(prints, 0);
  } finally {
    device.close();
    Date.now = originalNow;
    globalThis.setTimeout = originalTimeout;
  }
});
test("页面只释放广播订阅，不重复关闭进程级厂商串口", () => {
  let receiver,
    registered = 0,
    unregistered = 0,
    actions = [],
    closed = 0,
    gpio = 0,
    scans = [];
  const module = {
    init() {},
    closeDevice() {
      closed++;
    },
    closeGpio() {
      gpio++;
    },
    scan() {},
  };
  globalThis.uni = {
    getStorageSync() {
      return null;
    },
    requireNativePlugin() {
      return module;
    },
  };
  globalThis.plus = {
    android: {
      runtimeMainActivity() {
        return {
          registerReceiver(value) {
            receiver = value;
            registered++;
          },
          unregisterReceiver() {
            unregistered++;
          },
        };
      },
      importClass(value) {
        if (typeof value === "string")
          return class {
            addAction(action) {
              actions.push(action);
            }
          };
      },
      implements(name, value) {
        return value;
      },
    },
  };
  const device = createDevice();
  device.open((value) => scans.push(value));
  assert.deepEqual(actions, [
    "com.qs.scandode",
    "com.qs.scancode",
    "com.android.server.scannerservice.broadcast",
  ]);
  receiver.onReceive(null, {
    getStringExtra() {
      return "CODE-1";
    },
  });
  assert.deepEqual(scans, ["CODE-1"]);
  for (const value of ["##55UP", "\x1b##55DN", "##56UP", "no paper"])
    receiver.onReceive(null, { getStringExtra: () => value });
  assert.deepEqual(scans, ["CODE-1"], "控制回包不得触发任何业务回调");
  receiver.onReceive(null, { getStringExtra: () => "CODE-1" });
  assert.deepEqual(scans, ["CODE-1"]);
  const previousReceiver = receiver;
  device.open((value) => scans.push(value));
  previousReceiver.onReceive(null, { getStringExtra: () => "STALE-CODE" });
  assert.deepEqual(scans, ["CODE-1"]);
  assert.equal(registered, 2);
  assert.equal(unregistered, 1);
  device.close();
  device.close();
  receiver.onReceive(null, {
    getStringExtra() {
      return "CODE-2";
    },
  });
  assert.equal(scans.length, 1);
  assert.equal(closed, 0);
  assert.equal(gpio, 0);
});
test("等待初始化时关闭并重新打开，不得发送旧扫描请求", async () => {
  const originalTimeout = globalThis.setTimeout;
  let releaseWait, scans = 0;
  globalThis.setTimeout = callback => { releaseWait = callback; };
  globalThis.uni = { getStorageSync: () => null, requireNativePlugin: () => ({ init() {}, scan() { scans++; } }) };
  const device = createDevice();
  try {
    device.open();
    const pending = device.scan();
    device.close(); device.open();
    releaseWait();
    await assert.rejects(pending, /设备已关闭/);
    assert.equal(scans, 0);
  } finally { device.close(); globalThis.setTimeout = originalTimeout; }
});
test("608状态失败不会误报已启动扫描", async () => {
  const originalNow = Date.now;
  let scans = 0;
  Date.now = () => 1000;
  globalThis.uni = { getStorageSync: () => null, requireNativePlugin: () => ({
    init() {}, hardwareStatus(callback) { callback({ ready: false, initializing: false, message: "电源连接失败" }); },
    scan() { scans++; }
  }) };
  const device = createDevice();
  try { device.open(); Date.now = () => 10000; await assert.rejects(device.scan(), /电源连接失败/); assert.equal(scans, 0); }
  finally { device.close(); Date.now = originalNow; }
});
test("608 软解码 scannerdata 与硬件 SDK code 均可进入扫码回调", () => {
  const delivered = [];
  let receiver;
  globalThis.uni = {
    getStorageSync() {
      return null;
    },
    requireNativePlugin() {
      return { init() {}, closeGpio() {}, closeDevice() {}, scan() {} };
    },
  };
  globalThis.plus = {
    android: {
      runtimeMainActivity() {
        return {
          registerReceiver(value) {
            receiver = value;
          },
          unregisterReceiver() {},
        };
      },
      importClass(value) {
        if (typeof value === "string")
          return class {
            addAction() {}
          };
      },
      implements(name, value) {
        return value;
      },
    },
  };
  const device = createDevice();
  device.open((value) => delivered.push(value));
  for (const key of ["scannerdata", "code"])
    receiver.onReceive(null, {
      getStringExtra(name) {
        return name === key ? `${key}-VALUE` : null;
      },
    });
  assert.deepEqual(delivered, ["scannerdata-VALUE", "code-VALUE"]);
  device.close();
});
test("初始化失败向上传递，不重复关闭共享串口", () => {
  let closed = 0;
  globalThis.uni = {
    getStorageSync() {
      return null;
    },
    requireNativePlugin() {
      return {
        init() {
          throw new Error("device failed");
        },
        closeGpio() {},
        closeDevice() {
          closed++;
        },
      };
    },
  };
  assert.throws(() => createDevice().open(), /device failed/);
  assert.equal(closed, 0);
});
test("打印序列化为 AAR printPaper(String, boolean) 所需 JSON，调用不伪造完成回报", async () => {
  let captured,
    clock = Date.now;
  Date.now = () => 100000;
  globalThis.uni = {
    getStorageSync() {
      return null;
    },
    requireNativePlugin() {
      return {
        init() {},
        closeGpio() {},
        closeDevice() {},
        printPaper(value, feed) {
          captured = { value, feed };
        },
        hardwareStatus(callback) {
          callback({ ready: true, printState: "SENT", printSequence: 1 });
        },
        preparePrint(callback) {
          callback({ ready: true, printState: "PREPARING", printSequence: 1 });
        },
      };
    },
  };
  const device = createDevice();
  device.open();
  Date.now = () => 200000;
  try {
    await device.print({ attrs: { width: 400, height: 600 }, elements: [] });
    assert.deepEqual(JSON.parse(captured.value).attrs, {
      width: 400,
      height: 600,
    });
    assert.equal(captured.feed, true);
  } finally {
    Date.now = clock;
    device.close();
  }
});

test("缺纸或原生发送失败不能误报打印成功，且不自动重打", async () => {
  const clock = Date.now;
  let sent = 0;
  globalThis.uni = { getStorageSync: () => null, requireNativePlugin: () => ({
    init() {},
    preparePrint(cb) { cb({ ready: true, printState: "PREPARING", printSequence: 3 }); },
    printPaper() { sent++; },
    hardwareStatus(cb) { cb(sent ? { ready: true, printSequence: 3, printState: "FAILED", printMessage: "设备报告缺纸" } : { ready: true }); }
  }) };
  const device = createDevice();
  try {
    Date.now = () => 1000; device.open(); Date.now = () => 10000;
    await assert.rejects(device.print({ attrs: { width: 570, height: 600 }, elements: [] }), /缺纸/);
    assert.equal(sent, 1);
  } finally { device.close(); Date.now = clock; }
});

test("真实条码才能完成扫码等待，控制回包不会触发业务", async () => {
  const clock = Date.now;
  let receiver;
  const scans = [];
  globalThis.plus = { android: {
    runtimeMainActivity: () => ({ registerReceiver(value) { receiver = value; }, unregisterReceiver() {} }),
    importClass(value) { if (typeof value === "string") return class { addAction() {} }; },
    implements: (name, value) => value,
  } };
  globalThis.uni = { getStorageSync: () => null, requireNativePlugin: () => ({
    init() {},
    hardwareStatus(cb) { cb({ ready: true, scanState: "COMMAND_SENT" }); },
    scan(value, cb) {
      receiver.onReceive(null, { getStringExtra: () => "##55UP" });
      assert.equal(scans.length, 0);
      receiver.onReceive(null, { getStringExtra: () => "BIKE-REAL-001" });
      cb({ ready: true });
    },
  }) };
  const device = createDevice();
  try {
    Date.now = () => 1000; device.open(value => scans.push(value)); Date.now = () => 10000;
    await device.scan();
    assert.deepEqual(scans, ["BIKE-REAL-001"]);
  } finally { device.close(); Date.now = clock; }
});

test("只收到控制回包时扫码超时，不能假报成功", async () => {
  const clock = Date.now, timeout = globalThis.setTimeout;
  globalThis.setTimeout = (fn, delay) => timeout(fn, delay === 200 ? 0 : delay);
  globalThis.uni = { getStorageSync: () => null, requireNativePlugin: () => ({
    init() {}, hardwareStatus(cb) { cb({ ready: true, scanState: "COMMAND_SENT" }); },
    scan(value, cb) { cb({ ready: true }); }
  }) };
  const device = createDevice();
  try {
    Date.now = () => 1000; device.open(); Date.now = () => 10000;
    await assert.rejects(device.scan(), /未收到有效条码/);
  } finally { device.close(); Date.now = clock; globalThis.setTimeout = timeout; }
});
