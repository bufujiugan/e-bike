import test from "node:test";
import assert from "node:assert/strict";
import { deviceConfig } from "../common/storage.js";
test("按已确认的80×100纸型修复旧790点参数，但保留终端、偏移和原存储", () => {
  const saved = { terminal: "Pad01", paperWidthMm: 80, printableDots: 790, dpi: 203, offset: 52, blackMark: true };
  const before = structuredClone(saved);
  globalThis.uni = { getStorageSync: () => saved };
  assert.deepEqual(deviceConfig(), { ...saved, printableDots: 570, paperHeightMm: 100, labelProfile: "608_80X100" });
  assert.deepEqual(saved, before);
});
test("已明确设置其他纸型的配置不覆盖", () => {
  const saved = { terminal: "PDA02", paperWidthMm: 58, paperHeightMm: 40, printableDots: 384, dpi: 203, offset: 17, blackMark: false, labelProfile: "TEMPLATE" };
  globalThis.uni = { getStorageSync: () => saved };
  assert.deepEqual(deviceConfig(), saved);
});
test("旧58mm配置没有profile时继续使用原模板，不误用80mm版式", () => {
  globalThis.uni = { getStorageSync: () => ({ paperWidthMm: 58, printableDots: 384 }) };
  assert.equal(deviceConfig().labelProfile, "TEMPLATE");
  assert.equal(deviceConfig().paperWidthMm, 58);
});
