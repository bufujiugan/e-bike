// API 使用管理端反向代理，独立于将下线的 PDA 网站。
export const DEFAULT_API_ORIGIN = "http://e-bike.gpt20260801.top";
export const DEFAULT_DEVICE = {
  terminal: "",
  paperWidthMm: 80,
  paperHeightMm: 100,
  labelProfile: "608_80X100",
  printableDots: 570,
  dpi: 203,
  blackMark: true,
  offset: 52,
};
export function maxPrintableDots(paperWidthMm) {
  if (Number(paperWidthMm) <= 0) return 0;
  if (Number(paperWidthMm) <= 58) return 384;
  if (Number(paperWidthMm) <= 80) return 570;
  return 0;
}
export function normalizeOrigin(value) {
  const origin = String(value || "")
    .trim()
    .replace(/\/+$/, "");
  if (!/^https?:\/\/[a-zA-Z0-9.-]+(?::\d{1,5})?$/.test(origin))
    throw new Error("请输入 API 服务器地址，不带路径、账号或参数");
  return origin;
}
