package io.github.zuoyexingchen.devicehud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * 开机自启 / 应用更新后自动恢复悬浮条。
 *
 * 注意：Android 14+ 对 BOOT_COMPLETED 启动前台服务有类型限制，
 * specialUse 属于允许范围；但仍然可能被厂商 ROM 拦截，所以这里全部 try 掉，
 * 失败就静默放弃，不打扰用户。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        if (!boot && !updated) return;
        if (!Prefs.autoStart(context)) return;
        if (!Settings.canDrawOverlays(context)) return;

        try {
            context.startForegroundService(new Intent(context, HudService.class));
        } catch (Exception ignored) {
        }
    }
}
