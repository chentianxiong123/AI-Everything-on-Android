package com.example.dsh;

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

        portInput.setText(prefs.getString("port", "3080"));

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
                intent.putExtra("port", port.isEmpty() ? "3080" : port);
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
            intent.putExtra("port", port.isEmpty() ? "3080" : port);
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
                // 轮询阶段状态（解压/启动/token 就绪），取代死板的 10s 检查
                // 首次解压 971M 可能 1-2 分钟，必须显示进度而不是误判"已停止"
                String st = PiService.getStatus();
                statusText.setText("状态: " + st);
                boolean ready = st.contains("token 已就绪");
                openChatBtn.setEnabled(ready);
                if (ready && !running) {
                    running = true;
                    startStopBtn.setText("停止");
                }
                handler.postDelayed(this, 600);
            }
        };
        handler.postDelayed(logPoller, 500);
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
