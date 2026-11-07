const fs = require("node:fs");
const path = require("node:path");
const root = path.resolve(__dirname, "..");
const read = (name) =>
  JSON.parse(fs.readFileSync(path.join(root, name), "utf8"));
const manifest = read("manifest.json");
const release = read("android-release.json");
const failures = [];
const offline = process.argv.includes('--offline');
function check(name, passed, missing) {
  console.log(
    `${passed ? "OK" : "MISSING"} ${name}${passed ? "" : ": " + missing}`,
  );
  if (!passed) failures.push(name);
}
check(
  "原生渲染",
  manifest["app-plus"].renderer === "native",
  "renderer 必须为 native",
);
for (const page of read("pages.json").pages) {
  check(
    page.path,
    fs.existsSync(path.join(root, page.path + ".nvue")),
    "必须存在 nvue 页面",
  );
}
check(
  "Android 包名",
  /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(release.applicationId),
  "配置 android-release.json",
);
check(
  "DCloud AppID",
  /^__UNI__[A-Za-z0-9]+$/.test(manifest.appid || "") &&
    manifest.appid !== "__UNI__9B0CDE4",
  "需要在自有 DCloud 账号创建 BIKE 应用；禁止使用厂商示例身份",
);
check(
  "签名文件",
  fs.existsSync(release.storeFile),
  "创建 BIKE 专用签名，不能替换既有签名",
);
check(
  "签名密码密文",
  fs.existsSync(release.protectedPasswordFile),
  "需要本机 DPAPI 加密密码，禁止写入仓库",
);
check("密钥别名", Boolean(release.keyAlias), "配置 keyAlias");
check(
  "Android 图标",
  Object.values(manifest["app-plus"].distribute.icons?.android || {}).length >=
    5 &&
    Object.values(manifest["app-plus"].distribute.icons.android).every((file) =>
      fs.existsSync(path.join(root, file)),
    ),
  "需要全部密度的 PNG 图标",
);
check(
  "Qs601Module",
  fs.existsSync(
    path.join(root, "nativePlugins/Qs601Module/android/n601-release.aar"),
  ),
  "缺少厂商 AAR",
);
const appKey = process.env[release.appKeyEnvironmentVariable];
check(
  "离线 AppKey",
  Boolean((appKey && appKey.trim()) || (release.protectedAppKeyFile && fs.existsSync(release.protectedAppKeyFile))),
  `在本地设置 ${release.appKeyEnvironmentVariable}；不打印内容。云打包则按 DCloud 云打包流程配置`,
);
if (offline) {
  const sdk = process.env[release.sdkDirectoryEnvironmentVariable];
  check('离线 SDK 目录', Boolean(sdk && fs.existsSync(sdk)), `配置 ${release.sdkDirectoryEnvironmentVariable}，SDK 版本必须与编译器 ${release.compilerVersion} 匹配`);
} else {
  check('HBuilderX CLI', Boolean(release.hbuilderxCli && fs.existsSync(release.hbuilderxCli)), '安装 HBuilderX 5.33');
  check('安心打包插件', Boolean(release.hbuilderxCli && fs.existsSync(path.join(path.dirname(release.hbuilderxCli), 'plugins/app-safe-pack'))), '安装官方 app-safe-pack 插件');
}
console.log(
  failures.length
    ? `发布准备未完成：${failures.length} 项缺失。本命令没有执行 APK 打包。`
    : "静态配置检查通过；仍需完成构建、APK 检查和真机运行。",
);
process.exitCode = failures.length ? 1 : 0;
