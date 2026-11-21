package com.example.pi;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 直接启动后台服务（默认配置，无 UI 配置页）
        ContextCompat.startForegroundService(this, new Intent(this, PiService.class));
        // 直接进 WebView（服务在后台起，WebView 加载本地端口）
        startActivity(new Intent(this, WebViewActivity.class));
        finish();
    }
}
