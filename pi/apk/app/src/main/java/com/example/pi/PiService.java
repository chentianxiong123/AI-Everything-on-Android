package com.example.pi;

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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

public class PiService extends Service {
    private static boolean running = false;
    private static String lastPort = "8765";
    private static String lastProvider = "anthropic";
    private static String lastApiKey = "";
    private static String lastModel = "claude-sonnet-4-5";

    private static final String DATA_DIR = "/data/data/com.example.pi/files";
    private static final String RUNTIME_DIR = DATA_DIR + "/runtime";
    private static final String RUNTIME_MARKER = DATA_DIR + "/runtime.version";

    public static boolean isRunning() {
        File pidFile = new File(DATA_DIR, "pi.pid");
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

    private void diag(String msg) {
        android.util.Log.i("PiService", msg);
        try {
            File f = new File(DATA_DIR, "pi.log");
            f.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(msg + "\n");
            }
        } catch (Throwable ignored) {}
    }

    public static String getLog() {
        StringBuilder sb = new StringBuilder();
        File logFile = new File(DATA_DIR, "pi.log");
        sb.append(isRunning() ? "[状态] 运行中" : "[状态] 未运行").append('\n');
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
        File stdoutFile = new File(DATA_DIR, "pi.stdout.log");
        if (stdoutFile.exists()) {
            sb.append("--- bridge 输出 ---\n");
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

    @Override
    public void onCreate() {
        super.onCreate();
        diag("=== onCreate 进入，PID=" + android.os.Process.myPid() + " ===");
        try {
            String channelId = "pi_channel";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channelId, "Pi", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Notification notification = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("Pi Runner")
                    .setContentText("运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_send)
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
        String provider = intent.getStringExtra("provider");
        String apiKey = intent.getStringExtra("apikey");
        String model = intent.getStringExtra("model");
        if (port != null && !port.isEmpty()) lastPort = port;
        if (provider != null && !provider.isEmpty()) lastProvider = provider;
        if (apiKey != null) lastApiKey = apiKey;
        if (model != null && !model.isEmpty()) lastModel = model;

        new Thread(() -> {
            try {
                startProcess();
            } catch (Throwable e) {
                diag("[致命] startProcess 异常: " + e);
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                diag(sw.toString());
                running = false;
            }
        }).start();
        return START_STICKY;
    }

    /** 写一段 assets 文本文件到目标位置 */
    private void extractAsset(String name, File dest) throws IOException {
        try (InputStream in = getAssets().open(name);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /**
     * 解压 assets/runtime.tar.gz 到 filesDir/runtime。
     * 用内嵌的 busybox-free 方式：优先用系统 tar（API>=21 一般没有），退化到
     * 内嵌的 node 来解（node -e 一段 tar.gz 解包脚本）。这里用最可靠的：
     * 用 /system/bin/toybox tar（Android 6+ 自带），失败再用 node 自解。
     */
    private boolean ensureRuntimeExtracted() throws Exception {
        File runtimeDir = new File(RUNTIME_DIR);
        File marker = new File(RUNTIME_MARKER);

        String assetVersion = "1";
        // 读 assets/runtime.version 判断是否需要重新解压
        try (InputStream in = getAssets().open("runtime.version");
             BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String v = r.readLine();
            if (v != null && !v.trim().isEmpty()) assetVersion = v.trim();
        } catch (Exception e) {
            diag("[提示] 无 runtime.version asset，默认版本 1");
        }

        File nodeBin = new File(runtimeDir, "bin/node");
        if (nodeBin.exists() && marker.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(marker))) {
                String v = r.readLine();
                if (assetVersion.equals(v != null ? v.trim() : "")) {
                    diag("runtime 已就绪（版本 " + assetVersion + "），跳过解压");
                    return true;
                }
            } catch (Exception ignored) {}
        }

        diag("解压 runtime 到 " + runtimeDir.getAbsolutePath());
        runtimeDir.mkdirs();
        File tarball = new File(getFilesDir(), "runtime.tar");
        extractAsset("runtime.tar", tarball);
        diag("runtime.tar 大小=" + tarball.length() + " 字节");

        // 清理旧 runtime
        deleteRecursive(runtimeDir);
        runtimeDir.mkdirs();

        // 方式 A: toybox/busybox tar（裸 tar，无需 -z）
        boolean ok = extractWithTar(tarball, runtimeDir);
        if (!ok) {
            diag("系统 tar 失败，改用 node 自解");
            ok = extractWithNode(tarball, runtimeDir);
        }
        if (!ok) {
            diag("[致命] runtime 解压失败（tar 与 node 均失败）");
            return false;
        }

        // 确保 node 可执行
        try {
            Runtime.getRuntime().exec(new String[]{"chmod", "755", nodeBin.getAbsolutePath()}).waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            diag("[警告] chmod node 失败: " + e);
        }
        diag("node 存在=" + nodeBin.exists() + " 可执行=" + nodeBin.canExecute());

        try (FileWriter w = new FileWriter(marker)) {
            w.write(assetVersion + "\n");
        }
        tarball.delete();
        return nodeBin.exists();
    }

    private boolean extractWithTar(File tarball, File destDir) {
        String[][] cmds = {
            {"tar", "-xf", tarball.getAbsolutePath(), "-C", destDir.getAbsolutePath()},
            {"/system/bin/tar", "-xf", tarball.getAbsolutePath(), "-C", destDir.getAbsolutePath()},
            {"busybox", "tar", "-xf", tarball.getAbsolutePath(), "-C", destDir.getAbsolutePath()},
        };
        for (String[] cmd : cmds) {
            try {
                Process p = Runtime.getRuntime().exec(cmd);
                StringBuilder err = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) err.append(l).append('\n');
                }
                int code = p.waitFor();
                if (code == 0 && new File(destDir, "bin/node").exists()) {
                    diag("tar 解压成功 via " + cmd[0]);
                    return true;
                }
                diag("tar via " + cmd[0] + " 失败 code=" + code + " " + err.substring(0, Math.min(err.length(), 200)));
            } catch (Exception e) {
                diag("tar via " + cmd[0] + " 异常: " + e);
            }
        }
        return false;
    }

    /** 兜底：用 node 手解 tar（ustar）。runtime 是裸 tar（非 gz），直接解析。 */
    private boolean extractWithNode(File tarball, File destDir) {
        try {
            File node = new File(destDir, "bin/node");
            if (!node.exists()) {
                diag("[致命] 兜底解压需要 node，但 node 不存在");
                return false;
            }
            // 这段脚本解析标准 ustar（无 gz），我们控制打包格式故够用
            String script =
                "const fs=require('fs'),path=require('path');\n" +
                "const tar=fs.readFileSync(process.argv[1]);\n" +
                "const P=process.argv[2];\n" +
                "let i=0;\n" +
                "while(i<tar.length){\n" +
                " if(i+512>tar.length)break;\n" +
                " const name=tar.toString('utf8',i,i+100).replace(/\\0.*$/,'').trim();\n" +
                " if(!name){i+=1024;continue;}\n" +
                " const mode=tar.toString('utf8',i+100,i+108).replace(/\\0.*$/,'').trim();\n" +
                " const typeflag=String.fromCharCode(tar[i+156]);\n" +
                " const sizeStr=tar.toString('utf8',i+124,i+136).replace(/\\0.*$/,'').trim();\n" +
                " let size=0;for(const c of sizeStr){size=size*8+parseInt(c,8);}\n" +
                " const dataStart=i+512;\n" +
                " if(typeflag==='0'||typeflag==='\\0'){\n" +
                "  const out=path.join(P,name);\n" +
                "  fs.mkdirSync(path.dirname(out),{recursive:true});\n" +
                "  fs.writeFileSync(out,tar.slice(dataStart,dataStart+size));\n" +
                "  try{const m=parseInt(mode||'644',8);fs.chmodSync(out,m);}catch(e){}\n" +
                " }else if(typeflag==='5'){\n" +
                "  fs.mkdirSync(path.join(P,name),{recursive:true});\n" +
                " }\n" +
                " i=dataStart+Math.ceil(size/512)*512;\n" +
                "}\n" +
                "console.log('extracted');\n";
            File scriptFile = new File(getFilesDir(), "extract.js");
            try (FileWriter w = new FileWriter(scriptFile)) { w.write(script); }
            ProcessBuilder pb = new ProcessBuilder(
                node.getAbsolutePath(), scriptFile.getAbsolutePath(),
                tarball.getAbsolutePath(), destDir.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", new File(destDir, "lib").getAbsolutePath());
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) out.append(l).append('\n');
            }
            int code = p.waitFor();
            diag("node 自解 code=" + code + " out=" + out.substring(0, Math.min(out.length(), 300)));
            return code == 0 && new File(destDir, "bin/node").exists();
        } catch (Exception e) {
            diag("node 自解异常: " + e);
            return false;
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private void startProcess() throws Exception {
        diag("startProcess 开始 port=" + lastPort + " provider=" + lastProvider + " model=" + lastModel);
        File workDir = getFilesDir();
        File pidFile = new File(workDir, "pi.pid");

        if (!ensureRuntimeExtracted()) {
            diag("[致命] runtime 未就绪，启动中止");
            return;
        }

        File runtimeDir = new File(RUNTIME_DIR);
        File nodeBin = new File(runtimeDir, "bin/node");
        File libDir = new File(runtimeDir, "lib");
        File bridgeJs = new File(workDir, "bridge.js");

        // bridge.js 从 assets 释放（便于独立更新）
        extractAsset("bridge.js", bridgeJs);
        diag("bridge.js 释放到 " + bridgeJs.getAbsolutePath() + " (" + bridgeJs.length() + " 字节)");

        // 写配置（bridge 读取 pi-config.json）
        File cfg = new File(workDir, "pi-config.json");
        String cfgJson = String.format(
            "{\"port\": %s, \"provider\": \"%s\", \"apiKey\": \"%s\", \"model\": \"%s\", \"runtimeDir\": \"%s\"}",
            sanitize(lastPort), sanitize(lastProvider), sanitize(lastApiKey), sanitize(lastModel),
            runtimeDir.getAbsolutePath());
        try (FileWriter w = new FileWriter(cfg)) { w.write(cfgJson); }
        diag("配置已写入 " + cfg.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(
            nodeBin.getAbsolutePath(), bridgeJs.getAbsolutePath());
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("HOME", workDir.getAbsolutePath());
        pb.environment().put("TMPDIR", workDir.getAbsolutePath());
        // 关键：node 动态链接 libicu/openssl/sqlite 等，必须指向我们解压的 lib
        pb.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        // pi 在 PATH 里找不到也能通过 NODE_PATH 找 node_modules
        pb.environment().put("NODE_PATH", new File(runtimeDir, "lib/node_modules").getAbsolutePath());

        diag("启动 bridge: " + nodeBin.getAbsolutePath() + " " + bridgeJs.getAbsolutePath()
             + " LD_LIBRARY_PATH=" + libDir.getAbsolutePath());
        Process proc = pb.start();
        diag("pb.start() 成功");
        running = true;

        try {
            int pid = getPid(proc);
            try (FileWriter w = new FileWriter(pidFile)) { w.write(String.valueOf(pid)); }
        } catch (Exception e) {
            android.util.Log.w("PiService", "Could not save PID", e);
        }

        File stdoutFile = new File(workDir, "pi.stdout.log");
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

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int getPid(Process p) {
        try {
            return (int) p.getClass().getMethod("pid").invoke(p);
        } catch (Exception e) {
            File pidFile = new File(DATA_DIR, "pi.pid");
            try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
                return Integer.parseInt(r.readLine());
            } catch (Exception ex) {
                return -1;
            }
        }
    }

    public static void stopProcess() {
        File pidFile = new File(DATA_DIR, "pi.pid");
        if (pidFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
                String pid = r.readLine();
                if (pid != null && !pid.isEmpty()) {
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", "kill " + pid + " 2>/dev/null"});
                }
            } catch (Exception e) {
                android.util.Log.e("PiService", "Error stopping: " + e.getMessage());
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
