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
import java.util.List;

public class OneApiService extends Service {
    private static final String DEFAULT_DNS = "223.5.5.5,119.29.29.29,8.8.8.8";

    private static boolean running = false;
    private static Process backendProcess = null;


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
        sb.append(isBackendAlive() ? "[状态] 运行中" : "[状态] 未运行").append('\n');
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

        // DNS：优先用 files/dns.conf（用户显式指定），否则自动取系统 netd 分配的 DNS
        // 并探测 UDP/53 可达性，不可达则回退公共 DNS。
        // 注意：Go 二进制是 CGO_ENABLED=0 静态包，无法走 Android netd/cgo resolver，
        // 且 Android 上没有 /etc/resolv.conf，所以必须由 LD_PRELOAD hook 提供这份文件。
        File dnsConf = new File(workDir, "dns.conf");
        List<String> dnsList = SystemDns.resolveDnsList(this, dnsConf);
        String dnsServers = String.join(",", dnsList);
        diag("DNS 服务器=" + dnsServers);

        File resolvFile = new File(workDir, "resolv.conf");
        SystemDns.writeResolvConf(resolvFile, dnsList);

        File hookLib = new File(getApplicationInfo().nativeLibraryDir, "libdns_hook.so");

        String cmd = "cd " + workDir.getAbsolutePath() + " && " +
                     "export TIKTOKEN_CACHE_DIR=" + tokenDir.getAbsolutePath() + " && " +
                     "export GODEBUG=netdns=go=1 && " +
                     "export CUSTOM_RESOLV_CONF=" + resolvFile.getAbsolutePath() + " && " +
                     "export SQLITE_PATH=" + new File(workDir, "one-api.db").getAbsolutePath() + " && " +
                     "export LD_PRELOAD=" + hookLib.getAbsolutePath() + " && " +
                     // -log-dir 必须显式指定：上游默认 "./logs"，Android 上 cwd=/ 导致
                     // mkdir /logs 失败（read-only file system）而启动退出。
                     "setsid " + bin.getAbsolutePath() + " --port " + port +
                     " -log-dir " + new File(workDir, "logs").getAbsolutePath() +
                     " >> " + logFile.getAbsolutePath() + " 2>&1 & echo $!";

        diag("执行命令: " + cmd);
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        backendProcess = p;
        String pid = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
        int ec = p.waitFor();
        diag("命令退出码=" + ec + " pid=" + pid);

        if (pid != null && !pid.isEmpty()) {
            running = true;
            diag("启动成功 PID=" + pid);
        } else {
            diag("[致命] 未取得 PID，启动失败");
        }
    }

    public static void stopProcess() {
        // 直接杀掉本 Service 启动的后端进程（Android 会连带回收同 uid 子进程）
        Process p = backendProcess;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) {}
        }
        running = false;
    }

    @Override
    public void onDestroy() {
        running = false;
        Process p = backendProcess;
        if (p != null) { try { p.destroy(); } catch (Exception ignored) {} backendProcess = null; }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /** 后端是否在跑：直接看端口有没有在监听（不需要 PID 文件） */
    private static boolean isBackendAlive() {
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", 3000), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}