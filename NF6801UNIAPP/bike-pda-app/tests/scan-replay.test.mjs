import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { createDevice } from "../common/device.js";

function setup() {
  const subscribers = new Set();
  let trigger = () => {};
  let time = 10000;
  const originalNow = Date.now;
  const originalTimeout = globalThis.setTimeout;
  const originalPlus = globalThis.plus, originalUni = globalThis.uni;
  Date.now = () => time;
  globalThis.setTimeout = (fn, delay) => originalTimeout(fn, delay === 200 ? 0 : delay);
  globalThis.plus = { android: {
    runtimeMainActivity: () => ({ registerReceiver: receiver => subscribers.add(receiver), unregisterReceiver: receiver => subscribers.delete(receiver) }),
    importClass: value => typeof value === "string" ? class { addAction() {} } : value,
    implements: (name, value) => value,
  } };
  globalThis.uni = { getStorageSync: () => null, showToast() {}, requireNativePlugin: () => ({
    init() {}, hardwareStatus(cb) { cb({ ready: true, scanState: "COMMAND_SENT" }); },
    scan(value, cb) { trigger(); cb({ ready: true }); },
  }) };
  return {
    emit(code) { for (const receiver of subscribers) receiver.onReceive(null, { getStringExtra: name => name === "data" ? code : null }); },
    advance(delta = 10000) { time += delta; },
    trigger(fn) { trigger = fn; },
    subscribers,
    restore() { Date.now = originalNow; globalThis.setTimeout = originalTimeout; globalThis.plus = originalPlus; globalThis.uni = originalUni; },
  };
}
test("主动快速重扫同一码不被广播去重吞掉，重复广播仍只提交一次", async () => {
  const harness=setup(), delivered=[], device=createDevice();
  try {
    device.open(code => delivered.push(code)); harness.advance();
    harness.trigger(() => { harness.emit("BIKE-REPEAT"); harness.emit("BIKE-REPEAT"); });
    await device.scan(); harness.advance(500); await device.scan();
    assert.deepEqual(delivered,["BIKE-REPEAT","BIKE-REPEAT"]);
  } finally { device.close(); harness.restore(); }
});
test("关闭页面后的旧广播不进入重新打开的页面", () => {
  const harness=setup(), delivered=[], device=createDevice();
  try {
    device.open(code => delivered.push(code)); const stale=[...harness.subscribers][0];
    device.close(); device.open(code => delivered.push(code));
    stale.onReceive(null,{ getStringExtra: () => "OLD-CODE" });
    harness.emit("NEW-CODE"); assert.deepEqual(delivered,["NEW-CODE"]);
  } finally { device.close(); harness.restore(); }
});
test("实际发布AAR接收链路产生的广播进入真实page/device代码且不丢码", { skip: !process.env.BIKE_SIMULATION_CLASSPATH }, async () => {
  const result=spawnSync(process.env.BIKE_SIMULATION_JAVA,["-cp",process.env.BIKE_SIMULATION_CLASSPATH,"HardwareSimulation","scan"],{encoding:"utf8"});
  assert.equal(result.status,0,result.stderr);
  const encoded=result.stdout.match(/^SCAN_TRACE=(.+)$/m); assert.ok(encoded,"缺少实际原生模拟事件");
  const codes=Buffer.from(encoded[1],"base64").toString("utf8").split("\n");
  assert.ok(codes.length>=204); assert.ok(codes.includes("车辆-001"));
  const harness=setup(), delivered=[];
  const source=readFileSync(new URL("../common/page.js",import.meta.url),"utf8")
    .replace(/^import[\s\S]*?from ["'][^"']+["'];/gm,"")
    .replace("export const confirm","const confirm").replace("export default","const component =");
  const component=vm.runInNewContext(`(function(){${source};return component})()`,{
    ActionButton:{},FormField:{},ChoiceList:{},createDevice,
    session:()=>({userId:1}),refreshContext:async()=>({mustChangePassword:false}),canEnter:()=>true,
    pendingOperation:()=>null,uni:globalThis.uni,
  });
  const page={...component.data(),mode:"stock-in",async onScan(code) { delivered.push(code); }};
  for(const [name,fn] of Object.entries(component.methods)) page[name]=fn.bind(page);
  try {
    await component.onShow.call(page); harness.advance();
    for(const code of codes) {
      harness.trigger(()=>harness.emit(code));
      await page.triggerScan(); await Promise.resolve();
      assert.equal(page.failed,false,page.message);
    }
    assert.deepEqual(delivered,codes);
    component.onHide.call(page); harness.emit("AFTER-HIDE"); assert.deepEqual(delivered,codes);
  } finally { if(page._device) page._device.close(); harness.restore(); }
});
