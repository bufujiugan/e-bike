import { get, post, request } from "./api.js";
import { session, clearSession, saveSession } from "./storage.js";
const requirements = {
  assembly: [
    "ASSEMBLY_OPERATE",
    ["ASSEMBLY", "FRAME", "MOTOR", "BATTERY", "DISPLAY"],
  ],
  issues: [
    "ASSEMBLY_OPERATE",
    ["ASSEMBLY", "FRAME", "MOTOR", "BATTERY", "DISPLAY"],
  ],
  prints: [
    "ASSEMBLY_OPERATE",
    ["ASSEMBLY", "FRAME", "MOTOR", "BATTERY", "DISPLAY"],
  ],
  "stock-in": ["STOCK_IN_OPERATE", ["STOCK_IN"]],
  "stock-out": ["STOCK_OUT_OPERATE", ["STOCK_OUT"]],
  pi: ["STOCK_OUT_OPERATE", ["STOCK_OUT"]],
  rework: ["REWORK_OPERATE", ["REWORK"]],
};
export function canEnter(context, mode) {
  if (!context) return false;
  if ((context.roles || []).includes("SUPER_ADMIN")) return true;
  const permissions = context.permissions || [],
    stations = context.workstations || [];
  if (mode === "issues")
    return (
      ["ASSEMBLY_OPERATE", "REWORK_OPERATE", "*"].some((permission) =>
        permissions.includes(permission),
      ) && stations.length > 0
    );
  if (mode === "trace")
    return (
      (permissions.includes("TRACE_READ") || permissions.includes("*")) &&
      stations.length > 0
    );
  const requirement = requirements[mode];
  return Boolean(
    requirement &&
      (permissions.includes("*") || permissions.includes(requirement[0])) &&
      (stations.includes("*") ||
        requirement[1].some((station) => stations.includes(station))),
  );
}
export async function login(username, password) {
  clearSession();
  const result = await request(
    "/api/auth/login",
    "POST",
    { username: username.trim(), password },
    true,
  );
  if (!result?.accessToken) throw new Error("登录响应缺少凭据");
  saveSession({ username: username.trim(), accessToken: result.accessToken });
  try {
    return await refreshContext();
  } catch (cause) {
    clearSession();
    throw cause;
  }
}
export async function refreshContext() {
  if (!session()) throw new Error("请先登录");
  const context = await get("/api/auth/context");
  saveSession({ ...session(), username: context.username, context });
  return context;
}
export async function logout() {
  try {
    await post("/api/auth/logout");
  } finally {
    clearSession();
    uni.reLaunch({ url: "/pages/login/login" });
  }
}
