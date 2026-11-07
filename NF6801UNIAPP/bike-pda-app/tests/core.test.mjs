import test, { beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { request, post } from "../common/api.js";
import {
  saveSession,
  scopeKey,
  saveDraft,
  readDraft,
  setOrigin,
} from "../common/storage.js";
import {
  writeOnce,
  pendingOperation,
  dispatchPayload,
  assertQueue,
} from "../common/operations.js";
import { canEnter } from "../common/auth.js";
import { maxPrintableDots } from "../config.js";
import {
  barcodeBits,
  buildPaper,
  buildHardwareTestPaper,
} from "../common/label.js";
const values = new Map();
let transport,
  redirects = [];
beforeEach(() => {
  values.clear();
  redirects = [];
  globalThis.uni = {
    getStorageSync: (key) => values.get(key),
    setStorageSync: (key, value) => values.set(key, structuredClone(value)),
    removeStorageSync: (key) => values.delete(key),
    reLaunch: (value) => redirects.push(value),
    request: (options) => transport(options),
  };
  saveSession({ username: "operator-a", accessToken: "test-token" });
  transport = (options) =>
    options.success({
      statusCode: 200,
      data: { code: "SUCCESS", data: { id: 1 } },
    });
});
test("请求直接访问 API 并携带会话，业务失败不会被 HTTP 200 掩盖", async () => {
  transport = (options) => {
    assert.match(options.url, /\/api\/stock-in$/);
    assert.equal(options.header.Authorization, "Bearer test-token");
    options.success({
      statusCode: 200,
      data: { code: "INVALID", message: "车辆已入库" },
    });
  };
  await assert.rejects(
    post("/api/stock-in"),
    (error) => error.message === "车辆已入库" && !error.uncertain,
  );
});
test("401 清会话并跳转登录", async () => {
  transport = (options) =>
    options.success({ statusCode: 401, data: { code: "UNAUTHORIZED" } });
  await assert.rejects(request("/api/auth/context"));
  assert.equal(values.has("bike.session"), false);
  assert.equal(redirects.length, 1);
});
test("旧请求不能清除新账号的会话", async () => {
  let pending;
  transport = (options) => {
    pending = options;
  };
  const promise = request("/api/auth/context");
  saveSession({ username: "operator-b", accessToken: "new-token" });
  pending.success({ statusCode: 401, data: { code: "UNAUTHORIZED" } });
  await assert.rejects(promise, (error) => error.uncertain);
  assert.equal(values.get("bike.session").username, "operator-b");
  assert.equal(redirects.length, 0);
});
test("普通写入超时保留落盘锁，拒绝自动重放", async () => {
  let calls = 0;
  transport = (options) => {
    calls++;
    options.fail();
  };
  await assert.rejects(
    writeOnce("stock-out", "/api/stock-out", { orderId: 1 }),
    (error) => error.uncertain,
  );
  assert.ok(pendingOperation("stock-out"));
  await assert.rejects(
    writeOnce("stock-out", "/api/stock-out", { orderId: 1 }),
    /未核对/,
  );
  assert.equal(calls, 1);
});
test("普通写入明确拒绝可以改正后重试，成功有收据且清锁", async () => {
  transport = (options) =>
    options.success({
      statusCode: 400,
      data: { code: "INVALID", message: "错误订单" },
    });
  await assert.rejects(writeOnce("stock-out", "/api/stock-out", {}));
  assert.equal(pendingOperation("stock-out"), null);
  transport = (options) =>
    options.success({
      statusCode: 200,
      data: { code: "SUCCESS", data: { id: "saved" } },
    });
  assert.equal(
    (await writeOnce("stock-out", "/api/stock-out", {})).id,
    "saved",
  );
  assert.equal(readDraft("result:stock-out").result.id, "saved");
  assert.equal(pendingOperation("stock-out"), null);
});
test("同账号不同服务器以及不同账号草稿隔离", () => {
  saveDraft("queue", { id: "a" });
  const key = scopeKey("queue");
  saveSession({ username: "operator-b", accessToken: "b" });
  assert.equal(readDraft("queue"), null);
  setOrigin("https://api.example.com");
  saveSession({ username: "operator-a", accessToken: "c" });
  assert.notEqual(scopeKey("queue"), key);
  assert.equal(readDraft("queue"), null);
});
test("未取得权限上下文时不开放工位，工位和权限都必须符合", () => {
  assert.equal(canEnter(null, "assembly"), false);
  assert.equal(
    canEnter(
      { roles: [], permissions: ["ASSEMBLY_OPERATE"], workstations: [] },
      "assembly",
    ),
    false,
  );
  assert.equal(
    canEnter(
      { roles: [], permissions: ["ASSEMBLY_OPERATE"], workstations: ["MOTOR"] },
      "assembly",
    ),
    true,
  );
  assert.equal(canEnter({ roles: ["SUPER_ADMIN"] }, "prints"), true);
});
test("PI 请求内容排序稳定，改变归属订单必须改变指纹", () => {
  const a = { orderId: 2, vehicleQrCode: "b" },
    b = { orderId: 1, vehicleQrCode: "a" };
  assert.deepEqual(dispatchPayload(3, [a, b]), dispatchPayload(3, [b, a]));
  assert.notEqual(
    dispatchPayload(3, [a]).fingerprint,
    dispatchPayload(3, [{ ...a, orderId: 1 }]).fingerprint,
  );
});
test("车辆按车架去重，跨不同聚合码也不能重复出库", () => {
  const queue = [{ orderId: 1, vehicleQrCode: "old", frameSn: "FRAME-1" }];
  assert.throws(
    () => assertQueue(queue, { valid: true, frameSn: "FRAME-1" }, "new", 1, 3),
    /重复/,
  );
  assert.throws(
    () => assertQueue(queue, { valid: true, frameSn: "FRAME-2" }, "new", 1, 1),
    /剩余/,
  );
  assert.throws(
    () => assertQueue([], { valid: false, message: "隔离" }, "new", 1, 3),
    /隔离/,
  );
});
test("EAN13 / UPCA 校验与条码位流、静区所需宽度", () => {
  const ean = barcodeBits("4006381333931", "EAN_13");
  assert.equal(ean.length, 95);
  assert.ok(ean.startsWith("101000110101001110101111011110100010010110011"));
  assert.equal(
    barcodeBits("036000291452", "UPC_A"),
    barcodeBits("0036000291452", "EAN_13"),
  );
  assert.throws(() => barcodeBits("4006381333932", "EAN_13"), /校验位/);
});
test("608 纸宽遵循厂商打印头点数限制", () => {
  assert.equal(maxPrintableDots(58), 384);
  assert.equal(maxPrintableDots(80), 570);
  assert.equal(maxPrintableDots(100), 0);
  assert.throws(
    () =>
      buildPaper(
        { id: "PRINT-1", snapshot: { ...snapshot, widthMm: 100 } },
        { paperWidthMm: 80, printableDots: 790, dpi: 203 },
      ),
    /570 点.*标签模板/,
  );
});
const snapshot = {
  heading: "BIKE",
  modelName: "Model",
  productName: "Bike",
  specification: "Spec",
  skuCode: "SKU1",
  ean: "4006381333931",
  upc: "036000291452",
  batchNo: "B1",
  frameSn: "F1",
  motorSn: "M1",
  batterySn: "B1",
  displaySn: "D1",
  qrCode: "QR1",
  templateName: "标准标签",
  templateVersion: 2,
  widthMm: 100,
  heightMm: 200,
  layout: "STANDARD",
};
test("完整打印计划包含商品、四码、QR、EAN/UPC 图形且尺寸不缩放", () => {
  const paper = buildPaper(
    { id: "PRINT-1", snapshot: JSON.stringify(snapshot) },
    { paperWidthMm: 100, printableDots: 800, dpi: 203 },
  );
  assert.equal(paper.attrs.width, 799);
  assert.ok(
    paper.elements.some(
      (element) => element.type === 3 && element.data === "QR1",
    ),
  );
  for (const value of [
    "Model",
    "Bike",
    "SKU1",
    "F1",
    "M1",
    "B1",
    "D1",
    "PRINT-1",
  ])
    assert.ok(
      paper.elements.some(
        (element) => element.type === 1 && element.data.includes(value),
      ),
    );
  assert.ok(paper.elements.filter((element) => element.type === 4).length > 40);
});
test("窄纸、过短标签、未知设备参数、坏条码均在领取前拒绝", () => {
  const job = { id: "PRINT-1", snapshot };
  assert.throws(
    () => buildPaper(job, { paperWidthMm: 58, printableDots: 384, dpi: 203 }),
    /超过/,
  );
  assert.throws(
    () =>
      buildPaper(
        { ...job, snapshot: { ...snapshot, heightMm: 60 } },
        { paperWidthMm: 100, printableDots: 800, dpi: 203 },
      ),
    /截断/,
  );
  assert.throws(
    () => buildPaper(job, { paperWidthMm: 0, printableDots: 0, dpi: 203 }),
    /设置/,
  );
  assert.throws(
    () =>
      buildPaper(
        { ...job, snapshot: { ...snapshot, ean: "invalid" } },
        { paperWidthMm: 100, printableDots: 800, dpi: 203 },
      ),
    /EAN/,
  );
});
test("全部已注册页面为 nvue，业务源码不使用网页容器或 HTML 标签接口", () => {
  const root = new URL("../", import.meta.url),
    routes = JSON.parse(readFileSync(new URL("pages.json", root), "utf8"));
  assert.equal(
    JSON.parse(readFileSync(new URL("manifest.json", root), "utf8"))["app-plus"]
      .renderer,
    "native",
  );
  assert.equal(routes.pages.length, 12);
  for (const route of routes.pages) {
    const source = readFileSync(new URL(route.path + ".nvue", root), "utf8");
    assert.doesNotMatch(
      source,
      /<web-view|<iframe|evalJS|plus\.webview|\/label['"`]/i,
      route.path,
    );
  }
});
const profile608 = {
  labelProfile: "608_80X100",
  paperWidthMm: 80,
  paperHeightMm: 100,
  printableDots: 570,
  dpi: 203,
  offset: 52,
  blackMark: true,
};
test("80×100 标签重新排版旧100mm快照，保留字段和条码，不改快照", () => {
  const job = { id: "PRINT-1", snapshot: structuredClone(snapshot) };
  const before = structuredClone(job);
  const paper = buildPaper(job, profile608);
  assert.deepEqual(job, before);
  assert.equal(paper.attrs.width, 570);
  assert.ok(paper.attrs.height <= 691);
  assert.equal(paper.elements.find((e) => e.type === 3).side, 240);
  for (const value of [
    snapshot.heading,
    snapshot.modelName,
    snapshot.productName,
    snapshot.skuCode,
    snapshot.specification,
    snapshot.batchNo,
    snapshot.frameSn,
    snapshot.motorSn,
    snapshot.batterySn,
    snapshot.displaySn,
    snapshot.ean,
    snapshot.upc,
    job.id,
  ])
    assert.ok(
      paper.elements.some((e) => e.type === 1 && e.data.includes(value)),
      value,
    );
  for (const e of paper.elements) {
    assert.ok(e.x >= 0 && e.y >= 0);
    assert.ok(e.x + (e.width || e.side || 0) <= 570);
    if (e.type === 4) {
      assert.equal(e.strokeWidth, 3);
      assert.ok(e.stopY <= paper.attrs.height);
    }
    if (e.type === 3) assert.ok(e.y + e.side <= paper.attrs.height);
  }
});
test("608自检纸可排版，越界或错误配置不发送且不截断", () => {
  assert.ok(buildHardwareTestPaper(profile608).attrs.height <= 691);
  assert.throws(
    () =>
      buildPaper(
        {
          id: "PRINT",
          snapshot: { ...snapshot, productName: "长商品名称".repeat(120) },
        },
        profile608,
      ),
    /未截断/,
  );
  assert.throws(
    () =>
      buildPaper(
        { id: "PRINT", snapshot },
        { ...profile608, printableDots: 790 },
      ),
    /570/,
  );
});
