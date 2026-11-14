package com.zyxc.devicehud;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 电脑旁路 Shell 桥的客户端。
 *
 * <p>桥进程由电脑侧 {@code bridge/bridge.sh} 通过 adb 以 shell 身份启动，
 * 监听 127.0.0.1:{@value #PORT}。应用连上去之后就能以 shell 身份执行命令，
 * 从而读到 {@code /proc/stat} 这类 untrusted_app 被 SELinux 拒绝的节点。
 *
 * <h3>鉴权</h3>
 * 服务端 accept 后先下发 {@code NONCE <hex>}，客户端用令牌算
 * HMAC-SHA256 回 {@code AUTH <hex>}，令牌本身不上线。
 * 服务端还会用内核记录的连接属主 uid 反查包名，非本应用的连接直接断开。
 * 令牌由电脑侧写入应用外部目录 {@code files/bridge.token}。
 *
 * <h3>协议</h3>
 * 命令一行一条；响应为正文 + {@code \n__END__<exit>\n}。
 * 内建命令（不 fork，开销极低）：{@code __PING__ __STAT__ __LOADAVG__ __UPTIME__}。
 */
public final class ShellBridgeClient {

    public static final int PORT = 47200;

    private static final String TOKEN_NAME = "bridge.token";

    private static final Object LOCK = new Object();
    private static Context app;
    private static Socket socket;
    private static InputStream in;
    private static OutputStream out;

    private static volatile String lastError = "";

    private ShellBridgeClient() {
    }

    public static void init(Context c) {
        if (c != null && app == null) {
            app = c.getApplicationContext();
        }
        if (app != null) {
            ensureToken();
        }
    }

    public static String lastError() {
        return lastError;
    }

    /** 桥是否可用（会尝试建链，失败即 false）。 */
    public static boolean ready() {
        synchronized (LOCK) {
            return ensureConnected();
        }
    }

    /** 执行一条命令并返回输出；不可用时返回 null。 */
    public static String exec(String cmd) {
        synchronized (LOCK) {
            if (!ensureConnected()) return null;
            try {
                out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return readFrame(in);
            } catch (Exception e) {
                lastError = "io: " + e;
                close();
                return null;
            }
        }
    }

    public static String readProcStat() {
        return exec("__STAT__");
    }

    private static String sLogged = "";

    private static void note(String msg) {
        if (msg.equals(sLogged)) return;
        sLogged = msg;
        android.util.Log.i("DeviceHudBridge", msg);
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return true;
        }
        close();
        if (app == null) {
            lastError = "no context";
            return false;
        }

        String token = token();
        if (token == null) {
            lastError = "token unavailable";
            note("token unavailable");
            return false;
        }

        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 400);
            s.setSoTimeout(2000);
            s.setTcpNoDelay(true);

            // 必须缓冲：后面按字节扫描帧尾，裸 SocketInputStream 每次 read() 都是
            // 一次 recv 系统调用，4KB 的 /proc/stat 就是 4000 次
            InputStream is = new java.io.BufferedInputStream(s.getInputStream(), 8192);
            OutputStream os = new java.io.BufferedOutputStream(s.getOutputStream(), 256);

            String greet = readLine(is);
            if (greet == null || !greet.startsWith("NONCE ")) {
                lastError = "no nonce";
                s.close();
                return false;
            }
            byte[] nonce = hexToBytes(greet.substring(6).trim());
            if (nonce == null) {
                lastError = "bad nonce";
                s.close();
                return false;
            }

            os.write(("AUTH " + hmacHex(nonce, token) + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();

            String reply = readLine(is);
            if (reply == null || !reply.startsWith("AUTH_OK")) {
                lastError = "auth rejected";
                s.close();
                return false;
            }

            socket = s;
            in = is;
            out = os;
            lastError = "";
            note("connected");
            return true;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            note("connect failed: " + lastError);
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }

    private static void close() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    // ------------------------------------------------------------------ 令牌

    /**
     * 令牌由应用自己生成（32 hex），落两处：
     * <ul>
     *   <li>内部目录 —— 应用自己的权威副本，一定有读写权限</li>
     *   <li>外部目录 —— 给电脑脚本读；shell 能读 /sdcard</li>
     * </ul>
     * 电脑侧读到之后写进桥的私有令牌文件，桥再用它校验 HMAC。
     * 这样不依赖跨 uid 的文件写入权限，也不需要在界面上手输配对码。
     */
    private static String ensureToken() {
        File internal = new File(app.getFilesDir(), TOKEN_NAME);
        String t = readTokenFile(internal);
        if (t == null) {
            t = randomHex(16);
            writeTokenFile(internal, t);
        }
        // 外部副本，失败不影响功能（只是电脑侧读不到时要换途径）
        try {
            File ext = new File(app.getExternalFilesDir(null), TOKEN_NAME);
            if (!t.equals(readTokenFile(ext))) {
                writeTokenFile(ext, t);
            }
        } catch (Exception ignored) {
        }
        return t;
    }

    private static String token() {
        if (app == null) return null;
        String t = readTokenFile(new File(app.getFilesDir(), TOKEN_NAME));
        return t != null ? t : ensureToken();
    }

    private static String readTokenFile(File f) {
        if (!f.isFile()) return null;
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
            byte[] b = new byte[128];
            int n;
            while ((n = is.read(b)) > 0) bos.write(b, 0, n);
            String t = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
            return t.length() >= 16 ? t : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeTokenFile(File f, String t) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            try (OutputStream os = new java.io.FileOutputStream(f)) {
                os.write(t.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 编解码

    private static String readLine(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        int c;
        while ((c = is.read()) >= 0) {
            if (c == '\n') return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
            bos.write(c);
            if (bos.size() > 512) return null;
        }
        return null;
    }

    /**
     * 读到 {@code \n__END__<n>\n} 为止，返回之前的正文。
     *
     * <p>按块读、只在尾部判断帧尾。早先逐字节读 + 每个换行 toByteArray() 复制整块，
     * 一条 /proc/stat 就要 4000 次 read 和 40 次全量拷贝，实测吃掉 4.8% 单核。
     */
    private static String readFrame(InputStream is) throws Exception {
        byte[] buf = new byte[8192];
        int len = 0;
        byte[] chunk = new byte[2048];
        int n;
        while ((n = is.read(chunk)) > 0) {
            if (len + n > buf.length) {
                buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
            }
            System.arraycopy(chunk, 0, buf, len, n);
            len += n;

            if (len >= 10 && buf[len - 1] == '\n') {
                int cut = frameEnd(buf, len);
                if (cut >= 0) return new String(buf, 0, cut, StandardCharsets.UTF_8);
            }
            if (len > 4 * 1024 * 1024) break;
        }
        throw new java.io.IOException("frame not terminated");
    }

    /** 帧尾形如 "\n__END__0\n"，返回正文结束位置；不是帧尾则 -1。 */
    private static int frameEnd(byte[] all, int len) {
        if (len < 10 || all[len - 1] != '\n') return -1;

        int i = len - 2;
        int digitsEnd = i;
        while (i >= 0 && (all[i] == '-' || (all[i] >= '0' && all[i] <= '9'))) i--;
        if (i == digitsEnd) return -1;                 // 没有退出码

        int tagStart = i - 6;                          // "__END__" 共 7 字节，末字节在 i
        if (tagStart < 1) return -1;
        if (!"__END__".equals(new String(all, tagStart, 7, StandardCharsets.US_ASCII))) return -1;
        if (all[tagStart - 1] != '\n') return -1;

        return tagStart - 1;                           // 正文到此为止（不含前导换行）
    }

    private static byte[] hexToBytes(String s) {
        if (s == null || s.length() % 2 != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String hmacHex(byte[] nonce, String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(nonce);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
