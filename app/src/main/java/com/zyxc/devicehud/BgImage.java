package com.zyxc.devicehud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 悬浮条背景图。
 *
 * <p>条是"很宽很矮"的条带，把整张照片解码进内存再缩放既费内存又没必要。
 * 这里先只读尺寸，算出该显示**哪一块源区域**，用 {@link BitmapRegionDecoder}
 * 只解这一条带，再缩到条带尺寸。
 *
 * <p>两个维度可以独立调：
 * <ul>
 *   <li>{@link #SCALE_FIT 等比缩放}（默认）——整张图等比缩进条内，不裁切</li>
 *   <li>{@link #SCALE_ONE_TO_ONE 1:1}——按原始像素显示，超出的直接裁掉</li>
 *   <li>{@link #SCALE_STRETCH 拉伸铺满}——不保持比例，硬压成条带尺寸</li>
 * </ul>
 * 对齐决定横向取图片的哪一段、以及图片从条的哪个位置开始。
 */
public final class BgImage {

    // ------------------------------------------------------------------ 对齐

    /** 居中（默认）：无视左右留白，取图片中间一段 */
    public static final int ANCHOR_CENTER = 0;
    /** 左对齐屏幕边界：图片铺满整条，取最左一段 */
    public static final int ANCHOR_LEFT_SCREEN = 1;
    /** 左对齐左留白：图片从左留白线开始，左留白区留给背景色 */
    public static final int ANCHOR_LEFT_INSET = 2;
    /** 右对齐屏幕边界：图片铺满整条，取最右一段 */
    public static final int ANCHOR_RIGHT_SCREEN = 3;
    /** 右对齐右留白：图片到右留白线结束，右留白区留给背景色 */
    public static final int ANCHOR_RIGHT_INSET = 4;

    public static final String[] ANCHOR_NAMES = {
            "居中", "左对齐屏幕", "左对齐留白", "右对齐屏幕", "右对齐留白"
    };

    // ------------------------------------------------------------------ 缩放

    /** 等比缩放（默认）：高度铺满、保持比例，左右裁切 */
    public static final int SCALE_FIT = 0;
    /** 1:1：原始像素，一个像素对一个像素，超出的裁掉 */
    public static final int SCALE_ONE_TO_ONE = 1;
    /** 拉伸铺满：横向纵向各自拉，比例不管 */
    public static final int SCALE_STRETCH = 2;

    public static final String[] SCALE_NAMES = {"等比", "1:1", "拉伸"};

    public static final String[] SCALE_DESC = {
            "等比缩放：整张图等比缩到条内，不裁切",
            "1:1 原始像素：超出的部分直接裁掉",
            "拉伸铺满：不保持比例，硬压成条带尺寸"
    };

    private static String sKey = "";
    private static Bitmap sBitmap;

    private BgImage() {
    }

    // ------------------------------------------------------------------ 私有副本

    /**
     * 私有副本文件名：固定，覆盖写，避免越积越多。
     *
     * <p>扩展名不写死成 .png —— 裁剪出来的窄条用 PNG（锐利、体积小），
     * 整张照片用 JPEG（PNG 存照片动辄十几 MB）。解码都走 BitmapFactory，
     * 它按文件头判断格式，不看扩展名。
     */
    private static final String PRIVATE_NAME = "bar_bg.img";

    public static File file(Context c) {
        return new File(c.getFilesDir(), PRIVATE_NAME);
    }

    /** 私有副本的 uri（文件可能还不存在） */
    public static String privateUri(Context c) {
        return Uri.fromFile(file(c)).toString();
    }

    public static boolean hasPrivate(Context c) {
        File f = file(c);
        return f.isFile() && f.length() > 0;
    }

    /**
     * 把位图存成私有副本，返回它的 uri（格式见下方说明）。
     *
     * <p>存进应用私有目录是为了**持久化**：SAF 的持久化授权在重装 APK 后失效，
     * 之前用户选过的图会静默消失（踩过）。
     *
     * @return 成功返回 uri，失败返回 ""
     */
    public static synchronized String savePrivate(Context c, Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return "";
        File f = file(c);
        File tmp = new File(f.getParentFile(), PRIVATE_NAME + ".tmp");

        // 有透明通道必须 PNG；像素量大的照片走 JPEG，否则文件能到十几 MB
        boolean png = bmp.hasAlpha() || (long) bmp.getWidth() * bmp.getHeight() <= 400_000L;
        android.graphics.Bitmap.CompressFormat fmt = png
                ? android.graphics.Bitmap.CompressFormat.PNG
                : android.graphics.Bitmap.CompressFormat.JPEG;
        int quality = png ? 100 : 92;

        try (FileOutputStream os = new FileOutputStream(tmp)) {
            if (!bmp.compress(fmt, quality, os)) return "";
            os.flush();
            os.getFD().sync();
        } catch (Exception e) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            return "";
        }
        // 先写临时文件再改名：中途被杀也不会留下半张图
        try {
            if (f.exists() && !f.delete()) {
                // 某些系统上 delete 失败但 rename 覆盖仍然可以
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
                return "";
            }
        } catch (Exception e) {
            return "";
        }
        sKey = "";
        recycleCache();
        return Uri.fromFile(f).toString();
    }

    /**
     * 把外部 uri 的图整张复制进私有目录（不裁剪）。
     *
     * @param maxDim 长边上限，避免把 8000px 的原图整个解码进内存
     */
    public static String adopt(Context c, Uri src, int maxDim) {
        if (src == null) return "";
        Bitmap b = decodeSource(c, src, maxDim);
        if (b == null) return "";
        String uri = savePrivate(c, b);
        b.recycle();
        return uri;
    }

    /**
     * 旧配置迁移：存的是 content:// 时尝试复制一份到私有目录。
     *
     * @return 迁移后的 uri；不需要迁移或失败时原样返回
     */
    public static synchronized String migrate(Context c, String uriStr) {
        if (uriStr == null || uriStr.isEmpty()) return "";
        if (!uriStr.startsWith("content:")) return uriStr;

        // 迁移只是"把能读的复制一份"，分辨率取 2400 就够上屏用，
        // 别为了保真去解 4096，那一张就要 50MB 内存
        String got = adopt(c, Uri.parse(uriStr), 2400);
        return got.isEmpty() ? uriStr : got;
    }

    public static synchronized void clearPrivate(Context c) {
        try {
            file(c).delete();
        } catch (Exception ignored) {
        }
        sKey = "";
        recycleCache();
    }

    /**
     * 只丢掉引用，不 recycle。
     *
     * <p>悬浮窗的 HudView 可能还握着这张位图，recycle 掉会让它在下一次绘制时
     * 抛 "trying to use a recycled bitmap"（虽然那里有 isRecycled 判断，
     * 但没必要赌时序）。交给 GC 就行。
     */
    private static void recycleCache() {
        sBitmap = null;
    }

    /**
     * 这张图现在能不能读出来。设置页用它给出"读取失败，请重新选择"的提示。
     *
     * <p>结果按 uri + 文件时间戳缓存，避免每次刷新设置页都去开一次流。
     */
    public static synchronized boolean isUsable(Context c, String uriStr) {
        if (uriStr == null || uriStr.isEmpty()) return false;
        Uri u = Uri.parse(uriStr);
        String key = uriStr + "@" + stamp(u);
        if (key.equals(sUsableKey)) return sUsable;

        boolean ok;
        if ("file".equals(u.getScheme())) {
            ok = hasPrivate(c);
        } else {
            try (InputStream is = c.getContentResolver().openInputStream(u)) {
                ok = is != null && is.read() >= 0;
            } catch (Exception e) {
                ok = false;
            }
        }
        sUsableKey = key;
        sUsable = ok;
        return ok;
    }

    private static String sUsableKey = "";
    private static boolean sUsable;

    private static long stamp(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                File f = new File(uri.getPath());
                return (f.lastModified() << 20) ^ f.length();
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    /** 给裁剪界面用：把源图按长边上限采样解码出来（不裁、不缩放到条带） */
    public static Bitmap loadSource(Context c, Uri uri, int maxDim) {
        return decodeSource(c, uri, maxDim);
    }

    private static Bitmap decodeSource(Context c, Uri uri, int maxDim) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            try (InputStream is = c.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, probe);
            }
            int w = probe.outWidth;
            int h = probe.outHeight;
            if (w <= 0 || h <= 0) return null;

            int sample = 1;
            int lim = Math.max(256, maxDim);
            while (Math.max(w, h) / (sample * 2) >= lim) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream is = c.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static int clampAnchor(int a) {
        return (a < 0 || a >= ANCHOR_NAMES.length) ? ANCHOR_CENTER : a;
    }

    public static int clampScale(int s) {
        return (s < 0 || s >= SCALE_NAMES.length) ? SCALE_FIT : s;
    }

    public static String anchorName(int a) {
        return ANCHOR_NAMES[clampAnchor(a)];
    }

    public static String scaleName(int s) {
        return SCALE_NAMES[clampScale(s)];
    }

    /**
     * 图片在条上能占的横向范围 [left, right]（px）。
     *
     * <p>"*留白"两种对齐会把对应那条留白让出来给背景色，图片只排进文字区；
     * 其余三种都是铺满整条。
     */
    public static void box(int anchor, int viewW, int insetL, int insetR, float[] out) {
        float l = 0f;
        float r = viewW;
        switch (clampAnchor(anchor)) {
            case ANCHOR_LEFT_INSET:
                l = Math.min(Math.max(0, insetL), viewW / 2f);
                break;
            case ANCHOR_RIGHT_INSET:
                r = Math.max(viewW - Math.max(0, insetR), viewW / 2f);
                break;
            default:
                break;
        }
        out[0] = l;
        out[1] = r;
    }

    /**
     * 取背景图（带缓存）。参数或 URI 变化才会重新解码。
     *
     * @return 不可用时返回 null。返回的位图**不会大于** {@link #box} 给出的范围，
     *         等比/拉伸模式下正好等于条带可见尺寸，直接铺满即可。
     */
    public static synchronized Bitmap load(Context c, String uriStr,
                                           int viewW, int viewH,
                                           int insetL, int insetR,
                                           int anchor, int scale) {
        if (uriStr == null || uriStr.isEmpty() || viewW <= 0 || viewH <= 0) {
            return null;
        }
        anchor = clampAnchor(anchor);
        scale = clampScale(scale);
        Uri uri = Uri.parse(uriStr);
        // 私有副本的路径是固定的（files/bar_bg.png），覆盖同一个文件时 uri 不变，
        // 所以缓存 key 必须带上修改时间，否则换图之后还显示旧图
        String key = uriStr + "@" + stamp(uri) + "|" + viewW + "x" + viewH
                + "|" + insetL + "," + insetR + "|" + anchor + "|" + scale;
        if (key.equals(sKey)) return sBitmap;

        Bitmap bmp = decode(c, uri, viewW, viewH, insetL, insetR, anchor, scale);
        sKey = key;
        sBitmap = bmp;
        return bmp;
    }

    private static Bitmap decode(Context c, Uri uri, int viewW, int viewH,
                                 int insetL, int insetR, int anchor, int scale) {
        int[] size = bounds(c, uri);
        int srcW = size[0];
        int srcH = size[1];
        if (srcW <= 0 || srcH <= 0) return null;

        float[] b = new float[2];
        box(anchor, viewW, insetL, insetR, b);
        float boxW = Math.max(1f, b[1] - b[0]);
        int dstW = Math.max(1, Math.round(boxW));
        int dstH = Math.max(1, viewH);

        // 拉伸：整张解出来再压扁，反正比例已经不要了，采样到条带宽度量级就够
        if (scale == SCALE_STRETCH) {
            Bitmap whole = decodeFull(c, uri, viewW);
            return whole == null ? null : resize(whole, dstW, dstH);
        }

        // 等比：取两个方向缩放比的较小者 —— 整张图都塞进条内，一像素都不裁
        if (scale == SCALE_FIT) {
            float k = Math.min(viewH / (float) srcH, boxW / (float) srcW);
            int tw = Math.max(1, Math.round(srcW * k));
            int th = Math.max(1, Math.round(srcH * k));
            Bitmap whole = decodeFull(c, uri, tw);
            return whole == null ? null : resize(whole, tw, th);
        }

        // 1:1：不缩放，按原始像素裁一块能放下的窗口
        float k = 1f;

        int visW = Math.min(srcW, Math.max(1, Math.round(boxW / k)));
        int visH = Math.min(srcH, Math.max(1, Math.round(viewH / k)));

        int x0;
        switch (anchor) {
            case ANCHOR_LEFT_SCREEN:
            case ANCHOR_LEFT_INSET:
                x0 = 0;
                break;
            case ANCHOR_RIGHT_SCREEN:
            case ANCHOR_RIGHT_INSET:
                x0 = srcW - visW;
                break;
            default:
                x0 = (srcW - visW) / 2;
                break;
        }
        x0 = Math.max(0, Math.min(srcW - visW, x0));
        int y0 = (srcH - visH) / 2;      // 纵向永远居中

        Bitmap region = decodeRegion(c, uri, new Rect(x0, y0, x0 + visW, y0 + visH));
        if (region == null) {
            // 有些 provider 给的不是可随机访问的流，退回整张 + 采样
            region = decodeFull(c, uri, viewW);
            if (region == null) return null;
            return resize(region, dstW, dstH);
        }

        // 1:1 原样返回，让绘制层按原生尺寸摆放；等比缩到条带尺寸，省内存也更清楚
        if (scale == SCALE_ONE_TO_ONE) return region;
        return resize(region, dstW, dstH);
    }

    /** 缩放到目标尺寸；已经是目标尺寸就直接返回原图 */
    private static Bitmap resize(Bitmap src, int w, int h) {
        if (src == null) return null;
        if (src.getWidth() == w && src.getHeight() == h) return src;
        try {
            Bitmap out = Bitmap.createScaledBitmap(src, w, h, true);
            if (out != src) src.recycle();
            return out;
        } catch (Exception e) {
            return src;
        }
    }

    private static int[] bounds(Context c, Uri uri) {
        int[] out = new int[]{0, 0};
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream is = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, o);
            out[0] = o.outWidth;
            out[1] = o.outHeight;
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Bitmap decodeRegion(Context c, Uri uri, Rect region) {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = c.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return null;
            BitmapRegionDecoder dec = BitmapRegionDecoder.newInstance(pfd.getFileDescriptor(), false);
            if (dec == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = dec.decodeRegion(region, o);
            dec.recycle();
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Bitmap decodeFull(Context c, Uri uri, int targetW) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            try (InputStream is = c.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, probe);
            }
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= targetW) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try (InputStream is = c.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
