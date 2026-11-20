package com.deepseek.chatdemo;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "DSChatAPK";
    private static final String SITE_URL = "https://cdn.deepseek.com/chat-demo/";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WRITE_STORAGE = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private TextView diagView;
    private final StringBuilder diagLog = new StringBuilder();

    private OutputStream currentOut;
    private String currentName;
    private File currentTemp;

    // 修复白屏：旧 WebView 不支持 dvh，用 vh 覆盖
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

    // 文件注入 + 诊断：把 file:// 来源的.zip 换成内存 File，绕开华为 WebView
    // 对 file:// Blob 的 slice()/stream()/text() 兼容性问题（导致 ZIP 校验失败）。
    private static final String FILE_MONITOR = """
            (function(){
              if(window.__dsFileMon)return;
              window.__dsFileMon=true;
              function rep(s){if(window.DSReport)window.DSReport.report(s);}
              // 抓网页吞掉的真实错误码：BackupError 构造时执行 this.key=e，
              // 在 Error.prototype 上装 key 的 setter 即可截获。
              try{
                Object.defineProperty(Error.prototype,'key',{
                  configurable:true,
                  set:function(v){
                    rep('BACKUP_ERR '+v);
                    Object.defineProperty(this,'key',{value:v,writable:true,configurable:true,enumerable:true});
                  },
                  get:function(){return this.__k;}
                });
              }catch(err){rep('ERRHOOK FAIL '+err.message);}
              var rebuilt=new WeakSet();
              document.addEventListener('change',function(e){
                try{
                  var el=e.target;
                  if(!el||el.tagName!=='INPUT'||el.type!=='file')return;
                  var f=el.files&&el.files[0];
                  if(!f){rep('INPUT_FILE none');return;}
                  rep('INPUT_FILE name='+f.name+' size='+f.size+' type='+f.type);
                  // 只处理 zip 导入，且避免重复重建
                  var isZip=/\\.zip$/i.test(f.name)||f.type==='application/zip';
                  if(!isZip||rebuilt.has(f))return;
                  rebuilt.add(f);
                  e.stopPropagation();
                  e.preventDefault();
                  rep('REBUILD start');
                  var fr=new FileReader();
                  fr.onload=function(){
                    try{
                      var bytes=new Uint8Array(fr.result);
                      var nf=new File([bytes],f.name,{type:f.type||'application/zip'});
                      // 关键：华为 WebView 对 file:// File 的大偏移 slice() 有 bug，
                      // zip 库读文件尾部目录区时会拿到错误字节。用 JS 数组切片覆盖
                      // 实例 slice()，任何偏移都绝对正确。
                      try{
                        nf.slice=function(s,e,ct){
                          var a=s||0, b=(e===undefined||e<0)?bytes.length:Math.min(e,bytes.length);
                          if(a<0)a=bytes.length+a;
                          var part=bytes.subarray(a,b);
                          return new Blob([part],{type:ct||''});
                        };
                      }catch(err){rep('SLICE_PATCH ERR '+err.message);}
                      rebuilt.add(nf);
                      Object.defineProperty(el,'files',{value:[nf],configurable:true});
                      var head=Array.from(bytes.slice(0,4));
                      // 验证尾部大偏移 slice 的长度（zip 读 EOCD 用）
                      var tail=nf.slice(bytes.length-65557,bytes.length);
                      rep('REBUILD done size='+nf.size+' head='+head.join(',')+' tailLen='+tail.size);
                      el.dispatchEvent(new Event('change',{bubbles:true}));
                    }catch(err){rep('REBUILD ERR '+err.message);}
                  };
                  fr.onerror=function(){rep('REBUILD read ERR');};
                  fr.readAsArrayBuffer(f);
                }catch(err){rep('INPUT_ERR '+err.message);}
              },true);
              rep('FILE_MONITOR installed');
            })();
            """;

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

        // 诊断浮层
        diagView = new TextView(this);
        diagView.setTextColor(Color.GREEN);
        diagView.setBackgroundColor(Color.argb(225, 0, 0, 0));
        diagView.setTextSize(10f);
        diagView.setPadding(8, 8, 8, 8);
        diagView.setGravity(Gravity.TOP | Gravity.START);
        // 固定高度 + 可滚动，日志不会盖满整屏；点一下清空，方便导入前复位
        int diagH = (int) (getResources().getDisplayMetrics().heightPixels * 0.42);
        diagView.setHeight(diagH);
        diagView.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        diagView.setOnClickListener(v -> {
            diagLog.setLength(0);
            diagView.setText("");
        });
        FrameLayout container = new FrameLayout(this);
        container.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(diagView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, diagH, Gravity.TOP | Gravity.START));
        setContentView(container);
        appendDiag("[SYS] ready SDK=" + Build.VERSION.SDK_INT + " " + Build.MANUFACTURER + " " + Build.MODEL);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);   // 诊断：看 file:// 是否可被网页读到
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        webView.addJavascriptInterface(new DownloadBridge(), "DSDownload");
        webView.addJavascriptInterface(new ReportBridge(), "DSReport");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(CSS_FIX, null);
                view.evaluateJavascript(DOWNLOAD_HOOK, null);
                view.evaluateJavascript(FILE_MONITOR, null);
                appendDiag("[NAV] finished " + url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                appendDiag("[CON " + cm.messageLevel() + "] " + cm.message());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                appendDiag("[PICK] onShowFileChooser accept=" + Arrays.toString(params.getAcceptTypes())
                        + " mode=" + params.getMode());
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent;
                try {
                    intent = params.createIntent();
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    appendDiag("[PICK] no activity: " + e.getMessage());
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
            appendDiag("[PICK] result code=" + resultCode + " data=" + (data != null));
            if (resultCode != RESULT_OK || data == null) {
                cb.onReceiveValue(null);
                return;
            }
            List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n; i++) picked.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            appendDiag("[PICK] count=" + picked.size());
            if (picked.isEmpty()) {
                cb.onReceiveValue(null);
                return;
            }
            final List<Uri> finalPicked = picked;
            new Thread(() -> {
                List<Uri> out = new ArrayList<>();
                boolean ok = true;
                for (Uri u : finalPicked) {
                    appendDiag("[COPY] uri=" + u.getScheme() + " " + u.toString().substring(0, Math.min(120, u.toString().length())));
                    File cached = copyToCache(u);
                    if (cached != null) {
                        appendDiag("[COPY] -> " + cached.getAbsolutePath() + " size=" + cached.length());
                        out.add(Uri.fromFile(cached));
                    } else { ok = false; appendDiag("[COPY] FAILED"); break; }
                }
                final Uri[] result = ok ? out.toArray(new Uri[0]) : null;
                runOnUiThread(() -> {
                    if (result == null) toastOnUi("读取文件失败，请重试");
                    else appendDiag("[PICK] callback " + result.length + " uri(s): " + result[0]);
                    cb.onReceiveValue(result);
                });
            }).start();
        }
    }

    private File copyToCache(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) { appendDiag("[COPY] openInputStream null"); return null; }
            // 文件名固定成纯 ASCII，避开中文在 file:// URI 里被 URL 编码
            // （华为 WebView 转给网页时编码串会导致读取失败）
            File dst = new File(getCacheDir(), "import.zip");
            try (OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return dst.length() > 0 ? dst : null;
        } catch (Exception e) {
            Log.e(TAG, "copyToCache failed", e);
            appendDiag("[COPY] ERR " + e.getMessage());
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

    public class ReportBridge {
        @JavascriptInterface
        public void report(String msg) {
            appendDiag("[JS] " + msg);
        }
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

    private void appendDiag(String line) {
        try {
            diagLog.append(line).append('\n');
            if (diagLog.length() > 5000) diagLog.delete(0, diagLog.length() - 5000);
            final String text = diagLog.toString();
            runOnUiThread(() -> {
                diagView.setText(text);
                // 自动滚到底部，显示最新日志
                int h = diagView.getLayout().getLineTop(diagView.getLineCount());
                diagView.scrollTo(0, Math.max(0, h - diagView.getHeight()));
            });
        } catch (Exception ignored) {}
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
