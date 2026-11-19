package com.deepseek.chatdemo;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String TAG = "DSChatAPK";
    private static final String SITE_URL = "https://cdn.deepseek.com/chat-demo/";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WRITE_STORAGE = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private TextView diagView;
    private final StringBuilder diagLog = new StringBuilder();

    // 导出用：把网页 blob 分片写到临时文件，最后落到 Downloads
    private OutputStream currentOut;
    private String currentName;
    private File currentTemp;

    // 诊断 + 兼容补丁：在页面 JS 之前注入。
    // (1) 屏幕浮层显示 JS 报错和 WebView 环境，白屏时可直接截图定位；
    // (2) 给缺少 ResizeObserver 的旧 WebView 兜底，chat-demo 主界面直接 new 它，无 fallback。
    private static final String DIAG_SHIM = """
            <script>
            (function(){
              window.__DS_LOG=[];
              var box=null;
              function panel(){
                if(box)return box;
                box=document.createElement('div');
                box.id='__ds_diag';
                box.style.cssText='position:fixed;left:0;top:0;z-index:2147483647;background:#000;color:#0f0;font:11px/1.35 monospace;padding:6px 8px;max-width:100vw;max-height:46vh;overflow:auto;white-space:pre-wrap;word-break:break-all;opacity:.94;border:1px solid #0f0;';
                (document.body||document.documentElement).appendChild(box);
                return box;
              }
              function log(tag,msg){
                try{
                  var line='['+tag+'] '+msg;
                  window.__DS_LOG.push(line);
                  panel().textContent+=line+'\\n';
                  if(window.DSReport)window.DSReport.report(line);
                }catch(e){}
              }
              log('INFO','DIAG_SHIM_LOADED');
              log('INFO','RO='+(typeof window.ResizeObserver)+' | '+navigator.userAgent);
              window.addEventListener('error',function(e){
                log('ERR',(e.message||'')+' @ '+(e.filename||'')+':'+(e.lineno||0)+':'+(e.colno||0)+(e.error&&e.error.stack?'\\n'+e.error.stack:''));
              },true);
              window.addEventListener('unhandledrejection',function(e){
                var r=e.reason;
                log('REJ',(r&&(r.stack||r.message))||String(r));
              });
              var oe=console.error;
              console.error=function(){
                try{log('CON',Array.prototype.map.call(arguments,function(a){return a&&a.stack?a.stack:String(a);}).join(' '));}catch(e){}
                return oe.apply(console,arguments);
              };
              if(!window.ResizeObserver){
                window.ResizeObserver=function(cb){
                  this._t=[];this._cb=cb;var self=this;
                  function poll(){
                    if(!self._t.length){self._id=0;return;}
                    var es=[];
                    for(var i=0;i<self._t.length;i++){
                      var o=self._t[i],el=o.el;
                      if(!el.isConnected){self._t.splice(i--,1);continue;}
                      var r=el.getBoundingClientRect();
                      if(r.width!==o.w||r.height!==o.h){o.w=r.width;o.h=r.height;
                        es.push({target:el,contentRect:{width:r.width,height:r.height,top:r.top,left:r.left,right:r.right,bottom:r.bottom}});}
                    }
                    if(es.length){try{self._cb(es,self);}catch(e){}}
                    self._id=setTimeout(poll,60);
                  }
                  this.observe=function(el){if(!el)return;var r=el.getBoundingClientRect();
                    this._t.push({el:el,w:r.width,h:r.height});if(!this._id)this._id=setTimeout(poll,60);};
                  this.unobserve=function(el){this._t=this._t.filter(function(o){return o.el!==el;});};
                  this.disconnect=function(){this._t=[];if(this._id){clearTimeout(this._id);this._id=0;}};
                };
                log('INFO','ResizeObserver polyfill installed');
              }
            })();
            </script>
            """;

    // 注入到网页：拦截 <a download href="blob:...">.click()，
    // 改为把 blob 按 256KB 分片经 JS 桥传回 App。网页代码本身不用改。
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

        // 原生诊断浮层：盖在 WebView 上方，显示 JS console 报错（不依赖网页注入）
        diagView = new TextView(this);
        diagView.setTextColor(Color.GREEN);
        diagView.setBackgroundColor(Color.argb(220, 0, 0, 0));
        diagView.setTextSize(11f);
        diagView.setPadding(10, 10, 10, 10);
        diagView.setGravity(Gravity.TOP | Gravity.START);
        FrameLayout container = new FrameLayout(this);
        container.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(diagView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
        setContentView(container);
        appendDiag("[SYS] diagnostic overlay ready");
        appendDiag("[SYS] SDK=" + Build.VERSION.SDK_INT + " " + Build.MANUFACTURER + " " + Build.MODEL);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        // 关键：localStorage + IndexedDB 都要开，否则网页设置和聊天记录存不住
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
        webView.addJavascriptInterface(new ReportBridge(), "DSReport");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                appendDiag("[NAV] start " + url);
            }

            // 只拦截主文档 HTML，把兼容补丁插到 <head> 里、页面 JS 之前执行。
            // 用 onPageFinished 注入太晚：React 首屏渲染时就已经崩了。
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              android.webkit.WebResourceRequest request) {
                try {
                    Uri uri = request.getUrl();
                    String path = uri.getPath();
                    if ("GET".equals(request.getMethod())
                            && "https".equalsIgnoreCase(uri.getScheme())
                            && uri.getHost() != null
                            && uri.getHost().endsWith("deepseek.com")
                            && path != null
                            && path.startsWith("/chat-demo")) {
                        HttpURLConnection conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(15000);
                        // 关键：禁用 gzip，否则拿到的是压缩字节，UTF-8 解码成乱码 → 页面直接白屏
                        conn.setRequestProperty("Accept-Encoding", "identity");
                        conn.setRequestProperty("User-Agent",
                                webView.getSettings().getUserAgentString());
                        int code = conn.getResponseCode();
                        if (code < 200 || code >= 300) {
                            if (conn.getErrorStream() != null) conn.getErrorStream().close();
                            return super.shouldInterceptRequest(view, request);
                        }
                        String html = new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
                        conn.disconnect();
                        //  sanity check：不是 HTML 就绝不返回，避免把页面搞得更糟
                        if (html.indexOf("<html") < 0 && html.indexOf("<HTML") < 0) {
                            Log.w(TAG, "intercepted body not html, fallback");
                            return super.shouldInterceptRequest(view, request);
                        }
                        // 塞到 <head> 之后，确保早于任何外部脚本
                        int idx = html.indexOf("<head>");
                        if (idx < 0) idx = html.indexOf("<HEAD>");
                        if (idx >= 0) {
                            int insert = idx + 6;
                            html = html.substring(0, insert) + DIAG_SHIM + html.substring(insert);
                        } else {
                            html = DIAG_SHIM + html;
                        }
                        appendDiag("[SHIM] HTML intercepted + shim injected (" + html.length() + " chars)");
                        return new WebResourceResponse("text/html", "UTF-8",
                                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "intercept failed, falling back to normal load", e);
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 主动探测页面真实状态，绕开 console 转发不可靠的问题
                view.evaluateJavascript(
                        "(function(){"
                                + "var out={};"
                                + "try{out.ro=typeof ResizeObserver;}catch(e){out.ro='ERR:'+e.message;}"
                                + "try{out.bodyLen=document.body?document.body.innerHTML.length:-1;}catch(e){out.bodyLen='ERR';}"
                                + "try{var r=document.getElementById('root');out.rootLen=r?r.innerHTML.length:-1;}catch(e){out.rootLen='ERR';}"
                                + "try{out.ready=document.readyState;}catch(e){out.ready='ERR';}"
                                + "try{out.log=window.__DS_LOG?window.__DS_LOG.slice(0,8):'none';}catch(e){out.log='ERR';}"
                                + "try{out.title=document.title;}catch(e){out.title='ERR';}"
                                + "window.DSReport.report('PROBE '+JSON.stringify(out));"
                                + "})();", null);
                view.evaluateJavascript(DOWNLOAD_HOOK, null);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                super.onReceivedError(view, req, err);
                if (req != null && err != null) {
                    appendDiag("[NETERR] " + req.getUrl() + " -> " + err.getDescription());
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // 把网页 console 输出显示到浮层，白屏时可直接截图看报错
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                appendDiag("[" + cm.messageLevel() + " L" + cm.lineNumber() + "] " + cm.message());
                return true;
            }

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
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
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

    private void toastOnUi(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private void appendDiag(String line) {
        try {
            diagLog.append(line).append('\n');
            if (diagLog.length() > 4000) diagLog.delete(0, diagLog.length() - 4000);
            final String text = diagLog.toString();
            runOnUiThread(() -> diagView.setText(text));
        } catch (Exception ignored) {}
    }

    public class ReportBridge {
        @JavascriptInterface
        public void report(String msg) {
            appendDiag("[JS] " + msg);
        }
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

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }
}
