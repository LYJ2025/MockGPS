package com.example.mockgps;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;

/**
 * 「模拟位置开启教程」弹窗。
 *
 * <p>标题固定为 {@code R.string.dev_tutorial_title}（"开发者选项-模拟定位 开启教程"），
 * 居中播放 {@code assets/dev_tutorial.gif}，并提供「关闭」按钮；按需附带「前往开发者选项」按钮。
 * 开屏检测未授权时自动弹出一次，设置页也有手动按钮复用本方法。
 */
public final class DevTutorialDialog {

    private DevTutorialDialog() {
    }

    /**
     * @param ctx          任意 Context（建议 Activity）
     * @param showDevButton 是否在弹窗里额外提供「前往开发者选项」按钮
     */
    public static void showTutorial(Context ctx, boolean showDevButton) {
        if (ctx == null) {
            return;
        }
        GifView gif = new GifView(ctx);
        gif.loadAsset("dev_tutorial.gif");

        // 限制 GIF 最大宽度：手机上随弹窗宽度，平板上不超过 360dp，避免把弹窗撑爆
        float density = ctx.getResources().getDisplayMetrics().density;
        int marginPx = (int) (16 * density);
        int capPx = (int) (360 * density);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int maxW = Math.max(200, Math.min(screenW - 2 * marginPx, capPx));
        gif.setMaxWidth(maxW);

        int pad = (int) (12 * density);
        FrameLayout wrap = new FrameLayout(ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad, pad, pad);
        gif.setLayoutParams(lp);
        wrap.addView(gif);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle(R.string.dev_tutorial_title)
                .setView(wrap);

        if (showDevButton) {
            b.setPositiveButton("前往开发者选项", (d, w) -> DevSettings.openDevSettings(ctx));
        }
        b.setNegativeButton("关闭", null);
        b.show();
    }
}
