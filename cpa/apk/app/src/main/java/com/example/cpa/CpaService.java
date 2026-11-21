package com.example.cpa;

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

public class CpaService extends Service {
    private static boolean running = false;
    private static String lastPort = "8317";
    private static String lastEnvConf = "";

    // CPA 默认配置：首次启动时写入工作目录，用户可在"配置"框里覆盖
    private static final String DEFAULT_CONFIG =
        "# CLIProxyAPI config\n" +
        "server:\n" +
        "  host: \"0.0.0.0\"\n" +
        "  port: 8317\n" +
        "  api-keys:\n" +
        "    - \"123456\"\n";

    public static boolean isRunning() {
        File pidFile = new File("/data/data/com.example.cpa/files/cpa.pid");
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

    private static final String DATA_DIR = "/data/data/com.example.cpa/files";

    // 诊断日志：服务进入的每一步都写，即使 Go 进程没起来也能看到原因
    private void diag(String msg) {
        android.util.Log.i("CpaService", msg);
        try {
            File f = new File(DATA_DIR, "cpa.log");
            f.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(msg + "\n");
            }
        } catch (Throwable ignored) {}
    }

    // 永远返回非空字符串，保证 MainActivity 的轮询不会中断
    public static String getLog() {
        StringBuilder sb = new StringBuilder();
        File logFile = new File(DATA_DIR, "cpa.log");
        File pidFile = new File(DATA_DIR, "cpa.pid");
        sb.append(isRunningStatic(pidFile) ? "[状态] 运行中" : "[状态] 未运行").append('\n');
        if (logFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(logFile))) {
                String line;
                int n = 0;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (++n > 400) { sb.append("...(截断)\n"); break; }
                }
            } catch (Exception e) {
                sb.append("[读取日志失败] ").append(e).append('\n');
            }
        }
        File stdoutFile = new File(DATA_DIR, "cpa.stdout.log");
        if (stdoutFile.exists()) {
            sb.append("--- Go 进程输出 ---\n");
            try (BufferedReader r = new BufferedReader(new FileReader(stdoutFile))) {
                String line;
                int n = 0;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (++n > 300) { sb.append("...(截断)\n"); break; }
                }
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    private static boolean isRunningStatic(File pidFile) {
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

    @Override
    public void onCreate() {
        super.onCreate();
        diag("=== onCreate 进入，PID=" + android.os.Process.myPid() + " ===");
        // 前台服务是"尽力而为"：某些定制 ROM 即使声明了权限也可能拒绝。
        // 这里吞掉异常，保证 onStartCommand 仍能启动 Go 进程（大不了后台被系统回收）。
        try {
            String channelId = "cpa_channel";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channelId, "CPA", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Notification notification = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("CPA")
                    .setContentText("运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, notification);
            }
        } catch (Throwable t) {
            diag("[警告] startForeground 失败，继续无前台模式: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        diag("=== onStartCommand 进入 ===");
        if (intent == null) { diag("intent 为 null，返回"); return START_STICKY; }
        String port = intent.getStringExtra("port");
        String envConf = intent.getStringExtra("extra_env_conf");
        if (port != null) lastPort = port;
        if (envConf != null) lastEnvConf = envConf;

        new Thread(() -> {
            try {
                startProcess(lastPort);
            } catch (Throwable e) {
                diag("[致命] startProcess 异常: " + e);
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                diag(sw.toString());
                running = false;
            }
        }).start();
        return START_STICKY;
    }

    private void startProcess(String port) throws Exception {
        diag("startProcess 开始 port=" + port);
        File workDir = getFilesDir();
        diag("workDir=" + workDir.getAbsolutePath());
        File pidFile = new File(workDir, "cpa.pid");
        File logFile = new File(workDir, "cpa.log");
        File bin = new File(getApplicationInfo().nativeLibraryDir, "libcpa.so");

        diag("二进制路径=" + bin.getAbsolutePath() + " exists=" + bin.exists() + " executable=" + bin.canExecute());
        if (!bin.exists()) {
            diag("[致命] 二进制不存在，启动中止");
            return;
        }

        // CPA 配置文件：优先用 UI 传入的，否则首次写入默认配置
        File configFile = new File(workDir, "config.yaml");
        String cfg = (lastEnvConf != null && !lastEnvConf.trim().isEmpty())
                ? lastEnvConf.trim() : DEFAULT_CONFIG;
        try (FileWriter w = new FileWriter(configFile)) {
            w.write(cfg + "\n");
        }
        diag("配置已写入 " + configFile.getAbsolutePath() + " (" + cfg.length() + " 字节)");

        // 内置管理前端：把 APK assets 里的 management.html 复制到 static/，
        // 通过 MANAGEMENT_STATIC_PATH 让 CPA 离线也能提供完整 Web 面板（不依赖联网下载）。
        File staticDir = new File(workDir, "static");
        staticDir.mkdirs();
        File panelFile = new File(staticDir, "management.html");
        try (InputStream pin = getAssets().open("management.html");
             FileOutputStream pout = new FileOutputStream(panelFile)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = pin.read(buf)) != -1) pout.write(buf, 0, n);
            diag("管理面板已释放到 " + panelFile.getAbsolutePath() + " (" + panelFile.length() + " 字节)");
        } catch (Exception e) {
            diag("[警告] 释放内置管理面板失败: " + e);
        }

        // NDK/Bionic 编译版：系统 DNS 由 Android netd 处理，无需 LD_PRELOAD hook
        ProcessBuilder pb = new ProcessBuilder(
            bin.getAbsolutePath(), "-config", configFile.getAbsolutePath()
        );
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("HOME", workDir.getAbsolutePath());
        pb.environment().put("MANAGEMENT_STATIC_PATH", staticDir.getAbsolutePath());
        // CPA v8+ 的 management 路由是懒注册的：只有设了 MANAGEMENT_PASSWORD（或 config secret-key）
        // 才会 registerManagementRoutes()，否则 /v8/management/* 全部 404。默认管理密码 123456。
        pb.environment().put("MANAGEMENT_PASSWORD", "123456");
        loadEnvConfig(pb, workDir);

        diag("准备 pb.start(): " + bin.getAbsolutePath() + " -config " + configFile.getAbsolutePath());
        Process proc = pb.start();
        diag("pb.start() 成功");
        running = true;

        try {
            int pid = getPid(proc);
            try (FileWriter w = new FileWriter(pidFile)) {
                w.write(String.valueOf(pid));
            }
            android.util.Log.i("CpaService", "Started PID: " + pid + " in process: " + android.os.Process.myPid());
        } catch (Exception e) {
            android.util.Log.w("CpaService", "Could not save PID", e);
        }

        File stdoutFile = new File(workDir, "cpa.stdout.log");
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                 FileWriter outWriter = new FileWriter(stdoutFile, true)) {
                String line;
                while ((line = r.readLine()) != null) {
                    outWriter.write(line + "\n");
                    outWriter.flush();
                }
            } catch (Exception e) {
                diag("[日志读取错误] " + e);
            }
        }).start();

        int exitCode = proc.waitFor();
        diag("[进程退出] exitCode=" + exitCode);
        running = false;
    }

    private static int getPid(Process p) {
        try {
            return (int) p.getClass().getMethod("pid").invoke(p);
        } catch (Exception e) {
            File pidFile = new File("/data/data/com.example.cpa/files/cpa.pid");
            try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
                return Integer.parseInt(r.readLine());
            } catch (Exception ex) {
                return -1;
            }
        }
    }

    public static void stopProcess() {
        File pidFile = new File("/data/data/com.example.cpa/files/cpa.pid");
        if (pidFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
                String pid = r.readLine();
                if (pid != null && !pid.isEmpty()) {
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", "kill " + pid + " 2>/dev/null"});
                }
            } catch (Exception e) {
                android.util.Log.e("CpaService", "Error stopping: " + e.getMessage());
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

    private static void loadEnvConfig(ProcessBuilder pb, File workDir) {
        File conf = new File(workDir, "env.conf");
        if (!conf.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(conf))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (!key.isEmpty()) {
                    pb.environment().put(key, val);
                }
            }
        } catch (Exception ignored) {}
    }
}