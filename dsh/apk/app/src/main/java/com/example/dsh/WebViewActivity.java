package com.example.dsh;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import androidx.appcompat.app.AppCompatActivity;

public class WebViewActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        String port = getIntent().getStringExtra("port");
        if (port == null) port = "3080";

        WebView wv = findViewById(R.id.webview);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(100);
        wv.setWebViewClient(new WebViewClient());
        wv.setWebChromeClient(new WebChromeClient());
        // dsh web 需要 token 认证：先等 PiService 从 stdout 提取到 token（dsh 启动后异步输出），
        // 再加载带 token 的 URL（WebView 会自动处理 cookie 和重定向）
        final String portF = port;
        new Thread(() -> {
            String token = null;
            for (int i = 0; i < 60; i++) {   // 最多等 30s
                token = PiService.getLastToken();
                if (token != null) break;
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
            final String url = "http://127.0.0.1:" + portF + "/"
                + (token != null ? "?token=" + token : "");
            runOnUiThread(() -> wv.loadUrl(url));
        }).start();
    }

    @Override
    public void onBackPressed() {
        WebView wv = findViewById(R.id.webview);
        if (wv != null && wv.canGoBack()) {
            wv.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
