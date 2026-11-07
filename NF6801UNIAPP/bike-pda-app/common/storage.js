import {
  DEFAULT_API_ORIGIN,
  DEFAULT_DEVICE,
  normalizeOrigin,
} from "../config.js";
export const origin = () =>
  uni.getStorageSync("bike.apiOrigin") || DEFAULT_API_ORIGIN;
export const session = () => uni.getStorageSync("bike.session") || null;
export const deviceConfig = () => {
  const saved = uni.getStorageSync("bike.device") || {};
  const config = Object.assign({}, DEFAULT_DEVICE, saved);
  if (Object.keys(saved).length && !saved.labelProfile) config.labelProfile = "TEMPLATE";
  // 用户已确认本机 80×100mm；兼容旧版只保存纸宽、误填790点的配置。
  // 已明确设置其他纸型/高度的配置不覆盖；终端编号、偏移和纸张模式保留。
  if ((!saved.labelProfile || saved.labelProfile === "TEMPLATE") &&
      [0, 80].includes(Number(saved.paperWidthMm || 0)) &&
      !Number(saved.paperHeightMm || 0) && Number(saved.dpi || 203) === 203) {
    Object.assign(config, { paperWidthMm: 80, paperHeightMm: 100,
      printableDots: 570, labelProfile: "608_80X100" });
  }
  return config;
};
export function setOrigin(value) {
  const next = normalizeOrigin(value);
  if (next !== origin()) clearSession();
  uni.setStorageSync("bike.apiOrigin", next);
}
export function clearSession() {
  uni.removeStorageSync("bike.session");
}
export function saveSession(value) {
  uni.setStorageSync("bike.session", value);
}
export function scopeKey(name) {
  const current = session();
  if (!current || !current.username) throw new Error("请先登录");
  return `bike.user:${encodeURIComponent(origin())}:${encodeURIComponent(current.username)}:${name}`;
}
export function readDraft(name, fallback = null) {
  return uni.getStorageSync(scopeKey(name)) || fallback;
}
export function saveDraft(name, value) {
  uni.setStorageSync(scopeKey(name), value);
}
export function removeDraft(name) {
  uni.removeStorageSync(scopeKey(name));
}
export function uuid() {
  return String(
    plus.android.importClass("java.util.UUID").randomUUID().toString(),
  );
}
