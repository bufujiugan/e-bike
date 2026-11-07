import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
test("快速扫码结果不被触发按钮的 busy 锁丢弃，结果信息不被覆盖", async () => {
  const source = readFileSync(new URL("../common/page.js", import.meta.url), "utf8")
    .replace(/^import[\s\S]*?from ["'][^"']+["'];/gm, "")
    .replace("export const confirm", "const confirm").replace("export default", "const component =");
  const component = vm.runInNewContext(`(function(){${source};return component})()`, { ActionButton: {}, FormField: {}, ChoiceList: {} });
  const instance = { ready: true, busy: false, scanStarting: false, _showGeneration: 1, message: "" };
  instance._device = { async scan() { assert.equal(instance.busy, false); instance.message = "已收到真实扫码结果"; } };
  await component.methods.triggerScan.call(instance);
  assert.equal(instance.message, "已收到真实扫码结果");
  assert.equal(instance.scanStarting, false);
});
