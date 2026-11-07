// 608 的控制回包与条码共用串口。只拦截厂商保留帧，不修改真实条码内容。
export function isBarcodePayload(value) {
  if (typeof value !== "string") return false;
  const text = value.trim();
  if (!text || /[\x00-\x1f\x7f\ufffd]/.test(text)) return false;
  if (text.includes("褄") || text.includes("鳰")) return false;
  if (text.includes("##55") || text.includes("##56") || text === "55DN")
    return false;
  return !/^(?:start|BE|P|11|MM|no paper|find bl end err|t_pwr(?: .*)?|err(?: .*)?)$/i.test(text);
}
