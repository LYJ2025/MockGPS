package com.example.mockgps;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 跳转到系统「开发者选项」页面（无需任何无障碍权限）。
 *
 * <p>本应用正常工作前必须在这里的「选择模拟位置信息应用」里选中本 App。
 * 系统不允许应用代为开启 / 勾选，只能把用户送过去手动选 —— 所以这里只做"跳转"。
 */
public final class DevSettings {

    private DevSettings() {
    }

    /** 跳转到系统开发者选项页；个别 ROM 没有该页时回退到应用详情页。 */
    public static void openDevSettings(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            // 极少数 ROM / 车机没有开发者选项入口，回退到应用详情页，
            // 用户从那里也能进到开发者选项（部分系统放在"关于本机"里）。
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.fromParts("package", ctx.getPackageName(), null));
                ctx.startActivity(i);
            } catch (Exception ignored) {
                Toast.makeText(ctx, "未找到开发者选项入口，请手动开启", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
