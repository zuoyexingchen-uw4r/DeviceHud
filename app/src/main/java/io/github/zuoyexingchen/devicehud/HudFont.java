package io.github.zuoyexingchen.devicehud;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 自定义字体。
 *
 * <p>只支持"选一个 ttf/otf 文件"这一种方式：内置字体要打包进 APK，
 * 而这个项目的定位是"零依赖、体积小"，让用户自己带字体更合适。
 *
 * <p>文件会被复制到应用私有目录再加载。不能直接用 SAF 的 uri：
 * {@link Typeface#createFromFile} 只吃文件路径，吃不了 content:// 。
 */
public final class HudFont {

    /** 固定文件名：覆盖写，避免换字体后旧文件越积越多 */
    private static final String FILE_NAME = "hud_font.ttf";

    /** 单个字体文件上限。中文字体常见 10~20MB，给到 32MB 足够 */
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    private static Typeface sTypeface;
    private static String sLoadedKey = "";
    private static boolean sFailed;

    private HudFont() {
    }

    public static File file(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    public static boolean has(Context c) {
        File f = file(c);
        return f.isFile() && f.length() > 0;
    }

    /**
     * 当前应该用的字体。
     *
     * @return 自定义字体；没设置或加载失败时返回 {@code null}，
     *         调用方回落到 {@link Typeface#MONOSPACE}
     */
    public static Typeface typeface(Context c) {
        String key = HudState.fontFile;
        if (key == null || key.isEmpty()) return null;
        if (key.equals(sLoadedKey)) return sTypeface;

        sLoadedKey = key;
        sTypeface = null;
        sFailed = false;
        try {
            sTypeface = Typeface.createFromFile(file(c));
        } catch (Throwable t) {
            // 文件损坏 / 不是字体 / 权限问题都在这里，一律当作"没有自定义字体"。
            // 悬浮窗每帧都会调到这里，不能抛异常，否则整条子直接消失
            sFailed = true;
            sTypeface = null;
        }
        return sTypeface;
    }

    /** 上一次加载是否失败（界面用来提示"字体文件无效，已回落系统字体"） */
    public static boolean lastLoadFailed() {
        return sFailed;
    }

    /**
     * 导入一个字体文件。
     *
     * <p>只做"字节复制 + 试加载"两件事，不做字体子集化 —— 子集化要解析
     * 字体表，代价远超收益（HUD 上最多显示几百个字符，但用户换字体的目的
     * 往往是"整体换个观感"，而不是省体积）。
     *
     * @return 落盘后的文件名；失败抛异常，异常信息直接给用户看
     */
    public static synchronized String install(Context c, Uri src) throws Exception {
        if (src == null) throw new IllegalArgumentException("没有选中文件");

        File tmp = new File(c.getFilesDir(), FILE_NAME + ".tmp");
        long total = 0;
        try (InputStream is = c.getContentResolver().openInputStream(src)) {
            if (is == null) throw new IllegalStateException("读不到这个文件");
            try (OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IllegalStateException("字体文件超过 32MB");
                    }
                    os.write(buf, 0, n);
                }
                os.flush();
            }
        } catch (Exception e) {
            // 失败必须清掉半截文件，否则下次启动会去加载一个残缺字体
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            throw e;
        }

        if (total == 0) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("文件是空的");
        }

        // 先试着加载一遍：加载不了的字体落盘了也没用，反而让条子永久回落
        Typeface probe;
        try {
            probe = Typeface.createFromFile(tmp);
        } catch (Throwable t) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("这不是一个可用的字体文件");
        }
        if (probe == null) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("这不是一个可用的字体文件");
        }

        File dst = file(c);
        try {
            if (dst.exists() && !dst.delete()) {
                // 删不掉也继续尝试覆盖写，部分 ROM 上 rename 能覆盖
            }
            if (!tmp.renameTo(dst)) {
                // rename 失败（跨挂载点之类），退化成复制
                try (OutputStream os = new FileOutputStream(dst)) {
                    try (InputStream is = new java.io.FileInputStream(tmp)) {
                        byte[] buf = new byte[16 * 1024];
                        int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
                tmp.delete();
            }
        } catch (Exception e) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("保存字体失败：" + e.getMessage());
        }

        // 重新加载：文件名没变，靠 key 失效是行不通的，直接清缓存
        sTypeface = null;
        sLoadedKey = "";
        HudState.fontFile = FILE_NAME;
        Prefs.setFontFile(c, FILE_NAME);
        typeface(c);
        return FILE_NAME;
    }

    /** 恢复系统等宽字体 */
    public static synchronized void clear(Context c) {
        try {
            file(c).delete();
        } catch (Exception ignored) {
        }
        sTypeface = null;
        sLoadedKey = "";
        sFailed = false;
        HudState.fontFile = "";
        Prefs.setFontFile(c, "");
    }
}
