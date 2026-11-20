package com.deepseek.chatdemo;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "DSChatAPK";
    private static final String SITE_URL = "https://cdn.deepseek.com/chat-demo/";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WRITE_STORAGE = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    // 导出用：把网页 blob 分片写到临时文件，最后落到 Downloads
    private OutputStream currentOut;
    private String currentName;
    private File currentTemp;

    // 修复白屏：chat-demo 的 CSS 用了 dvh 视口单位（Chrome 108+ 才支持），
    // 旧华为 WebView 不认 → .frame{height:var(--app-viewport-height,100dvh)} 失效
    // → 主容器高度塌成 0 → 页面有内容但全白。这里用 vh 覆盖并强制容器高度。
    // 走 evaluateJavascript（onPageFinished），因为华为 WebView 上 HTML 拦截不可靠。
    private static final String CSS_FIX = """
            (function(){
              try{
                if(document.getElementById('__ds_cssfix')) return;
                var s=document.createElement('style');
                s.id='__ds_cssfix';
                s.textContent=':root{--app-viewport-height:100vh!important;--app-viewport-top:0px!important}'
                  +'html,body{height:100%!important;margin:0!important}'
                  +'#root{height:100vh!important;min-height:100vh!important}'
                  +'#root>div{height:100vh!important;min-height:100vh!important}'
                  +'[class*=frame-]{height:100vh!important}';
                document.head.appendChild(s);
                var fs=document.querySelectorAll('[class*=frame-]');
                for(var i=0;i<fs.length;i++){fs[i].style.setProperty('height','100vh','important');}
              }catch(e){}
            })();
            """;

    // 导出用：拦截 <a download href="blob:...">.click()，把 blob 按 256KB 分片
    // 经 JS 桥传回 App 写入 Downloads，避免大 ZIP 一次性传输失败。
    private static final String DOWNLOAD_HOOK = """
            (function(){
              if (window.__dsDownloadHooked) return;
              window.__dsDownloadHooked = true;
              var blobs = {};
              var origCreate = URL.createObjectURL;
              URL.createObjectURL = function(obj){
                var url = origCreate.apply(URL, arguments);
                try { blobs[url] = obj; } catch(e){}
                return url;
              };
              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function(){
                try {
                  var href = this.href || '';
                  var name = this.getAttribute('download');
                  if (href.indexOf('blob:') === 0 && name && blobs[href]) {
                    var blob = blobs[href];
                    var CHUNK = 256 * 1024;
                    var offset = 0;
                    function sendChunk(){
                      var end = Math.min(offset + CHUNK, blob.size);
                      var slice = blob.slice(offset, end);
                      var fr = new FileReader();
                      fr.onload = function(){
                        var dataUrl = String(fr.result);
                        var comma = dataUrl.indexOf(',');
                        var b64 = comma >= 0 ? dataUrl.substring(comma + 1) : '';
                        var last = end >= blob.size;
                        try { window.DSDownload.saveChunk(name, b64, last); } catch(e){}
                        offset = end;
                        if (!last) { sendChunk(); }
                        else { try { URL.revokeObjectURL(href); } catch(e){} delete blobs[href]; }
                      };
                      fr.onerror = function(){};
                      fr.readAsDataURL(slice);
                    }
                    sendChunk();
                    return;
                  }
                } catch(e){}
                return origClick.apply(this, arguments);
              };
            })();
            """;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        // localStorage（设置/API Key）+ IndexedDB（会话/图片）都要开，否则数据存不住
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        webView.addJavascriptInterface(new DownloadBridge(), "DSDownload");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 页面加载完成后注入 dvh 修复 + 导出拦截
                view.evaluateJavascript(CSS_FIX, null);
                view.evaluateJavascript(DOWNLOAD_HOOK, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent;
                try {
                    intent = params.createIntent();
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
        }

        webView.loadUrl(SITE_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) return;
            final ValueCallback<Uri[]> cb = filePathCallback;
            filePathCallback = null;
            if (resultCode != RESULT_OK || data == null) {
                cb.onReceiveValue(null);
                return;
            }
            // 收集用户选中的文件 URI
            List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n; i++) picked.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            if (picked.isEmpty()) {
                cb.onReceiveValue(null);
                return;
            }
            // 关键：把 content:// 临时 URI 复制成 App 缓存里的真实文件，
            // 再把 file:// 交给网页。华为 WebView 直接读 content:// 会拿到残缺内容，
            // 导致网页的 ZIP 格式校验失败。
            final List<Uri> finalPicked = picked;
            new Thread(() -> {
                List<Uri> out = new ArrayList<>();
                boolean ok = true;
                for (Uri u : finalPicked) {
                    File cached = copyToCache(u);
                    if (cached != null) out.add(Uri.fromFile(cached));
                    else { ok = false; break; }
                }
                final Uri[] result = ok ? out.toArray(new Uri[0]) : null;
                runOnUiThread(() -> {
                    if (result == null) toastOnUi("读取文件失败，请重试");
                    cb.onReceiveValue(result);
                });
            }).start();
        }
    }

    // 把 content:// URI 的内容复制到 App 缓存目录，保留原文件名
    private File copyToCache(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            String name = displayName(uri);
            if (name == null || name.isEmpty()) name = "import.zip";
            name = name.replace("/", "_").replace("\\", "_");
            File dst = new File(getCacheDir(), name);
            try (OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return dst.length() > 0 ? dst : null;
        } catch (Exception e) {
            Log.e(TAG, "copyToCache failed", e);
            return null;
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return name;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (currentOut != null) {
            try { currentOut.close(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private void toastOnUi(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    public class DownloadBridge {
        @JavascriptInterface
        public void saveChunk(String name, String base64, boolean last) {
            try {
                if (currentOut == null || !name.equals(currentName)) {
                    if (currentOut != null) { try { currentOut.close(); } catch (Exception ignored) {} }
                    currentName = name;
                    currentTemp = new File(getCacheDir(), "ds_export_tmp");
                    if (currentTemp.exists() && !currentTemp.delete()) {
                        toastOnUi("无法准备导出文件");
                        return;
                    }
                    currentOut = new FileOutputStream(currentTemp);
                }
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                currentOut.write(bytes);
                currentOut.flush();
                if (last) {
                    currentOut.close();
                    currentOut = null;
                    publishDownload(name, currentTemp);
                }
            } catch (Exception e) {
                Log.e(TAG, "saveChunk failed", e);
                toastOnUi("导出失败：" + e.getMessage());
            }
        }
    }

    private void publishDownload(String name, File src) {
        String shownPath;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("MediaStore 返回空");
                try (OutputStream out = getContentResolver().openOutputStream(uri);
                     InputStream in = new FileInputStream(src)) {
                    copy(in, out);
                }
                shownPath = "下载/" + name;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File dest = new File(dir, name);
                try (OutputStream out = new FileOutputStream(dest);
                     InputStream in = new FileInputStream(src)) {
                    copy(in, out);
                }
                shownPath = dest.getAbsolutePath();
            }
            toastOnUi("已导出到 " + shownPath);
        } catch (Exception e) {
            Log.e(TAG, "publishDownload failed", e);
            toastOnUi("导出失败：" + e.getMessage());
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
