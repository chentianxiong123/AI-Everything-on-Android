package com.example.oneapi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.StringWriter;

public class OneApiService extends Service {
    private static boolean running = false;

    public static boolean isRunning() {
        File pidFile = new File("/data/data/com.example.oneapi/files/oneapi.pid");
        if (!pidFile.exists()) return false;
        try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
            String pid = r.readLine();
            if (pid == null || pid.isEmpty()) return false;
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "kill -0 " + pid + " 2>/dev/null && echo alive"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String result = br.readLine();
            p.waitFor();
            return "alive".equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    private static final String DATA_DIR = "/data/data/com.example.oneapi/files";

    private void diag(String msg) {
        android.util.Log.i("OneApiService", msg);
        try {
            File f = new File(DATA_DIR, "oneapi.log");
            f.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(f, true)) { w.write(msg + "\n"); }
        } catch (Throwable ignored) {}
    }

    // 永远返回非空，保证 UI 轮询不中断
    public static String getLog() {
        StringBuilder sb = new StringBuilder();
        File logFile = new File(DATA_DIR, "oneapi.log");
        File pidFile = new File(DATA_DIR, "oneapi.pid");
        sb.append(pidFile.exists() ? "[状态] 可能运行中" : "[状态] 未运行").append('\n');
        if (logFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(logFile))) {
                String line; int n = 0;
                while ((line = r.readLine()) != null) { sb.append(line).append('\n'); if (++n > 400) break; }
            } catch (Exception e) { sb.append("[读取失败] ").append(e).append('\n'); }
        } else {
            sb.append("[日志] 尚无日志文件。\n");
        }
        return sb.toString();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        diag("=== onCreate 进入 PID=" + android.os.Process.myPid() + " ===");
        // 前台服务是"尽力而为"：某些定制 ROM 即使声明了权限也可能拒绝。
        // 这里吞掉异常，保证 onStartCommand 仍能启动 Go 进程（大不了后台被系统回收）。
        try {
            String channelId = "oneapi_channel";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channelId, "OneAPI", NotificationManager.IMPORTANCE_LOW);
                getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }
            Notification notification = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("OneAPI")
                    .setContentText("运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, notification);
            }
        } catch (Throwable t) {
            diag("[警告] startForeground 失败: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 重复启动保护：进程已在跑时直接返回。否则每次打开应用都会再起一个
        // Go 进程抢同一端口，新进程启动失败还会覆盖 pid 文件，导致停止时杀错进程。
        if (isRunning()) {
            diag("服务已在运行，跳过重复启动");
            return START_NOT_STICKY;
        }
        // intent 为 null 表示系统按 START_STICKY 重启服务，此时用默认参数启动
        String port = "3000";
        String dns = "223.5.5.5,119.29.29.29,8.8.8.8";
        if (intent != null) {
            String p = intent.getStringExtra("port");
            String d = intent.getStringExtra("dns");
            if (p != null && !p.isEmpty()) port = p;
            if (d != null && !d.isEmpty()) dns = d;
        }
        final String fPort = port;
        final String fDns = dns;

        new Thread(() -> {
            try {
                startProcess(fPort, fDns);
            } catch (Throwable e) {
                diag("[致命] startProcess 异常: " + e);
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                diag(sw.toString());
                running = false;
                stopSelf();
            }
        }).start();
        return START_NOT_STICKY;
    }

    private void startProcess(String port, String dns) throws Exception {
        diag("startProcess 开始 port=" + port + " dns=" + dns);
        File workDir = getFilesDir();
        diag("二进制=" + new File(getApplicationInfo().nativeLibraryDir, "liboneapi.so").getAbsolutePath() + " exists=" + new File(getApplicationInfo().nativeLibraryDir, "liboneapi.so").exists());
        File pidFile = new File(workDir, "oneapi.pid");
        File logFile = new File(workDir, "oneapi.log");
        File bin = new File(getApplicationInfo().nativeLibraryDir, "liboneapi.so");

        File tokenDir = new File(workDir, "tiktoken");
        tokenDir.mkdirs();
        File tokenFile = new File(tokenDir, "cl100k_base.tiktoken");
        if (!tokenFile.exists()) {
            // tokenizer 非核心功能，APK 未内置时跳过，绝不阻断启动
            try (InputStream in = getAssets().open("cl100k_base.tiktoken");
                 FileOutputStream out = new FileOutputStream(tokenFile)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                diag("tiktoken 已复制");
            } catch (Exception e) {
                diag("[提示] APK 未内置 tiktoken，跳过（不影响核心功能）: " + e);
            }
        }

        File resolvFile = new File(workDir, "resolv.conf");
        try (FileWriter w = new FileWriter(resolvFile)) {
            for (String s : dns.split("[,，\\s]+")) {
                s = s.trim();
                if (!s.isEmpty()) {
                    w.write("nameserver " + s + "\n");
                }
            }
        }

        File hookLib = new File(getApplicationInfo().nativeLibraryDir, "libdns_hook.so");

        String cmd = "cd " + workDir.getAbsolutePath() + " && " +
                     "export TIKTOKEN_CACHE_DIR=" + tokenDir.getAbsolutePath() + " && " +
                     "export GODEBUG=netdns=go=1 && " +
                     "export CUSTOM_RESOLV_CONF=" + resolvFile.getAbsolutePath() + " && " +
                     "export LD_PRELOAD=" + hookLib.getAbsolutePath() + " && " +
                     "setsid " + bin.getAbsolutePath() + " --port " + port +
                     " >> " + logFile.getAbsolutePath() + " 2>&1 & echo $!";

        diag("执行命令: " + cmd);
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        String pid = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
        int ec = p.waitFor();
        diag("命令退出码=" + ec + " pid=" + pid);

        if (pid != null && !pid.isEmpty()) {
            try (FileWriter w = new FileWriter(pidFile)) {
                w.write(pid);
            }
            running = true;
            diag("启动成功 PID=" + pid);
        } else {
            diag("[致命] 未取得 PID，启动失败");
        }
    }

    public static void stopProcess() {
        File pidFile = new File("/data/data/com.example.oneapi/files/oneapi.pid");
        if (pidFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
                String pid = r.readLine();
                if (pid != null && !pid.isEmpty()) {
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", "kill " + pid + " 2>/dev/null"});
                }
            } catch (Exception e) {
                android.util.Log.e("OneApiService", "Error stopping: " + e.getMessage());
            }
            pidFile.delete();
        }
        running = false;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}