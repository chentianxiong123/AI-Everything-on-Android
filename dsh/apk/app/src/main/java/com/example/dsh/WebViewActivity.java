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
            // dsh web 启动时会输出多次 token（刷新），需等 token 稳定（连续 4 次 500ms 相同 ≈ 2s 不变）
            // 再加载，否则用第一个过期 token 会 401 白屏
            String token = null, prev = null;
            int stable = 0;
            for (int i = 0; i < 90; i++) {   // 最多等 45s
                String cur = PiService.getLastToken();
                if (cur != null) {
                    if (cur.equals(prev)) {
                        if (++stable >= 4) { token = cur; break; }
                    } else {
                        stable = 0;
                    }
                    prev = cur;
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
            if (token == null) token = PiService.getLastToken();   // 兜底（用当前最新）
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
