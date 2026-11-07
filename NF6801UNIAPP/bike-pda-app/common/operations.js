import { post } from "./api.js";
import { scopeKey } from "./storage.js";
// 无幂等键的写入在发送前落盘；进程退出、网络超时均不自动重放。
export async function writeOnce(name, path, payload) {
  const key = scopeKey(`pending:${name}`),
    resultKey = scopeKey(`result:${name}`);
  if (uni.getStorageSync(key))
    throw new Error("存在未核对的操作，请先检查服务器结果");
  uni.setStorageSync(key, { path, payload, at: new Date().toISOString() });
  try {
    const result = await post(path, payload);
    uni.setStorageSync(resultKey, {
      result,
      path,
      payload,
      at: new Date().toISOString(),
    });
    uni.removeStorageSync(key);
    return result;
  } catch (cause) {
    if (!cause.uncertain) uni.removeStorageSync(key);
    throw cause;
  }
}
export function pendingOperation(name) {
  return uni.getStorageSync(scopeKey(`pending:${name}`)) || null;
}
export function clearPending(name) {
  uni.removeStorageSync(scopeKey(`pending:${name}`));
}
export function dispatchPayload(piId, queue) {
  const vehicles = queue
    .map(({ orderId, vehicleQrCode }) => ({ orderId, vehicleQrCode }))
    .sort(
      (a, b) =>
        a.orderId - b.orderId || a.vehicleQrCode.localeCompare(b.vehicleQrCode),
    );
  return { vehicles, fingerprint: JSON.stringify({ piId, vehicles }) };
}
export function assertQueue(queue, checked, code, orderId, remaining) {
  if (!checked.valid) throw new Error(checked.message || "车辆不可出库");
  if (!checked.frameSn) throw new Error("校验响应缺少车架号");
  if (
    queue.some(
      (row) => row.frameSn === checked.frameSn || row.vehicleQrCode === code,
    )
  )
    throw new Error("同一车辆不能重复加入");
  if (
    queue.length >= 200 ||
    queue.filter((row) => row.orderId === orderId).length >= remaining
  )
    throw new Error("超过订单剩余数量或单批 200 台上限");
}
