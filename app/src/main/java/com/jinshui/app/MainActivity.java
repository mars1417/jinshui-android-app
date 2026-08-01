/**
 * 金水新苑社区 · Android WebView App（纯页面壳 v4）
 *
 * 架构变更（2026-08-01 Boss施拍板）：
 *   ❌ 移除整包自动更新（checkForUpdate/下载/覆盖安装）—— WebView壳页面更新不需要装APK
 *   ✅ 保留：强制清缓存 + URL时间戳 → 每次打开拉最新页面 = 零安装自动更新
 *   ✅ 保留：通知桥(NotificationBridge) / 多入口URL回退 / 文件选择器 / SSL放行
 *
 * 页面更新机制：改服务器(8601 Flask) → 用户下次打开APK即见新版，无需重新安装。
 */

package com.jinshui.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private int fallbackIndex = 0;
    private NotificationBridge notifBridge;
    private ValueCallback<Uri[]> uploadMessage;

    // 多入口URL，按优先级排列
    // 入口=网关登录页(统一登录→角色分流)，登录后进对应系统
    private static final String[] ENTRY_URLS = {
        "https://76ae250e.r23.cpolar.top/gateway/",   // 0: 邻音·社区服务网关（登录页，固定域名）
        "https://76ae250e.r23.cpolar.top/portal/"     // 1: portal首页（备用）
    };

    private static final String CHANNEL_ID = "jinshui_notifications";
    private static final int NOTIFICATION_PERMISSION_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        createNotificationChannel();
        requestNotificationPermission();

        webView = findViewById(R.id.webview);
        setupWebView();

        notifBridge = new NotificationBridge(this);
        webView.addJavascriptInterface(notifBridge, "AndroidNotif");

        // 页面壳：每次启动强制清缓存 + 时间戳拉最新页面（零安装自动更新）
        webView.clearCache(true);
        fallbackIndex = 0;
        loadCurrentUrl();
    }

    private void loadCurrentUrl() {
        if (fallbackIndex >= ENTRY_URLS.length) {
            Log.w("EntryUrl", "All entry URLs exhausted");
            return;
        }
        String url = ENTRY_URLS[fallbackIndex] + "?_t=" + System.currentTimeMillis();
        Log.d("EntryUrl", "Trying: " + url);
        webView.loadUrl(url);
    }

    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    int errCode = error.getErrorCode();
                    Log.w("EntryUrl", "Error loading " + request.getUrl() + " code=" + errCode);
                    // 主框架加载失败 → 自动切下一个入口URL
                    fallbackIndex++;
                    loadCurrentUrl();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectNotifBridgeJS(view);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle(title);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                }
                uploadMessage = filePathCallback;

                // ✅ 使用网页 accept 属性动态生成 Intent（支持图片/视频/音频等所有类型）
                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception e) {
                    // 兜底：通用文件选择器
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), 1001);
                } catch (Exception e) {
                    // 最后兜底：ACTION_GET_CONTENT 全类型
                    if (uploadMessage != null) {
                        uploadMessage.onReceiveValue(null);
                        uploadMessage = null;
                    }
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    fallback.setType("*/*");
                    fallback.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "audio/*"});
                    startActivityForResult(Intent.createChooser(fallback, "选择文件"), 1001);
                }
                return true;
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "金水新苑通知", NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("来自金水新苑社区的通知消息");
            channel.enableVibration(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_CODE
                );
            }
        }
    }

    private void injectNotifBridgeJS(WebView view) {
        String js =
            "(function(){" +
            "  if(window.__notifBridgeDone) return;" +
            "  window.__notifBridgeDone=true;" +
            "  var _lastId=null;" +
            "  setInterval(function(){" +
            "    try{" +
            "      fetch('/api/notifications/poll',{cache:'no-store'})" +
            "      .then(function(r){return r.json()})" +
            "      .then(function(d){" +
            "        if(d&&d.id&&d.id!=_lastId&&window.AndroidNotif){" +
            "          _lastId=d.id;" +
            "          AndroidNotif.showNotification(d.title||'',d.body||'',d.id);" +
            "        }" +
            "      }).catch(function(){});" +
            "    }catch(e){}" +
            "  },15000);" +
            "  setInterval(function(){" +
            "    try{" +
            "      fetch('/api/notifications/unread-count',{cache:'no-store'})" +
            "      .then(function(r){return r.json()})" +
            "      .then(function(d){" +
            "        if(d&&typeof d.count!=='undefined'&&window.AndroidNotif){" +
            "          if(d.count>0) AndroidNotif.updateBadge(d.count);" +
            "          else AndroidNotif.clearBadge();" +
            "        }" +
            "      }).catch(function(){});" +
            "    }catch(e){}" +
            "  },30000);" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && uploadMessage != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                Uri uri = data != null ? data.getData() : null;
                if (uri != null) results = new Uri[]{uri};
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
