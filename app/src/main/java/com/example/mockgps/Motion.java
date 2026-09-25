package com.example.mockgps;

import android.content.Context;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 动效分层。等级取自 ?attr/integerMotionLevel：
 *   0 = 关   1 = 基础（按压反馈）   2 = 丰富（再列入场）   3 = 全开（再加触感）
 *
 * 为什么要把动效收敛到一个类里：
 *   动效是无障碍与性能争议最大的部分，必须有统一的开关，而不是散落在各处的写死动画。
 *   所有动画都必须先问 level()，等级不够就直接不做 —— 这样「动效=关」是一个真的开关。
 */
public final class Motion {

    private Motion() {
    }

    public static int level(Context c) {
        return ThemeManager.motionLevel(c);
    }

    /** L1 · 按压回弹。返回 false 让点击事件继续传递，不吞掉 onClick。 */
    public static void press(final View v) {
        if (v == null || level(v.getContext()) < 1) {
            return;
        }
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().cancel();
                        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().cancel();
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(140)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /** L2 · 卡片入场（淡入 + 轻微上移）。等级 >= 2 才做。 */
    public static void rise(final View v) {
        rise(v, 0L);
    }

    public static void rise(final View v, long delayMs) {
        if (v == null || level(v.getContext()) < 2) {
            return;
        }
        float dy = 10f * v.getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationY(dy);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** L3 · 触感反馈。等级 >= 3 且用户在设置里开着才会震。 */
    public static void haptic(View v) {
        if (v == null) {
            return;
        }
        Context c = v.getContext();
        if (level(c) < 3 || !ThemeManager.haptics(c)) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            } else {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        } catch (Throwable ignored) {
            // 部分设备未开启触摸振动，忽略即可
        }
    }
}
