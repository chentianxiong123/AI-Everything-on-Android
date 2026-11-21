package com.example.newapi;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 系统 DNS 解析与 resolv.conf 生成。
 *
 * 背景：Go 纯解析器（GODEBUG=netdns=go=1）不经过 Android netd，必须自己提供
 * /etc/resolv.conf（Android 上不存在）。本类从 ConnectivityManager 读取 netd
 * 当前分配给本机的 DNS 服务器，探测其 UDP/53 是否可达，然后写入 app 私有的
 * resolv.conf 供 LD_PRELOAD hook 重定向使用。
 *
 * Go 的纯解析器每次 DNS 查询都会重新读取该文件，因此网络切换后只要重写文件
 * 即可生效，无需重启后端进程。
 */
public class SystemDns {

    private static final String TAG = "SystemDns";
    // 公共 DNS 兜底（国内优先 + 海外兜底）
    private static final String[] FALLBACK_DNS = {
            "223.5.5.5", "119.29.29.29", "180.76.76.76", "8.8.8.8", "1.1.1.1"
    };
    private static final int PROBE_TIMEOUT_MS = 1500;

    /** 读取系统为当前网络分配的 DNS 列表（按系统返回顺序） */
    public static List<String> systemDnsServers(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return out;
            Network net = cm.getActiveNetwork();
            if (net == null) return out;
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp == null) return out;
            for (InetAddress a : lp.getDnsServers()) {
                if (a == null) continue;
                // 去掉 IPv6 zone id（如 fe80::1%wlan0）
                String ip = a.getHostAddress();
                int pct = ip.indexOf('%');
                if (pct > 0) ip = ip.substring(0, pct);
                if (!out.contains(ip)) out.add(ip);
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取系统 DNS 失败: " + t);
        }
        return out;
    }

    /**
     * 探测 DNS 服务器的 UDP/53 是否真的可用。
     * 某些路由器/公共 DNS 会丢弃 UDP 查询，或只在 TCP 上响应，此时必须排除，
     * 否则 Go 纯解析器会一直超时。
     */
    public static boolean isReachable(String dnsIp) {
        try {
            InetAddress server = InetAddress.getByName(dnsIp);
            byte[] query = buildQuery("example.com", (short) 0x4d57);
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(PROBE_TIMEOUT_MS);
                sock.send(new DatagramPacket(query, query.length, new InetSocketAddress(server, 53)));
                byte[] buf = new byte[512];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                sock.receive(resp);
                // 响应头 12 字节；rcode != 0 视为不可用（REFUSED/NXDOMAIN 场景）
                if (resp.getLength() < 12) return false;
                int rcode = buf[3] & 0x0F;
                return rcode == 0;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** 构造标准 A 记录查询包 */
    private static byte[] buildQuery(String host, short id) {
        byte[] head = new byte[12];
        head[0] = (byte) (id >> 8);
        head[1] = (byte) id;
        head[2] = 0x01; head[3] = 0x00;   // 标准查询
        head[4] = 0x00; head[5] = 0x01;   // QDCOUNT=1
        // ANCOUNT/NSCOUNT/ARCOUNT = 0
        byte[] name = new byte[host.length() + 2];
        int i = 0;
        for (String part : host.split("\\.")) {
            byte[] b = part.getBytes();
            name[i++] = (byte) b.length;
            System.arraycopy(b, 0, name, i, b.length);
            i += b.length;
        }
        name[i++] = 0x00;
        byte[] tail = new byte[4];
        tail[0] = 0x00; tail[1] = 0x01;   // TYPE=A
        tail[2] = 0x00; tail[3] = 0x01;   // CLASS=IN
        byte[] out = new byte[head.length + name.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(name, 0, out, head.length, name.length);
        System.arraycopy(tail, 0, out, head.length + name.length, tail.length);
        return out;
    }

    /**
     * 生成最终的 DNS 列表：
     *   1) dns.conf 中用户显式指定的（若存在且非空）——最高优先级
     *   2) 系统 DNS 中 UDP/53 可达的
     *   3) 公共 DNS 兜底（也做可达性探测，探测失败的放最后）
     */
    public static List<String> resolveDnsList(Context ctx, File dnsConf) {
        List<String> userDns = readUserDns(dnsConf);
        if (!userDns.isEmpty()) {
            Log.i(TAG, "使用 dns.conf 指定 DNS: " + userDns);
            return userDns;
        }

        List<String> result = new ArrayList<>();
        List<String> sys = systemDnsServers(ctx);
        for (String ip : sys) {
            if (isReachable(ip)) {
                result.add(ip);
            } else {
                Log.i(TAG, "系统 DNS " + ip + " UDP/53 不可达，跳过");
            }
        }
        for (String ip : FALLBACK_DNS) {
            if (!result.contains(ip) && isReachable(ip)) result.add(ip);
        }
        // 一个都探测不到时仍然写全兜底（网络刚起来时可能暂时不通）
        if (result.isEmpty()) {
            result.addAll(java.util.Arrays.asList(FALLBACK_DNS));
        }
        Log.i(TAG, "最终 DNS 列表: " + result + "（系统: " + sys + "）");
        return result;
    }

    /** 读取用户自定义 dns.conf（每行一个或逗号分隔，# 开头为注释） */
    private static List<String> readUserDns(File dnsConf) {
        List<String> out = new ArrayList<>();
        if (dnsConf == null || !dnsConf.exists()) return out;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(dnsConf))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                for (String part : line.split("[,，\\s]+")) {
                    part = part.trim();
                    if (!part.isEmpty()) out.add(part);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 把 DNS 列表写入 resolv.conf（Go 纯解析器通过 hook 读取此文件）。
     * 写入是原子的（先写临时文件再 rename），避免 Go 读到半截内容。
     */
    public static void writeResolvConf(File resolvFile, List<String> dnsList) {
        try {
            File parent = resolvFile.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(resolvFile.getAbsolutePath() + ".tmp");
            try (FileWriter w = new FileWriter(tmp)) {
                for (String ip : dnsList) {
                    w.write("nameserver " + ip + "\n");
                }
                w.write("options timeout:2 attempts:2\n");
            }
            if (resolvFile.exists() && !resolvFile.delete()) {
                Log.w(TAG, "删除旧 resolv.conf 失败");
            }
            if (!tmp.renameTo(resolvFile)) {
                // rename 失败时退化为直接写
                try (FileWriter w = new FileWriter(resolvFile)) {
                    for (String ip : dnsList) {
                        w.write("nameserver " + ip + "\n");
                    }
                    w.write("options timeout:2 attempts:2\n");
                }
            }
            Log.i(TAG, "resolv.conf 已更新: " + dnsList);
        } catch (Throwable t) {
            Log.e(TAG, "写 resolv.conf 失败: " + t);
        }
    }

    /** 网络切换时调用：重新探测并刷新 resolv.conf（Go 下次查询即生效） */
    public static void refresh(Context ctx, File resolvFile, File dnsConf) {
        List<String> list = resolveDnsList(ctx, dnsConf);
        writeResolvConf(resolvFile, list);
    }
}
