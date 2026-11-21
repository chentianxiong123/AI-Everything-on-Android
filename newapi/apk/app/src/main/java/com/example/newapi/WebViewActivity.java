package com.example.newapi;

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
        if (port == null) port = "3000";
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
        wv.setWebViewClient(new WebViewClient());

        // 后台等待后端就绪（最多 ~15 秒）后再加载，避免服务尚未起来就发请求
        // 导致 ERR_CONNECTION_REFUSED。
        final WebView webView = wv;
        new Thread(() -> {
            for (int i = 0; i < 30; i++) { // 30 * 500ms = 15s
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
}
