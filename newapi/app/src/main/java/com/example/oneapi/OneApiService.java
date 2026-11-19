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
    private static String lastPort = "3000";
    private static String lastDns = "8.8.8.8,8.8.4.4";
    private static String lastEnvConf = "";

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

    // 诊断日志：服务进入的每一步都写，即使 Go 进程没起来也能看到原因
    private void diag(String msg) {
        android.util.Log.i("OneApiService", msg);
        try {
            File f = new File(DATA_DIR, "oneapi.log");
            f.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(msg + "\n");
            }
        } catch (Throwable ignored) {}
    }

    // 永远返回非空字符串，保证 MainActivity 的轮询不会中断
    public static String getLog() {
        StringBuilder sb = new StringBuilder();
        File logFile = new File(DATA_DIR, "oneapi.log");
        File pidFile = new File(DATA_DIR, "oneapi.pid");
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
        File stdoutFile = new File(DATA_DIR, "oneapi.stdout.log");
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
            String channelId = "oneapi_channel";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channelId, "OneAPI", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(ch);
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
            diag("[警告] startForeground 失败，继续无前台模式: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        diag("=== onStartCommand 进入 ===");
        if (intent == null) { diag("intent 为 null，返回"); return START_STICKY; }
        String port = intent.getStringExtra("port");
        String dns = intent.getStringExtra("dns");
        String envConf = intent.getStringExtra("extra_env_conf");
        if (port != null) lastPort = port;
        if (dns != null) lastDns = dns;
        if (envConf != null) lastEnvConf = envConf;

        new Thread(() -> {
            try {
                startProcess(lastPort, lastDns);
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

    private void startProcess(String port, String dns) throws Exception {
        diag("startProcess 开始 port=" + port + " dns=" + dns);
        File workDir = getFilesDir();
        diag("workDir=" + workDir.getAbsolutePath());
        File pidFile = new File(workDir, "oneapi.pid");
        File logFile = new File(workDir, "oneapi.log");
        File bin = new File(getApplicationInfo().nativeLibraryDir, "liboneapi.so");

        diag("二进制路径=" + bin.getAbsolutePath() + " exists=" + bin.exists() + " executable=" + bin.canExecute());
        if (!bin.exists()) {
            diag("[致命] 二进制不存在，启动中止");
            return;
        }

        File tokenDir = new File(workDir, "tiktoken");
        tokenDir.mkdirs();
        File tokenFile = new File(tokenDir, "cl100k_base.tiktoken");
        if (!tokenFile.exists()) {
            // tokenizer 只用于部分模型的 token 估算，非核心功能。
            // APK 未内置该文件时跳过即可，绝不能让它阻断启动。
            try (InputStream in = getAssets().open("cl100k_base.tiktoken");
                 FileOutputStream out = new FileOutputStream(tokenFile)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                diag("tiktoken 已复制到 " + tokenFile.getAbsolutePath());
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

        File envConfFile = new File(workDir, "env.conf");
        if (lastEnvConf != null && !lastEnvConf.isEmpty()) {
            try (FileWriter w = new FileWriter(envConfFile)) {
                w.write(lastEnvConf.trim() + "\n");
            }
        } else {
            envConfFile.delete();
        }

        File hookLib = new File(getApplicationInfo().nativeLibraryDir, "libdns_hook.so");

        ProcessBuilder pb = new ProcessBuilder(
            bin.getAbsolutePath(), "--port", port
        );
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("TIKTOKEN_CACHE_DIR", tokenDir.getAbsolutePath());
        pb.environment().put("GODEBUG", "netdns=go=1");
        pb.environment().put("DNS_SERVER", dns.split("[,，\\s]+")[0].trim());
        loadEnvConfig(pb, workDir);
        if (hookLib.exists()) {
            pb.environment().put("LD_PRELOAD", hookLib.getAbsolutePath());
        }

        diag("准备 pb.start()，env: GODEBUG=" + pb.environment().get("GODEBUG") + " LD_PRELOAD=" + pb.environment().get("LD_PRELOAD"));
        Process proc = pb.start();
        diag("pb.start() 成功");
        running = true;

        try {
            int pid = getPid(proc);
            try (FileWriter w = new FileWriter(pidFile)) {
                w.write(String.valueOf(pid));
            }
            android.util.Log.i("OneApiService", "Started PID: " + pid + " in process: " + android.os.Process.myPid());
        } catch (Exception e) {
            android.util.Log.w("OneApiService", "Could not save PID", e);
        }

        File stdoutFile = new File(workDir, "oneapi.stdout.log");
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
            File pidFile = new File("/data/data/com.example.oneapi/files/oneapi.pid");
            try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
                return Integer.parseInt(r.readLine());
            } catch (Exception ex) {
                return -1;
            }
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