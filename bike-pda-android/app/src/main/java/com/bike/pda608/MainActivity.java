package com.bike.pda608;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity implements Qs608Hardware.Listener {
    private static final String LOCAL_PATH = "/__bike_pda__/";
    private WebView web;
    private SharedPreferences config;
    private String origin = "", hardwareStatus = "尚未连接设备", lastCode = "";
    private long lastScan;
    private Qs608Hardware hardware;
    private boolean foreground, pageReady, receiverRegistered, settingsVisible;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private final BroadcastReceiver scanner = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!foreground || settingsVisible) return;
            String mode = config.getString("scanner", "hard");
            if (mode.equals("hard") && "qsapi.intent.key_scan_down".equals(intent.getAction())) startScan();
            if (mode.equals("soft") && "com.android.server.scannerservice.broadcast".equals(intent.getAction())) {
                Object value = intent.getExtras() == null ? null : intent.getExtras().get("scannerdata");
                if (value instanceof String) scan((String)value);
                else if (value instanceof byte[]) scan(QsProtocol.scanText((byte[])value));
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        config = getSharedPreferences("device", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(18,52,84));
        web = new WebView(this); setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUserAgentString(s.getUserAgentString() + " BikePda608/1.0.5");
        web.addJavascriptInterface(new DeviceBridge(), "BikePda");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!sameOrigin(uri) || !uri.getPath().startsWith(LOCAL_PATH)) return null;
                String name = uri.getPath().substring(LOCAL_PATH.length());
                if (name.isEmpty()) name = "index.html";
                if (name.contains("..") || name.contains("\\")) return missing();
                try {
                    String mime = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "application/javascript" : name.endsWith(".css") ? "text/css" : "application/octet-stream";
                    Map<String,String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    headers.put("Content-Security-Policy", "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; font-src 'self' data:; frame-src 'self' blob:; base-uri 'self'; form-action 'none'");
                    return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, getAssets().open("www/" + name));
                } catch (IOException error) { return missing(); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return !isAppPage(Uri.parse(url)); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return !isAppPage(request.getUrl()); }
            @Override public void onPageFinished(WebView view, String url) { pageReady = isAppPage(Uri.parse(url)); }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) { pageReady = false; toast("页面加载失败，请在设备设置中检查服务地址"); showSettings(); }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback; cameraUri = null;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                Intent chooser = Intent.createChooser(pick, "拍照或选择异常照片");
                Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (camera.resolveActivity(getPackageManager()) != null) {
                    new File(getCacheDir(), "capture.jpg").delete();
                    cameraUri = Uri.parse("content://com.bike.pda608.capture/photo.jpg");
                    camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                    camera.setClipData(ClipData.newRawUri("photo", cameraUri));
                    camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                }
                try { startActivityForResult(chooser, 608); }
                catch (ActivityNotFoundException error) { fileCallback.onReceiveValue(null); fileCallback = null; toast("设备未安装文件选择器"); }
                return true;
            }
        });
        String saved = config.getString("server", "");
        if (saved.isEmpty()) {
            web.loadDataWithBaseURL(null, "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='padding:24px;font-family:sans-serif;color:#123454'><h2>BIKE PDA 608</h2><p>请先配置服务器地址。</p><p>按设备返回键，选择“设备设置”即可继续。</p></body></html>", "text/html", "UTF-8", null);
            showSettings();
        } else loadApp(saved);
    }
    private WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
    private boolean sameOrigin(Uri uri) {
        if (origin.isEmpty()) return false;
        Uri expected = Uri.parse(origin);
        int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        int expectedPort = expected.getPort() < 0 ? ("https".equalsIgnoreCase(expected.getScheme()) ? 443 : 80) : expected.getPort();
        return expected.getScheme().equalsIgnoreCase(uri.getScheme()) && expected.getHost().equalsIgnoreCase(uri.getHost()) && port == expectedPort;
    }
    private boolean isAppPage(Uri uri) { return sameOrigin(uri) && (LOCAL_PATH.equals(uri.getPath()) || (LOCAL_PATH + "index.html").equals(uri.getPath())); }
    private void loadApp(String server) { pageReady = false; origin = server; web.loadUrl(origin + LOCAL_PATH + "index.html"); }

    @Override protected void onResume() {
        super.onResume(); foreground = true; web.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction("qsapi.intent.key_scan_down"); filter.addAction("com.android.server.scannerservice.broadcast");
        registerReceiver(scanner, filter); receiverRegistered = true;
        connectHardware();
    }
    private void connectHardware() {
        if (hardware != null) hardware.close(); hardware = null;
        if (foreground && !config.getString("scanner", "hard").equals("keyboard")) {
            hardware = new Qs608Hardware(this, this); hardware.open();
        }
    }
    @Override protected void onPause() {
        foreground = false; pageReady = false;
        if (receiverRegistered) { unregisterReceiver(scanner); receiverRegistered = false; }
        if (hardware != null) { hardware.close(); hardware = null; }
        web.onPause(); super.onPause();
    }
    @Override protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        web.removeJavascriptInterface("BikePda"); web.destroy(); super.onDestroy();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && web != null && web.getUrl() != null) pageReady = isAppPage(Uri.parse(web.getUrl()));
    }
    @Override public void onBackPressed() {
        new AlertDialog.Builder(this).setTitle("BIKE PDA 608").setItems(new String[]{"继续作业", "设备设置", "退出应用"}, (dialog, which) -> {
            if (which == 1) showSettings(); if (which == 2) finish();
        }).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 608 || fileCallback == null) return;
        Uri[] values = null;
        if (result == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                ClipData clips = data.getClipData(); values = new Uri[clips.getItemCount()];
                for (int i = 0; i < values.length; i++) values[i] = clips.getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) values = new Uri[]{data.getData()};
            else if (cameraUri != null && new File(getCacheDir(), "capture.jpg").length() > 0) values = new Uri[]{cameraUri};
        }
        fileCallback.onReceiveValue(values); fileCallback = null; cameraUri = null;
    }
    private void startScan() {
        android.util.Log.d("BikePda608", "Scan requested: foreground=" + foreground + ", settings=" + settingsVisible + ", ready=" + (hardware != null && hardware.isReady()));
        if (!foreground || settingsVisible) return;
        if (!config.getString("scanner", "hard").equals("hard")) { toast("当前模式请使用设备系统扫描键或扫码输入"); return; }
        if (hardware != null && hardware.isReady()) { hardware.scan(); toast("正在扫描，请将扫描头对准条码"); }
        else toast("扫描接口未就绪：" + hardwareStatus);
    }
    @Override public void scan(String code) {
        if (!foreground || settingsVisible || !pageReady || code == null) return;
        code = code.trim(); if (code.isEmpty() || code.length() > 4096) return;
        long now = SystemClock.elapsedRealtime(); if (code.equals(lastCode) && now - lastScan < 800) return;
        lastCode = code; lastScan = now;
        try {
            event("bike-device-scan", new JSONObject().put("code", code));
            ((Vibrator)getSystemService(VIBRATOR_SERVICE)).vibrate(45);
        } catch (Exception error) { toast("扫描结果传递失败，请重扫"); }
    }
    @Override public void status(String message) { hardwareStatus = message; }
    @Override public void printed(String id, String state, String message) {
        try { event("bike-device-print", new JSONObject().put("id", id).put("status", state).put("message", message)); }
        catch (Exception error) { toast("打印结果传递失败，请核对实物"); }
    }
    private void event(String name, JSONObject detail) {
        if (web != null && !isFinishing()) web.evaluateJavascript("window.dispatchEvent(new CustomEvent(" + JSONObject.quote(name) + ",{detail:" + detail.toString() + "}));", null);
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    public final class DeviceBridge {
        @JavascriptInterface public void scan() { runOnUiThread(MainActivity.this::startScan); }
        @JavascriptInterface public void settings() { runOnUiThread(MainActivity.this::showSettings); }
        @JavascriptInterface public String info() {
            return "{\"printWidth\":" + config.getInt("width", 384) + "}";
        }
        @JavascriptInterface public void printBitmap(String id, String png) {
            if (id == null || id.length() > 128 || png == null || png.length() > 4000000) return;
            runOnUiThread(() -> {
                if (!foreground || !pageReady || settingsVisible || hardware == null || !hardware.isReady()) { printed(id, "FAILED", "设备未就绪，未发送打印数据，请核对后回报失败"); return; }
                try {
                    byte[] bytes = Base64.decode(png, Base64.DEFAULT);
                    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    int width = config.getInt("width", 384);
                    if (bounds.outWidth < 1 || bounds.outWidth > width || bounds.outHeight < 1 || bounds.outHeight > 2400) throw new IllegalArgumentException();
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                    bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                    boolean label = config.getBoolean("label", false);
                    byte[] raster = QsProtocol.raster(bitmap.getWidth(), bitmap.getHeight(), pixels, width == 384 ? 200 : 0, label);
                    bitmap.recycle(); hardware.print(id, raster, label);
                } catch (Exception error) { printed(id, "FAILED", "标签图像无效，未送印，请刷新标签后核对任务"); }
            });
        }
    }

    private void showSettings() {
        if (settingsVisible || isFinishing()) return;
        if (hardware != null && hardware.isPrinting()) { toast("正在打印，请等待结果后再修改设置"); return; }
        settingsVisible = true;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(24, 12, 24, 12);
        TextView info = new TextView(this);
        info.setText("型号：" + Build.MODEL + " · Android " + Build.VERSION.RELEASE + "\n" + hardwareStatus + "\n服务地址填写现有系统的根地址（含端口），优先使用 HTTPS。\n" + web.getSettings().getUserAgentString()); box.addView(info);
        EditText address = new EditText(this); address.setSingleLine(true); address.setHint("https://服务器地址:端口"); address.setText(config.getString("server", "")); box.addView(address);
        Spinner mode = new Spinner(this); mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"硬解码：608串口扫码与打印", "软解码：系统广播接收", "键盘输入：不占用串口"}));
        String selected = config.getString("scanner", "hard"); mode.setSelection(selected.equals("soft") ? 1 : selected.equals("keyboard") ? 2 : 0); box.addView(mode);
        Spinner width = new Spinner(this); width.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"58mm纸 / 384点打印宽度", "80mm纸 / 570点打印宽度"})); width.setSelection(config.getInt("width", 384) == 570 ? 1 : 0); box.addView(width);
        CheckBox label = new CheckBox(this); label.setText("间隙/黑标标签纸（普通热敏纸不勾选）"); label.setChecked(config.getBoolean("label", false)); box.addView(label);
        TextView note = new TextView(this); note.setText("硬解码模式需关闭官方测试程序，避免串口争用。宽标签会缩小，使用前须确认条码可识读。修改服务地址将退出当前登录。"); box.addView(note);
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("608 设备设置 · 1.0.5").setView(scroll).setPositiveButton("保存并加载", null).setNegativeButton("取消", null).create();
        dialog.setOnDismissListener(d -> settingsVisible = false); dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String server = address.getText().toString().trim().replaceAll("/+$", "");
            Uri uri = Uri.parse(server);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())) { address.setError("请输入 http(s)://主机:端口，不含路径、账号或参数"); return; }
            boolean serverChanged = !server.equals(config.getString("server", ""));
            config.edit().putString("server", server).putString("scanner", new String[]{"hard", "soft", "keyboard"}[mode.getSelectedItemPosition()]).putInt("width", width.getSelectedItemPosition() == 0 ? 384 : 570).putBoolean("label", label.isChecked()).apply();
            if (serverChanged) { WebStorage.getInstance().deleteAllData(); CookieManager.getInstance().removeAllCookies(null); web.clearCache(true); }
            dialog.dismiss(); connectHardware(); loadApp(server);
        });
    }
}
