package com.example.cpa;

import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import java.net.Socket;

public class WebViewActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        String port = getIntent().getStringExtra("port");
        if (port == null) port = "8317";
        final String url = "http://127.0.0.1:" + port + "/management.html";
        final int portNum = Integer.parseInt(port);

        WebView wv = findViewById(R.id.webview);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        wv.setWebViewClient(new WebViewClient());

        // 后台等待 CPA service 就绪（最多 ~15 秒），避免 WebView 在服务起来前
        // 加载导致 ERR_CONNECTION_REFUSED。就绪后回主线程 loadUrl。
        final WebView webView = wv;
        final AppCompatActivity activity = this;
        new Thread(() -> {
            boolean ready = false;
            for (int i = 0; i < 30; i++) { // 30 * 500ms = 15s
                try {
                    Socket sock = new Socket("127.0.0.1", portNum);
                    sock.close();
                    ready = true;
                    break;
                } catch (Exception e) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                }
            }
            final boolean isReady = ready;
            activity.runOnUiThread(() -> webView.loadUrl(url));
        }).start();
    }
}
