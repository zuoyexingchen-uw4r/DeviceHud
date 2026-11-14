package io.github.zuoyexingchen.devicehud.bridge;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 有线旁路 Shell 桥。
 *
 * <p>由电脑侧脚本通过 adb 以 {@code app_process} 启动，进程 uid 为 shell(2000)，
 * 因此能读取 {@code /proc/stat} 等 untrusted_app 被 SELinux 拒绝的节点。
 *
 * <h3>鉴权（防御性设计）</h3>
 * <ol>
 *   <li>只监听 127.0.0.1，不接受外部连接；</li>
 *   <li>每条连接第一行必须是 {@code AUTH <token>}，token 从 argv 指定的文件读取
 *       （文件位于 /data/local/tmp，权限 600，untrusted_app 读不到，也不进 cmdline）；</li>
 *   <li>校验连接方 uid：读取 /proc/net/tcp 反查该连接四元组对应的 uid，
 *       不是允许的 uid 直接断开；</li>
 *   <li>认证失败计数超过阈值后进程自行退出，避免被爆破。</li>
 * </ol>
 *
 * <h3>协议</h3>
 * 请求：一行命令（\n 结尾）。内建命令不 fork，直接由本进程读取，开销极低：
 * <ul>
 *   <li>{@code __PING__} → {@code PONG}</li>
 *   <li>{@code __STAT__} → /proc/stat 全文</li>
 *   <li>{@code __LOADAVG__} → /proc/loadavg 全文</li>
 *   <li>{@code __UPTIME__} → /proc/uptime 全文</li>
 * </ul>
 * 其它命令交给 {@code /system/bin/sh -c} 执行。
 * 响应：输出原文 + {@code \n__END__<exitCode>\n} 作为帧尾。
 */
public final class ShellBridge {

    public static final int DEFAULT_PORT = 47200;

    private static final String END = "\n__END__";
    private static final int MAX_LINE = 8192;
    private static final long CMD_TIMEOUT_MS = 15_000L;
    /** 允许的认证失败次数，超过即退出 */
    private static final int MAX_AUTH_FAIL = 12;

    private static String token = "";
    private static int allowedUid = -1;
    /** 期望的调用方包名；由 uid 反查得到，客户端自报的包名一律不信 */
    private static String allowedPackage = "";
    private static int port = DEFAULT_PORT;
    private static int authFail = 0;

    public static void main(String[] args) {
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (args.length > 1) {
            token = readFile(args[1]).trim();
        }
        if (args.length > 2) {
            try {
                allowedUid = Integer.parseInt(args[2].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (args.length > 3) {
            allowedPackage = args[3].trim();
        }

        if (token.length() < 16) {
            // 没有令牌就拒绝启动 —— 绝不暴露一个无鉴权的 shell
            System.err.println("refuse to start: token missing or too short");
            System.exit(4);
            return;
        }

        ServerSocket server = bind(port);
        if (server == null) {
            System.err.println("bind failed on 127.0.0.1:" + port);
            System.exit(5);
            return;
        }
        port = server.getLocalPort();

        System.out.println("BRIDGE_READY " + port + " uid=" + selfUid()
                + " expect_uid=" + allowedUid + " expect_pkg=" + allowedPackage);
        System.out.flush();

        while (true) {
            try {
                final Socket socket = server.accept();
                Thread t = new Thread(() -> serve(socket), "bridge-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                sleep(50);
            }
        }
    }

    private static ServerSocket bind(int want) {
        for (int p = want; p < want + 40; p++) {
            try {
                return new ServerSocket(p, 8, InetAddress.getByName("127.0.0.1"));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void serve(Socket socket) {
        boolean authed = false;
        try (Socket s = socket) {
            s.setSoTimeout(120_000);
            s.setTcpNoDelay(true);

            // —— 调用方身份校验：uid 由内核给出（/proc/net/tcp），
            //    包名再用 uid 反查系统表得到；客户端自报的包名一律不采信
            int peerUid = peerUid(s.getPort());
            String deny = authorize(peerUid);
            if (deny != null) {
                fail(deny);
                return;
            }

            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            // 挑战先发：每条连接一个新 nonce，token 本身永不上线
            byte[] nonce = new byte[16];
            new java.security.SecureRandom().nextBytes(nonce);
            out.write(("NONCE " + hex(nonce) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] buf = new byte[4096];
            ByteArrayOutputStream line = new ByteArrayOutputStream(128);

            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b != '\n') {
                        line.write(b);
                        if (line.size() > MAX_LINE) {
                            fail("line too long");
                            return;
                        }
                        continue;
                    }
                    String cmd = new String(line.toByteArray(), StandardCharsets.UTF_8).trim();
                    line.reset();
                    if (cmd.isEmpty()) continue;

                    if (!authed) {
                        if (!checkAuth(cmd, nonce)) {
                            fail("bad auth");
                            return;
                        }
                        authed = true;
                        // 握手应答不带帧尾，和后续命令响应分开，避免客户端错位
                        out.write("AUTH_OK\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        continue;
                    }

                    int[] code = new int[1];
                    String text = dispatch(cmd, code);
                    byte[] payload = text.getBytes(StandardCharsets.UTF_8);
                    out.write(payload);
                    out.write((END + code[0] + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 校验客户端应答：HMAC-SHA256(nonce, token)。
     *
     * <p>token 从不上线，抓到线上流量也无法重放到另一条连接（nonce 每条连接都变）。
     */
    private static boolean checkAuth(String cmd, byte[] nonce) {
        if (nonce == null || !cmd.startsWith("AUTH ")) return false;
        String got = cmd.substring(5).trim();
        String want = hmacHex(nonce, token);
        if (got.length() != want.length()) return false;
        // 定长比较，避免长度差带来的时序侧信道
        int diff = 0;
        for (int i = 0; i < want.length(); i++) {
            diff |= want.charAt(i) ^ got.charAt(i);
        }
        return diff == 0;
    }

    private static String hmacHex(byte[] nonce, String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(nonce));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 判定这条连接是否有资格执行命令。
     *
     * <p>和 Shizuku 同构：身份来自内核（Binder 给的是 calling uid，
     * TCP 这边给的是 /proc/net/tcp 里的属主 uid），包名再由 uid 反查系统表。
     * 客户端从未有机会声明"我是谁"。
     *
     * @return null 表示放行，否则返回拒绝原因
     */
    private static String authorize(int uid) {
        if (uid <= 0) return "peer uid unknown";
        // root / shell 本就拥有全部权限，放行只是为了电脑侧自测，不构成额外放权
        if (uid == 0 || uid == 2000) return null;

        if (allowedUid > 0 && uid != allowedUid) {
            return "uid " + uid + " != " + allowedUid;
        }
        if (allowedPackage.isEmpty()) return null;

        java.util.List<String> pkgs = packagesForUid(uid);
        if (pkgs.isEmpty()) return "uid " + uid + " resolves to no package";
        if (!pkgs.contains(allowedPackage)) {
            return "uid " + uid + " owns " + pkgs + ", not " + allowedPackage;
        }
        if (pkgs.size() > 1) {
            // sharedUserId 场景：同 uid 的其它包也在同一信任域内，只告警不阻断
            System.err.println("warn: uid " + uid + " shared by " + pkgs);
        }
        return null;
    }

    /** 由 uid 反查包名。优先读系统权威表，读不到再退回 pm 命令。 */
    private static java.util.List<String> packagesForUid(int uid) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String want = String.valueOf(uid);

        for (String line : readFile("/data/system/packages.list").split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 2) continue;
            if (want.equals(f[1])) out.add(f[0]);
        }
        if (!out.isEmpty()) return out;

        int[] code = new int[1];
        for (String line : exec("cmd package list packages -U", code).split("\n")) {
            String p = line.trim();
            if (!p.startsWith("package:")) continue;
            int sp = p.indexOf(" uid:");
            if (sp < 0) continue;
            if (want.equals(p.substring(sp + 5).trim())) {
                out.add(p.substring(8, sp));
            }
        }
        return out;
    }

    private static void fail(String why) {
        synchronized (ShellBridge.class) {
            authFail++;
            int now = authFail;
            System.err.println("reject: " + why + " (#" + now + ")");
            if (now >= MAX_AUTH_FAIL) {
                System.err.println("too many failures, exiting");
                System.exit(3);
            }
        }
    }

    private static String dispatch(String cmd, int[] code) {
        switch (cmd) {
            case "__PING__":
                code[0] = 0;
                return "PONG";
            case "__STAT__":
                code[0] = 0;
                return readFile("/proc/stat");
            case "__LOADAVG__":
                code[0] = 0;
                return readFile("/proc/loadavg");
            case "__UPTIME__":
                code[0] = 0;
                return readFile("/proc/uptime");
            default:
                return exec(cmd, code);
        }
    }

    private static void write(OutputStream out, String s, int code) throws IOException {
        out.write((s + END + code + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String exec(String cmd, int[] exitHolder) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();

            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            InputStream is = p.getInputStream();
            byte[] buf = new byte[16384];
            long deadline = System.currentTimeMillis() + CMD_TIMEOUT_MS;
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 8 * 1024 * 1024) break;
                if (System.currentTimeMillis() > deadline) {
                    p.destroy();
                    break;
                }
            }
            try {
                exitHolder[0] = p.waitFor();
            } catch (InterruptedException e) {
                exitHolder[0] = -1;
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            exitHolder[0] = -2;
            return "ERR " + e;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /**
     * 用 /proc/net/tcp{,6} 反查这条连接属于哪个 uid。
     *
     * <p>连接四元组里，服务端是 local:port，客户端是 local:clientPort。
     * 注意 Java 的 ServerSocket 绑 127.0.0.1 时实际是 IPv6 双栈，
     * 条目会落在 tcp6 里（::ffff:127.0.0.1），两张表都要查。
     *
     * <p>找不到就返回 -1，由 {@link #authorize} 按"身份不可判定"拒绝（fail closed）。
     */
    private static int peerUid(int clientPort) {
        String serverP = String.format(Locale.US, "%04X", port);
        String clientP = String.format(Locale.US, "%04X", clientPort);

        for (String path : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            for (String row : readFile(path).split("\n")) {
                String[] f = row.trim().split("\\s+");
                if (f.length < 8) continue;
                if (!"01".equals(f[3])) continue;               // 只认 ESTABLISHED

                String lPort = tail(f[1]);
                String rPort = tail(f[2]);
                if (!serverP.equalsIgnoreCase(lPort)) continue;
                if (!clientP.equalsIgnoreCase(rPort)) continue;

                try {
                    return Integer.parseInt(f[7]);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** 取 "ADDR:PORT" 里最后一段（IPv6 地址里也有冒号，split 后取末段才是端口） */
    private static String tail(String addrPort) {
        int i = addrPort.lastIndexOf(':');
        return i < 0 ? addrPort : addrPort.substring(i + 1);
    }

    private static String readFile(String path) {
        try (InputStream is = new FileInputStream(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] b = new byte[8192];
            int n;
            while ((n = is.read(b)) > 0) {
                bos.write(b, 0, n);
                if (bos.size() > 1024 * 1024) break;
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static int selfUid() {
        for (String l : readFile("/proc/self/status").split("\n")) {
            if (l.startsWith("Uid:")) {
                try {
                    return Integer.parseInt(l.substring(4).trim().split("\\s+")[0]);
                } catch (Exception ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
