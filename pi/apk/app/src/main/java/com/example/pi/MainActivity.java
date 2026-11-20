package com.example.pi;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private EditText portInput;
    private Button startStopBtn, openChatBtn;
    private TextView statusText, logText;
    private SharedPreferences prefs;
    private boolean running = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable logPoller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("pi_config", MODE_PRIVATE);

        portInput = findViewById(R.id.port_input);
        startStopBtn = findViewById(R.id.start_stop_btn);
        openChatBtn = findViewById(R.id.open_chat_btn);
        statusText = findViewById(R.id.status_text);
        logText = findViewById(R.id.log_text);
        logText.setMovementMethod(new ScrollingMovementMethod());

        portInput.setText(prefs.getString("port", "30141"));

        startStopBtn.setOnClickListener(v -> {
            if (running) {
                PiService.stopProcess();
                stopService(new Intent(this, PiService.class));
                startStopBtn.setText("启动");
                running = false;
                statusText.setText("状态: 已停止");
                openChatBtn.setEnabled(false);
                stopLogPoller();
            } else {
                String port = portInput.getText().toString().trim();
                prefs.edit().putString("port", port).apply();

                Intent intent = new Intent(this, PiService.class);
                intent.putExtra("port", port.isEmpty() ? "30141" : port);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }

                startStopBtn.setText("停止");
                running = true;
                statusText.setText("状态: 启动中...");
                startLogPoller();
            }
        });

        openChatBtn.setOnClickListener(v -> {
            String port = portInput.getText().toString().trim();
            Intent intent = new Intent(this, WebViewActivity.class);
            intent.putExtra("port", port.isEmpty() ? "30141" : port);
            startActivity(intent);
        });
    }

    private void startLogPoller() {
        logPoller = new Runnable() {
            @Override
            public void run() {
                String log = PiService.getLog();
                logText.setText(log);
                logText.scrollTo(0, 0);
                handler.postDelayed(this, 600);
            }
        };
        handler.postDelayed(logPoller, 500);

        // pi-web 首次启动要解压 runtime + 起 Next.js 服务，多等一会
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (PiService.isRunning()) {
                statusText.setText("状态: 运行中");
                openChatBtn.setEnabled(true);
            } else {
                statusText.setText("状态: 已停止（看日志排查）");
                startStopBtn.setText("启动");
                running = false;
                openChatBtn.setEnabled(false);
            }
        }, 10000);
    }

    private void stopLogPoller() {
        if (logPoller != null) {
            handler.removeCallbacks(logPoller);
            logPoller = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopLogPoller();
        super.onDestroy();
    }
}
