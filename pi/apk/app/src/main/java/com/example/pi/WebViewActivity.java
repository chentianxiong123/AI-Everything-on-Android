package com.example.pi;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import androidx.appcompat.app.AppCompatActivity;
import java.net.Socket;

public class WebViewActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        String port = getIntent().getStringExtra("port");
        if (port == null) port = "30141";
        final String url = "http://127.0.0.1:" + port + "/";
        final int portNum = Integer.parseInt(port);

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

        // bridge.js serves the chat UI at root.
        // 先等后端端口就绪（Node 启动需数秒），否则直接 loadUrl 会 ERR_CONNECTION_REFUSED。
        final WebView webView = wv;
        new Thread(() -> {
            for (int i = 0; i < 40; i++) { // 40 * 500ms = 20s（Node 启动比 Go 慢）
                try {
                    Socket sock = new Socket("127.0.0.1", portNum);
                    sock.close();
                    break;
                } catch (Exception e) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                }
            }
            runOnUiThread(() -> webView.loadUrl(url));
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
