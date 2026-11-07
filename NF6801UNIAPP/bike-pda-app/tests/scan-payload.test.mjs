import test from "node:test";
import assert from "node:assert/strict";
import { isBarcodePayload } from "../common/scan-payload.js";

test("视频中的 ##55UP 及厂商控制回包不能成为车辆条码", () => {
  for (const value of ["##55UP", "\x1b##55UP\r\n", "##55DN", "##56UP",
    "55DN", "##55UP##55DN", "BIKE-01##55UP", "start", "BE", "P",
    "11", "MM", "no paper", "find bl end err", "t_pwr 0", "err 1",
    "BIKE\x00SN", "\ufffdz\ufffdM", "褄鳰", "", null]) assert.equal(isBarcodePayload(value), false, String(value));
});
test("真实车辆码、序列号、中文及二维码 URL 原样放行", () => {
  for (const value of ["BIKE-PDA-SELF-TEST", "BIKE-20261102-001",
    "4006381333931", "036000291452", "车辆-001", "https://bike.example/vehicle/55UP",
    "FRAME-START-11", "START123", "\r\nBIKE-01\r\n"])
    assert.equal(isBarcodePayload(value), true, value);
});
