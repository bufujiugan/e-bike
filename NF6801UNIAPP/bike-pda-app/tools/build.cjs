const path = require("node:path");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const root = path.resolve(__dirname, "..");
const started = Date.now();
const result = spawnSync(
  process.execPath,
  [require.resolve("@vue/cli-service/bin/vue-cli-service.js"), "uni-build"],
  {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 20 * 1024 * 1024,
    env: {
      ...process.env,
      NODE_ENV: "production",
      UNI_PLATFORM: "app-plus",
      UNI_INPUT_DIR: root,
      UNI_OUTPUT_DIR: path.join(root, "dist", "build", "app-plus"),
      NODE_OPTIONS: "--openssl-legacy-provider",
    },
  },
);
process.stdout.write(result.stdout || "");
process.stderr.write(result.stderr || "");
// 编译器部分错误路径返回 0，因此还需核对本轮确实生成了原生页面。
const pages = JSON.parse(
  fs.readFileSync(path.join(root, "pages.json"), "utf8"),
).pages;
const missing = pages.filter((page) => {
  const target = path.join(
    root,
    "dist",
    "build",
    "app-plus",
    page.path + ".js",
  );
  return !fs.existsSync(target) || fs.statSync(target).mtimeMs < started;
});
if (missing.length)
  console.error(
    "本轮未生成的原生页面：" + missing.map((page) => page.path).join(", "),
  );
process.exit(
  result.status ||
    (result.error ||
    missing.length ||
    /(?:Error:|ERROR|Failed to compile)/.test(
      (result.stdout || "") + (result.stderr || ""),
    )
      ? 1
      : 0),
);
