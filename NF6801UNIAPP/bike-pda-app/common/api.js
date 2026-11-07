import { origin, session, clearSession } from "./storage.js";
export class ApiError extends Error {
  constructor(message, uncertain = false, code = "") {
    super(message);
    this.uncertain = uncertain;
    this.code = code;
  }
}
function identity() {
  return `${origin()}|${session()?.accessToken || ""}`;
}
function decode(response, snapshot) {
  if (snapshot !== identity())
    throw new ApiError("会话或服务器已变化，请重新核对操作结果", true);
  if (response.statusCode === 401) {
    clearSession();
    uni.reLaunch({ url: "/pages/login/login" });
    throw new ApiError("登录已失效，请重新登录", false, "UNAUTHORIZED");
  }
  let body = response.data;
  if (typeof body === "string") {
    try {
      body = JSON.parse(body);
    } catch (cause) {
      throw new ApiError("服务器返回格式异常，请核对操作结果", true);
    }
  }
  if (response.statusCode >= 500)
    throw new ApiError("服务器异常，请核对操作是否已完成", true);
  if (
    response.statusCode < 200 ||
    response.statusCode >= 300 ||
    !body ||
    body.code !== "SUCCESS"
  ) {
    throw new ApiError(
      body?.message || "请求失败",
      !body?.code,
      body?.code || "",
    );
  }
  return body.data;
}
export function request(path, method = "GET", body, anonymous = false) {
  if (!path.startsWith("/api/"))
    return Promise.reject(new Error("仅允许业务 API 请求"));
  const snapshot = identity(),
    current = session();
  return new Promise((resolve, reject) => {
    uni.request({
      url: origin() + path,
      method,
      data: body,
      timeout: 30000,
      header: {
        "Content-Type": "application/json",
        ...(current && !anonymous
          ? { Authorization: `Bearer ${current.accessToken}` }
          : {}),
      },
      success: (response) => {
        try {
          resolve(decode(response, snapshot));
        } catch (cause) {
          reject(cause);
        }
      },
      fail: () =>
        reject(
          new ApiError(
            "连接中断或超时；写入操作可能已完成，请先核对",
            method !== "GET",
          ),
        ),
    });
  });
}
export const get = (path, params = {}) => {
  const query = Object.keys(params)
    .filter((key) => params[key] !== undefined && params[key] !== "")
    .map(
      (key) => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`,
    )
    .join("&");
  return request(path + (query ? "?" + query : ""));
};
export const post = (path, body = {}) => request(path, "POST", body);
export function uploadImage(issueId, entry, progress) {
  const snapshot = identity(),
    current = session();
  return new Promise((resolve, reject) => {
    const task = uni.uploadFile({
      url: `${origin()}/api/production/issues/${encodeURIComponent(issueId)}/images`,
      filePath: entry.path,
      name: "file",
      formData: { requestId: entry.id },
      timeout: 180000,
      header: { Authorization: `Bearer ${current.accessToken}` },
      success: (response) => {
        try {
          resolve(decode(response, snapshot));
        } catch (cause) {
          reject(cause);
        }
      },
      fail: () =>
        reject(new ApiError("图片上传未确认，可以使用原请求重试", true)),
    });
    task.onProgressUpdate((event) => progress(Math.min(99, event.progress)));
  });
}
