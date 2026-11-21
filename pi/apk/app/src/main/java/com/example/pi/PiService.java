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
    private static String lastPort = "30141";

    private static final String DATA_DIR = "/data/data/com.example.pi/files";
    private static final int PORT = 30141;
    private static final String RUNTIME_DIR = DATA_DIR + "/runtime";
    private static final String RUNTIME_MARKER = DATA_DIR + "/runtime.version";
    // node 可执行文件：以 libnode.so 形式打进 jniLibs，安装时系统解压到
    // nativeLibraryDir（SELinux 允许 app 从那里 exec；filesDir 下 exec 会被拒 error=13）。
    private File nodeExecFile() {
        return new File(getApplicationInfo().nativeLibraryDir, "libnode.so");
    }

    public static boolean isRunning() {
        File pidFile = new File(DATA_DIR, "pi.pid");
        if (!pidFile.exists()) return isPortListening(PORT);   // 无 pid 文件时用端口探测兜底
        try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
            String pid = r.readLine();
            if (pid == null || pid.isEmpty()) return isPortListening(PORT);
            int pidNum;
            try { pidNum = Integer.parseInt(pid.trim()); } catch (Exception e) { return isPortListening(PORT); }
            if (pidNum <= 0) return isPortListening(PORT);   // -1/0 是无效 pid，改用端口探测
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
            sb.append("--- pi-web 输出 ---\n");
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
                    .setContentTitle("Pi Web")
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
        // 重复启动保护：进程已在跑时直接返回。否则每次打开应用都会再起一个
        // Node 进程抢同一端口，新进程启动失败还会覆盖 pid 文件，导致停止时杀错进程。
        if (isRunning()) {
            diag("服务已在运行，跳过重复启动");
            return START_STICKY;
        }
        // intent 为 null 表示系统按 START_STICKY 重启服务，此时沿用上次端口启动
        String port = null;
        if (intent != null) port = intent.getStringExtra("port");
        if (port != null && !port.isEmpty()) lastPort = port;

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

    /** 释放 assets 文本/二进制到目标位置 */
    private void extractAsset(String name, File dest) throws IOException {
        try (InputStream in = getAssets().open(name);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /**
     * 解压 assets/runtime.tar 到 filesDir/runtime。
     * runtime 是裸 tar（aapt2 会把 .tar.gz 解压改名，故打裸 .tar）。
     */
    private boolean ensureRuntimeExtracted() throws Exception {
        File runtimeDir = new File(RUNTIME_DIR);
        File marker = new File(RUNTIME_MARKER);

        String assetVersion = "1";
        try (InputStream in = getAssets().open("runtime.version");
             BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String v = r.readLine();
            if (v != null && !v.trim().isEmpty()) assetVersion = v.trim();
        } catch (Exception e) {
            diag("[提示] 无 runtime.version asset，默认版本 1");
        }

        File nodeBin = new File(runtimeDir, "bin/node");
        File execNode = nodeExecFile();
        // marker 与 exec node 同时存在才算就绪
        if (nodeBin.exists() && execNode.exists() && marker.exists()) {
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

        deleteRecursive(runtimeDir);
        runtimeDir.mkdirs();

        boolean ok = extractWithTar(tarball, runtimeDir);
        if (!ok) {
            diag("系统 tar 失败，改用 node 自解");
            ok = extractWithNode(tarball, runtimeDir);
        }
        if (!ok) {
            diag("[致命] runtime 解压失败（tar 与 node 均失败）");
            return false;
        }

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

    /** 兜底：用 node 手解 ustar。runtime 是裸 tar（非 gz），直接解析。 */
    private boolean extractWithNode(File tarball, File destDir) {
        try {
            File node = new File(destDir, "bin/node");
            if (!node.exists()) {
                diag("[致命] 兜底解压需要 node，但 node 不存在");
                return false;
            }
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
            pb.directory(destDir);
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

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private void startProcess() throws Exception {
        diag("startProcess 开始 port=" + lastPort);
        File workDir = getFilesDir();
        File pidFile = new File(workDir, "pi.pid");

        if (!ensureRuntimeExtracted()) {
            diag("[致命] runtime 未就绪，启动中止");
            return;
        }

        File runtimeDir = new File(RUNTIME_DIR);
        File nodeBin = nodeExecFile();   // 从 nativeLibraryDir 执行（SELinux 允许）
        File libDir = new File(runtimeDir, "lib");
        File piWebBin = new File(runtimeDir, "lib/node_modules/@agegr/pi-web/bin/pi-web.js");

        if (!piWebBin.exists()) {
            diag("[致命] pi-web 不存在: " + piWebBin.getAbsolutePath());
            return;
        }
        diag("pi-web=" + piWebBin.getAbsolutePath());

        // pi-web 用 ~/.pi/agent 存配置/会话（和官方 pi 一致）。
        // 把 HOME 指向 app 私有目录，让 pi-web 的 agent-dir 落在 filesDir/.pi/agent。
        File homeDir = new File(workDir, "home");
        homeDir.mkdirs();

        ProcessBuilder pb = new ProcessBuilder(
            nodeBin.getAbsolutePath(), piWebBin.getAbsolutePath(),
            "--port", lastPort, "--hostname", "127.0.0.1", "--no-open");
        pb.directory(homeDir);
        pb.redirectErrorStream(true);
        pb.environment().put("HOME", homeDir.getAbsolutePath());
        pb.environment().put("TMPDIR", workDir.getAbsolutePath());
        // 关键：node 动态链接 libicu/openssl/sqlite 等，必须指向解压的 lib
        pb.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        pb.environment().put("NODE_PATH", new File(runtimeDir, "lib/node_modules").getAbsolutePath());
        // 禁用 pi-web 自动开浏览器（无 GUI）；WebView 直接访问
        pb.environment().put("PI_WEB_NO_OPEN", "1");

        diag("启动 pi-web: " + nodeBin.getAbsolutePath() + " " + piWebBin.getAbsolutePath()
             + " --port " + lastPort + " LD_LIBRARY_PATH=" + libDir.getAbsolutePath());
        Process proc = pb.start();
        diag("pb.start() 成功");
        running = true;

        try {
            int pid = getPid(proc);
            try (FileWriter w = new FileWriter(pidFile)) { w.write(String.valueOf(pid)); }
        } catch (Exception e) {
            // 拿不到 PID 时删除可能残留的旧 pid 文件（残留 -1 会让 isRunning 恒真）
            try { pidFile.delete(); } catch (Exception ignored) {}
            diag("[警告] 无法保存 PID（后续用端口探测兜底）: " + e);
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

    private static int getPid(Process p) {
        try {
            return (int) p.getClass().getMethod("pid").invoke(p);
        } catch (Exception e) {
            File pidFile = new File(DATA_DIR, "pi.pid");
            try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
                return Integer.parseInt(r.readLine());
            } catch (Exception ex) {
                // 拿不到真实 PID 时绝不能返回 -1：写进 pid 文件后，
                // isRunning() 里的 `kill -0 -1` 对所有进程都返回成功，
                // 会误判"服务已在运行"从而永远不再启动。改为抛异常，由调用方跳过写 pid。
                throw new IllegalStateException("无法获取子进程 PID", ex);
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

    /** 端口探测兜底：pid 不可靠时用它判断后端是否真的在监听 */
    private static boolean isPortListening(int port) {
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
