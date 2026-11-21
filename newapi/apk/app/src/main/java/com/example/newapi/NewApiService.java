package com.example.newapi;

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
import java.util.List;

public class NewApiService extends Service {
    private static boolean running = false;
    private static String lastPort = "3000";
    private static final String DEFAULT_DNS = "223.5.5.5,119.29.29.29,8.8.8.8";

    private static String lastDns = "223.5.5.5,119.29.29.29,8.8.8.8";
    private static String lastEnvConf = "";

    public static boolean isRunning() {
        File pidFile = new File("/data/data/com.example.newapi/files/newapi.pid");
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

    private static final String DATA_DIR = "/data/data/com.example.newapi/files";

    // 诊断日志：服务进入的每一步都写，即使 Go 进程没起来也能看到原因
    private void diag(String msg) {
        android.util.Log.i("NewApiService", msg);
        try {
            File f = new File(DATA_DIR, "newapi.log");
            f.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(msg + "\n");
            }
        } catch (Throwable ignored) {}
    }

    // 永远返回非空字符串，保证 MainActivity 的轮询不会中断
    public static String getLog() {
        StringBuilder sb = new StringBuilder();
        File logFile = new File(DATA_DIR, "newapi.log");
        File pidFile = new File(DATA_DIR, "newapi.pid");
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
        File stdoutFile = new File(DATA_DIR, "newapi.stdout.log");
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
            String channelId = "newapi_channel";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channelId, "NewAPI", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Notification notification = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("NewAPI")
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
        registerNetworkWatcher();
    }

    /**
     * 监听网络变化：WiFi ↔ 移动数据切换时，系统分配的 DNS 会变。
     * Go 纯解析器每次查询都重读 resolv.conf，所以只要重写该文件就能生效，
     * 无需重启后端进程。
     */
    private void registerNetworkWatcher() {
        try {
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    try {
                        File workDir = getFilesDir();
                        File resolv = new File(workDir, "resolv.conf");
                        File dnsConf = new File(workDir, "dns.conf");
                        List<String> list = SystemDns.resolveDnsList(context, dnsConf);
                        SystemDns.writeResolvConf(resolv, list);
                        diag("网络变化，resolv.conf 已刷新: " + list);
                    } catch (Throwable t) {
                        diag("[提示] 刷新 DNS 失败: " + t);
                    }
                }
            };
            registerReceiver(receiver, new android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));
        } catch (Throwable t) {
            diag("[提示] 注册网络监听失败: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        diag("=== onStartCommand 进入 ===");
        // 重复启动保护：进程已在跑时直接返回。否则每次打开应用都会再起一个
        // Go 进程抢同一端口，新进程启动失败还会覆盖 pid 文件，导致停止时杀错进程。
        if (isRunning()) {
            diag("服务已在运行，跳过重复启动");
            return START_STICKY;
        }
        // intent 为 null 表示系统按 START_STICKY 重启服务，此时沿用上次参数启动
        String port = null;
        String dns = null;
        String envConf = null;
        if (intent != null) {
            port = intent.getStringExtra("port");
            dns = intent.getStringExtra("dns");
            envConf = intent.getStringExtra("extra_env_conf");
        }
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
        File pidFile = new File(workDir, "newapi.pid");
        File logFile = new File(workDir, "newapi.log");
        File bin = new File(getApplicationInfo().nativeLibraryDir, "libnewapi.so");

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

        // DNS：优先用 files/dns.conf（用户显式指定），否则自动取系统 netd 分配的 DNS
        // 并探测 UDP/53 可达性，不可达则回退公共 DNS。
        // 注意：Go 二进制是 CGO_ENABLED=0 静态包，无法走 Android netd/cgo resolver，
        // 且 Android 上没有 /etc/resolv.conf，所以必须由 LD_PRELOAD hook 提供这份文件。
        File dnsConf = new File(workDir, "dns.conf");
        File resolvFile = new File(workDir, "resolv.conf");
        List<String> dnsList = SystemDns.resolveDnsList(this, dnsConf);
        SystemDns.writeResolvConf(resolvFile, dnsList);
        String dnsServers = String.join(",", dnsList);
        diag("DNS 服务器=" + dnsServers);

        File envConfFile = new File(workDir, "env.conf");
        // 只在 UI 显式传入时写入；绝不主动删除已有 env.conf，否则用户配的
        // 环境变量（API key 等）会在每次启动时丢失。
        if (lastEnvConf != null && !lastEnvConf.trim().isEmpty()) {
            try (FileWriter w = new FileWriter(envConfFile)) {
                w.write(lastEnvConf.trim() + "\n");
            }
        } else if (envConfFile.exists()) {
            diag("保留已有 env.conf (" + envConfFile.length() + " 字节)");
        }

        File hookLib = new File(getApplicationInfo().nativeLibraryDir, "libdns_hook.so");

        ProcessBuilder pb = new ProcessBuilder(
            bin.getAbsolutePath(), "--port", port,
            // 日志目录必须显式指定：上游默认 "./logs"，Android 上 cwd=/ 导致
            // mkdir /logs 失败（read-only file system）而启动退出。
            "-log-dir", new File(workDir, "logs").getAbsolutePath()
        );
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("HOME", workDir.getAbsolutePath());
        pb.environment().put("TIKTOKEN_CACHE_DIR", tokenDir.getAbsolutePath());
        pb.environment().put("GODEBUG", "netdns=go=1");
        pb.environment().put("DNS_SERVER", dnsServers.split("[,，\\s]+")[0].trim());
        // SQLite 数据库路径：上游默认 "one-api.db" 相对 cwd，Android 上会落到只读根目录
        pb.environment().put("SQLITE_PATH", new File(workDir, "one-api.db").getAbsolutePath());
        // LD_PRELOAD hook 靠这个变量把 /etc/resolv.conf 重定向到 app 私有文件
        pb.environment().put("CUSTOM_RESOLV_CONF", resolvFile.getAbsolutePath());
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
            android.util.Log.i("NewApiService", "Started PID: " + pid + " in process: " + android.os.Process.myPid());
        } catch (Exception e) {
            android.util.Log.w("NewApiService", "Could not save PID", e);
        }

        File stdoutFile = new File(workDir, "newapi.stdout.log");
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
            File pidFile = new File("/data/data/com.example.newapi/files/newapi.pid");
            try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
                return Integer.parseInt(r.readLine());
            } catch (Exception ex) {
                return -1;
            }
        }
    }

    public static void stopProcess() {
        File pidFile = new File("/data/data/com.example.newapi/files/newapi.pid");
        if (pidFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
                String pid = r.readLine();
                if (pid != null && !pid.isEmpty()) {
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", "kill " + pid + " 2>/dev/null"});
                }
            } catch (Exception e) {
                android.util.Log.e("NewApiService", "Error stopping: " + e.getMessage());
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