import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import vm from 'node:vm';
import test from 'node:test';

const require = createRequire(new URL('../../bike-h5/package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), { parse, compileScript } = require('vue/compiler-sfc');
const qr = 'BIKE:20:d37baf95c4444509a0b1aafe3ea2ef03';
function harness(component) {
  const timers = new Map(), calls = [], notices = [], scope = vue.effectScope();
  let timerId = 0;
  const api = { isAxiosError: () => false, get: () => new Promise(() => {}), post: (url, payload) => new Promise((resolve, reject) => calls.push({ url, payload, resolve, reject })) };
  const runtimeVue = { ...vue, onMounted() {}, onUnmounted: vue.onScopeDispose };
  let autoScan;
  function evaluate(source) {
    const context = vm.createContext({ exports: {}, setTimeout(fn) { timers.set(++timerId, fn); return timerId; }, clearTimeout(id) { timers.delete(id); },
      localStorage: { getItem: () => null }, sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} },
      window: { location: { pathname: '/pda/stock-out' }, removeEventListener() {} },
      require(name) {
        if (name === 'vue') return runtimeVue;
        if (name.includes('useAutoVehicleScan')) return autoScan;
        if (name === 'vant') return { showFailToast: text => notices.push(text), showSuccessToast: text => notices.push(text) };
        if (name.endsWith('/api')) return { default: api };
        if (name.includes('permissions')) return { isSuperAdmin: () => true, hasPermission: () => true };
        return {};
      } });
    vm.runInContext(ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }), context);
    return context.exports;
  }
  autoScan = evaluate(readFileSync(new URL('../../bike-h5/src/composables/useAutoVehicleScan.ts', import.meta.url), 'utf8'));
  const source = readFileSync(new URL(`../../bike-h5/src/${component}`, import.meta.url), 'utf8');
  const compiled = compileScript(parse(source).descriptor, { id: 'auto-scan-test' });
  const props = vue.reactive({ username: 'tester', active: true });
  const state = scope.run(() => evaluate(compiled.content).default.setup(props, { expose() {} }));
  return { state, props, calls, notices, stop: () => scope.stop(), tick() { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); } };
}
async function orderHarness() {
  const h = harness('App.vue'), s = h.state;
  s.loggedIn.value = true; s.customerId.value = 1;
  await vue.nextTick();
  s.orders.value = [{ id: 2, customerId: 1, orderQty: 3, shippedQty: 0, status: 'CONFIRMED' }];
  s.orderId.value = 2;
  await vue.nextTick();
  return h;
}
async function settle() { await Promise.resolve(); await vue.nextTick(); }
function allow(call) { call.resolve({ data: { data: { valid: true, frameSn: 'FRAME20' } } }); }

test('逐字扫码只有完整总码才自动校验；回车与自动触发不会重复加入', async () => {
  const h = await orderHarness(), s = h.state;
  try {
    for (let i = 1; i < qr.length; i++) { s.vehicleQrCode.value = qr.slice(0, i); h.tick(); }
    assert.equal(h.calls.length, 0);
    s.vehicleQrCode.value = qr; h.tick();
    await s.scanStockOutVehicle();
    assert.equal(h.calls.length, 1);
    assert.equal(h.calls[0].url, '/api/stock-out/validate');
    allow(h.calls[0]); await settle();
    assert.equal(s.scannedVehicleQrs.value.length, 1);
    assert.equal(s.vehicleQrCode.value, '');
    s.vehicleQrCode.value = qr; h.tick();
    assert.equal(h.calls.length, 1);
    assert.equal(s.scannedVehicleQrs.value.length, 1);
  } finally { h.stop(); }
});

test('粘贴总码无需回车，校验期间不能执行真实出库', async () => {
  const h = await orderHarness(), s = h.state;
  try {
    s.vehicleQrCode.value = ` ${qr}\r\n`;
    await s.submitStockOut();
    assert.equal(h.calls.length, 0);
    h.tick(); await s.submitStockOut();
    assert.equal(h.calls.length, 1);
    allow(h.calls[0]); await settle();
    assert.equal(s.scannedVehicleQrs.value[0], qr);
    assert.ok(h.calls.every(call => call.url === '/api/stock-out/validate'));
  } finally { h.stop(); }
});

test('切换订单丢弃旧校验结果，离开页面取消待处理扫码', async () => {
  const h = await orderHarness(), s = h.state;
  try {
    s.vehicleQrCode.value = qr; h.tick();
    s.orderId.value = 3; await vue.nextTick();
    allow(h.calls[0]); await settle();
    assert.equal(s.scannedVehicleQrs.value.length, 0);
    s.vehicleQrCode.value = qr; h.stop(); h.tick();
    assert.equal(h.calls.length, 1);
  } finally { h.stop(); }
});

test('失败校验保留输入且不无限自动重试，可手动重试', async () => {
  const h = await orderHarness(), s = h.state;
  try {
    s.vehicleQrCode.value = qr; h.tick();
    h.calls[0].resolve({ data: { data: { valid: false, message: '仓库不匹配' } } }); await settle();
    h.tick();
    assert.equal(h.calls.length, 1);
    assert.equal(s.vehicleQrCode.value, qr);
    assert.equal(s.resultMessage.value, '仓库不匹配');
    void s.scanStockOutVehicle();
    assert.equal(h.calls.length, 2);
    allow(h.calls[1]); await settle();
    assert.equal(s.scannedVehicleQrs.value.length, 1);
  } finally { h.stop(); }
});

test('PI 整批模式自动加入且切换标签后丢弃旧响应', async () => {
  const h = harness('components/PiDispatchPanel.vue'), s = h.state;
  try {
    s.detail.value = { id: 9, customerId: 1, orders: [{ orderId: 2, remainingQty: 3 }] };
    s.orderId.value = 2; s.qr.value = qr; h.tick();
    assert.equal(h.calls.length, 1);
    allow(h.calls[0]); await settle();
    assert.equal(s.queue.value.length, 1);
    s.qr.value = qr.replace('20:', '21:'); h.tick();
    h.props.active = false;
    allow(h.calls[1]); await settle();
    assert.equal(s.queue.value.length, 1);
    assert.ok(h.calls.every(call => call.url === '/api/stock-out/validate'));
  } finally { h.stop(); }
});
