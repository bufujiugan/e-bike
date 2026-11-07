import test, { beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { get, post, uploadImage, ApiError } from "../common/api.js";
import {
  readDraft,
  saveDraft,
  removeDraft,
  saveSession,
} from "../common/storage.js";
import {
  writeOnce,
  assertQueue,
  dispatchPayload,
} from "../common/operations.js";
const values = new Map();
let handler, uploadHandler, sequence;
beforeEach(() => {
  values.clear();
  sequence = 0;
  globalThis.uni = {
    getStorageSync: (key) => values.get(key),
    setStorageSync: (key, value) => values.set(key, structuredClone(value)),
    removeStorageSync: (key) => values.delete(key),
    reLaunch() {},
    request(options) {
      handler(options);
    },
    uploadFile(options) {
      uploadHandler(options);
      return { onProgressUpdate() {} };
    },
  };
  saveSession({ username: "operator", accessToken: "test" });
  handler = (options) =>
    options.success({ statusCode: 200, data: { code: "SUCCESS", data: {} } });
});
const ok = (options, data) =>
  options.success({ statusCode: 200, data: { code: "SUCCESS", data } });
function pageInstance(name, overrides = {}) {
  const file = readFileSync(
    new URL(`../pages/${name}/${name}.nvue`, import.meta.url),
    "utf8",
  );
  const script = file
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s+[\s\S]*?\sfrom\s+["'][^"']+["'];?/gm, "")
    .replace("export default", "const component =");
  const context = {
    page: {},
    confirm: async () => true,
    get,
    post,
    readDraft,
    saveDraft,
    removeDraft,
    writeOnce,
    assertQueue,
    dispatchPayload,
    uploadImage,
    uuid: () => `request-${++sequence}`,
    uni: globalThis.uni,
    console,
    ...overrides,
  };
  const component = vm.runInNewContext(
    `(function(){${script};return component})()`,
    context,
  );
  const instance = {
    ...component.data(),
    ready: true,
    busy: false,
    context: { username: "operator" },
    message: "",
    failed: false,
    pending: null,
  };
  for (const [name, method] of Object.entries(component.methods || {}))
    instance[name] = method.bind(instance);
  for (const [name, getter] of Object.entries(component.computed || {}))
    Object.defineProperty(instance, name, { get: () => getter.call(instance) });
  instance.run = async (action) => {
    if (instance.busy) return;
    instance.busy = true;
    try {
      return await action();
    } finally {
      instance.busy = false;
    }
  };
  instance.execute = (name, ...args) => {
    const run = instance.run;
    let pending;
    instance.run = (action) => {
      pending = run(action);
      return pending;
    };
    try {
      instance[name](...args);
    } finally {
      instance.run = run;
    }
    return pending;
  };
  return instance;
}
test("组装完成后列表刷新失败，仍保存聚合码、清除当前任务并可打印", async () => {
  const page = pageInstance("assembly");
  page.workOrderId = 9;
  page.task = { id: "TASK-1", scans: [] };
  handler = (options) => {
    if (options.method === "POST")
      ok(options, {
        id: "TASK-1",
        workOrderId: 9,
        status: "COMPLETED",
        qrCode: "QR",
        scans: [],
      });
    else options.fail();
  };
  await page.onScan("LAST-CODE");
  assert.equal(page.task, null);
  assert.equal(page.taskId, "");
  assert.equal(page.lastCompleted.qrCode, "QR");
  assert.equal(readDraft("assembly").taskId, "");
  assert.match(page.message, /绑定完成/);
  assert.match(page.message, /刷新失败/);
});
test("首码返回成功后进程退出，重启从收据恢复新任务 ID", async () => {
  saveDraft("result:assembly", {
    result: {
      id: "NEW-TASK",
      workOrderId: 9,
      status: "OPEN",
      scans: [{ codeType: "MOTOR", code: "M1" }],
    },
  });
  handler = (options) => {
    if (options.url.includes("/work-orders"))
      ok(options, { records: [], total: 0, pageSize: 20 });
    else
      ok(options, {
        id: "NEW-TASK",
        workOrderId: 9,
        status: "OPEN",
        scans: [{ codeType: "MOTOR", code: "M1" }],
      });
  };
  const page = pageInstance("assembly");
  await page.load();
  assert.equal(page.taskId, "NEW-TASK");
  assert.equal(page.task.scans.length, 1);
});
test("PI 超时后跨页面恢复使用同一个 requestId，不再询问生成新批次", async () => {
  const sent = [],
    detail = {
      id: 3,
      piNo: "PI-3",
      customerId: 1,
      orders: [{ orderId: 7, remainingQty: 2, status: "CONFIRMED" }],
    };
  const first = pageInstance("pi");
  first.detail = detail;
  first.queue = [{ orderId: 7, vehicleQrCode: "QR-1", frameSn: "FRAME-1" }];
  handler = (options) => {
    sent.push(options.data);
    options.fail();
  };
  await assert.rejects(first.execute("submit"), (error) => error.uncertain);
  const id = first.attempt.requestId;
  assert.equal(readDraft("pi-dispatch").attempt.requestId, id);
  await assert.rejects(first.onScan("QR-2"), /不能增加/);
  let confirms = 0;
  const restored = pageInstance("pi", {
    confirm: async () => {
      confirms++;
      return true;
    },
  });
  handler = (options) => {
    if (options.url.includes("/options"))
      ok(options, { records: [], total: 0 });
    else ok(options, detail);
  };
  await restored.load();
  handler = (options) => {
    if (options.method === "POST") {
      sent.push(options.data);
      ok(options, {
        id: 8,
        batchNo: "BATCH-8",
        status: "COMPLETED",
        originalQty: 1,
        activeQty: 1,
      });
    } else options.fail();
  };
  await restored.execute("submit");
  assert.equal(sent[1].requestId, id);
  assert.equal(confirms, 0);
  assert.equal(restored.queue.length, 0);
  assert.equal(restored.result.batchNo, "BATCH-8");
  assert.equal(readDraft("pi-dispatch").result.batchNo, "BATCH-8");
  assert.match(restored.message, /刷新失败/);
});
test("普通订单成功收据消除崩溃前遗留队列，避免再次出库", async () => {
  saveDraft("order-dispatch", {
    customerId: 1,
    orderId: 7,
    queue: [{ orderId: 7, vehicleQrCode: "QR-1", frameSn: "F1" }],
    selectedOrder: { id: 7, orderNo: "O7", orderQty: 2, shippedQty: 0 },
  });
  saveDraft("result:stock-out", {
    payload: { orderId: 7, vehicleQrCodes: ["QR-1"] },
    result: { id: 8 },
  });
  handler = (options) =>
    ok(
      options,
      options.url.includes("/customers")
        ? [{ id: 1, name: "客户", enabled: true }]
        : { records: [], total: 0 },
    );
  const page = pageInstance("stock-out");
  await page.load();
  assert.equal(page.queue.length, 0);
  assert.equal(readDraft("order-dispatch").queue.length, 0);
});
test("入库码改变后必须重新校验，仓库切换先清掉旧库位", async () => {
  const page = pageInstance("stock-in");
  page.code = "QR-1";
  page.checked = { valid: true };
  page.changeCode("QR-2");
  assert.equal(page.checked, null);
  page.warehouseId = 1;
  page.locationId = 10;
  page.locations = [{ id: 10 }];
  handler = (options) => options.fail();
  // UI 的方法会把执行结果交给 run；此处捕获异步错误并等待它完成。
  let finished;
  page.run = (action) => {
    finished = action().catch(() => {});
  };
  page.chooseWarehouse(2);
  await finished;
  assert.equal(page.warehouseId, 2);
  assert.equal(page.locationId, "");
  assert.equal(page.locations.length, 0);
});
test("返工连续扫描只更新指定字段，不覆盖原因", async () => {
  const page = pageInstance("rework");
  page.reason = "电池异常";
  await page.onScan("QR");
  assert.equal(page.scanTarget, "part");
  await page.onScan("BATTERY");
  assert.equal(page.vehicleQrCode, "QR");
  assert.equal(page.newPartSn, "BATTERY");
  assert.equal(page.reason, "电池异常");
});
test("图片部分失败单独重试沿用 requestId，已成功图片不会再上传", async () => {
  const page = pageInstance("issues");
  page.issueId = "ISSUE-1";
  page.entries = [
    { id: "image-1", path: "/one.jpg", state: "ready", progress: 0 },
    { id: "image-2", path: "/two.jpg", state: "ready", progress: 0 },
  ];
  const calls = [];
  uploadHandler = (options) => {
    calls.push(options.formData.requestId);
    if (options.formData.requestId === "image-2" && calls.length === 2)
      options.fail();
    else ok(options, { id: "attachment" });
  };
  await page.execute("uploadAll");
  assert.equal(page.entries[0].state, "saved");
  assert.equal(page.entries[1].state, "failed");
  await page.execute("uploadAll");
  assert.deepEqual(calls, ["image-1", "image-2", "image-2"]);
  assert.equal(page.entries[1].state, "saved");
});
test("打印发送后保持 PROCESSING，刷新或重开不会再次发送，回报失败可以重试回报", async () => {
  let sends = 0,
    claims = 0;
  const page = pageInstance("prints", {
    deviceConfig: () => ({ terminal: "PDA-1" }),
    buildPaper: () => ({ attrs: { width: 400, height: 800 }, elements: [] }),
    createDevice: () => ({
      open() {},
      close() {},
      async ready() {},
      async print() {
        sends++;
      },
    }),
  });
  page.job = { id: "PRINT-1", status: "PENDING" };
  handler = (options) => {
    claims++;
    ok(options, { id: "PRINT-1", status: "PROCESSING", claimedBy: "operator" });
  };
  await page.execute("print");
  assert.equal(sends, 1);
  assert.equal(page.job.status, "PROCESSING");
  assert.equal(page.canReport, true);
  assert.ok(readDraft("print-attempt:PRINT-1"));
  await assert.rejects(page.execute("print"), /重复发送/);
  assert.equal(sends, 1);
  assert.equal(claims, 1);
  handler = (options) => options.fail();
  await assert.rejects(
    page.execute("report", "SUBMITTED"),
    (error) => error.uncertain,
  );
  assert.equal(page.job.status, "PROCESSING");
  handler = (options) => ok(options, null);
  await page.execute("report", "SUBMITTED");
  assert.equal(page.job.status, "SUBMITTED");
  assert.equal(sends, 1);
});
test("硬件未就绪不领取服务器打印任务", async () => {
  let claims = 0;
  const page = pageInstance("prints", {
    deviceConfig: () => ({ terminal: "PDA-1" }),
    buildPaper: () => ({ attrs: { width: 570, height: 600 }, elements: [] }),
    createDevice: () => ({ open() {}, close() {}, async ready() { throw new Error("608 电源未就绪"); } }),
  });
  page.job = { id: "PRINT-2", status: "PENDING" };
  handler = options => { claims++; ok(options, {}); };
  await assert.rejects(page.execute("print"), /电源未就绪/);
  assert.equal(claims, 0);
  assert.equal(readDraft("print-attempt:PRINT-2"), null);
});
