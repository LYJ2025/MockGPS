package com.example.mockgps;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

/**
 * 外观状态的唯一入口。
 *
 * 职责：
 *   1) 读写 SharedPreferences（文件名 appearance），保存用户选择的方案 / 玻璃 / 透明度 /
 *      圆角 / 密度 / 动效 / 主色 / 深浅 / 触感；
 *   2) 按固定顺序把 ThemeOverlay 叠加到 Activity 的主题上；
 *   3) 提供从主题属性取值的工具方法（颜色 / 尺寸 / 整数 / 布尔）。
 *
 * 叠加顺序（顺序有意义，越靠后越优先）：
 *      Base(Theme.MockGPS) -> Scheme -> SurfaceAlpha -> Radius -> Density -> Motion -> Accent -> Glass
 * 用户手动微调排在 Scheme 之后，否则会被方案的默认值吃掉。
 *
 * 两个必须知道的实现约束：
 *   · 窗口级属性（statusBarColor 等）写成 ThemeOverlay 是没用的 —— 窗口读的是 Activity 自身的
 *     theme。所以状态栏颜色在这里用 window.setStatusBarColor() 显式设置。
 *   · 主题是在 onCreate 里就地叠加的，后续再改必须 recreate()，否则已 inflate 的 View 不会变。
 *     组件（ThemeOverlay）本来就不支持热更新，这是平台限制，不是实现偷懒。
 */
public final class ThemeManager {

    private ThemeManager() {
    }

    public static final String PREFS = "appearance";

    public static final String KEY_SCHEME = "appearance_scheme";
    public static final String KEY_GLASS = "appearance_glass";
    public static final String KEY_ALPHA = "appearance_surface_alpha";
    public static final String KEY_RADIUS = "appearance_radius";
    public static final String KEY_MOTION = "appearance_motion";
    public static final String KEY_ACCENT = "appearance_accent";
    public static final String KEY_DARK = "appearance_dark";
    public static final String KEY_DENSITY = "appearance_density";
    public static final String KEY_HAPTICS = "appearance_haptics";

    /**
     * 主题代号。每次写入设置都 +1。
     * 用途：设置页改完主题后，栈里的 MainActivity 不会自动重建（它不是被 recreate 的那个），
     * 于是返回时看到的还是旧外观。MainActivity 在 onResume 里比对这个代号，
     * 发现变了就自己 recreate()，从而让外观真正生效。
     */
    public static final String KEY_GEN = "appearance_generation";

    /** v1.20 默认值迁移标记（只跑一次，见 migrateDefaultsOnce） */
    private static final String KEY_MIGRATED_119 = "appearance_migrated_119";
    /** v1.20 单选项归位标记：设计风格 / 玻璃强度 / 动效等级 只剩一个选项 */
    private static final String KEY_MIGRATED_FIXED = "appearance_migrated_fixed";

    /**
     * v1.20：外观设置页只保留唯一选项 —— 设计风格 = 液态玻璃（B）、
     * 玻璃强度 = 关（off）、动效等级 = 全开（full）。
     *
     * <p>这三个常量现在同时是「唯一合法值」：不再有第二个选项可选，
     * 历史存过的其它值（A/C、light/medium/strong、off/basic/rich）
     * 由 {@link #migrateDefaultsOnce} 一次性归位。
     */
    public static final String DEF_SCHEME = "B";
    public static final String DEF_GLASS = "off";
    /**
     * 卡片默认不透明度。
     *
     * <p>v1.20 由 0.60 提到 0.80：真机上 0.60（= {@code glass_surface_s60}，60% 白）
     * 叠在浅色地图上仍然明显偏透，「选点 / 轨迹」两张卡片上的文字对比度不够。
     * 0.80 对应 {@code glass_surface_s80}（80% 白），既压得住文字，又能透出一点地图。
     * 用户仍可在「外观设置 → 卡片不透明度」里自由调到 50~95。
     */
    public static final float DEF_ALPHA = 0.80f;
    public static final String DEF_RADIUS = "standard";
    /** v1.20：动效等级只保留「全开」 */
    public static final String DEF_MOTION = "full";
    public static final String DEF_ACCENT = "blue";
    public static final String DEF_DARK = "system";
    public static final String DEF_DENSITY = "standard";
    public static final boolean DEF_HAPTICS = true;

    /** 透明度滑杆的取值范围与步进。0.72 这类不在栅格上的值会被吸附到最近的 0.05。 */
    public static final float ALPHA_MIN = 0.50f;
    public static final float ALPHA_MAX = 0.95f;
    public static final float ALPHA_STEP = 0.05f;

    @NonNull
    public static SharedPreferences prefs(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * v1.20 一次性迁移：把旧的默认不透明度 0.60 抬到新默认 0.80。
     *
     * <p>只迁移「存的值恰好等于旧默认 0.60」这一种情况 —— 那实质上就是没动过滑杆的用户
     * 拿到了旧默认值。用户手动选过的其他档位（0.50~0.95 里非 0.60 的）一律不动。</p>
     *
     * <p>必须做成"只跑一次"：否则用户哪天主动把滑杆拉到 60%，下次启动又会被悄悄改回去。</p>
     */
    public static void migrateDefaultsOnce(@NonNull Context c) {
        try {
            SharedPreferences sp = prefs(c);

            // ---- 迁移 1：不透明度默认值 0.60 → 0.80 ----
            if (!sp.getBoolean(KEY_MIGRATED_119, false)) {
                // 默认值传 -1：没存过就得到 -1，不会误判成"等于旧默认"
                if (Math.abs(sp.getFloat(KEY_ALPHA, -1f) - 0.60f) < 1e-4f) {
                    sp.edit().putFloat(KEY_ALPHA, DEF_ALPHA).apply();
                }
                sp.edit().putBoolean(KEY_MIGRATED_119, true).apply();
            }

            // ---- 迁移 2：设计风格 / 玻璃强度 / 动效等级已固定为唯一选项 ----
            // 选项只剩一个，历史存过的其它值（A/C、light/medium/strong、off/basic/rich）
            // 已经没有任何入口能改回去，直接归位到唯一合法值。
            if (!sp.getBoolean(KEY_MIGRATED_FIXED, false)) {
                sp.edit()
                        .putString(KEY_SCHEME, DEF_SCHEME)
                        .putString(KEY_GLASS, DEF_GLASS)
                        .putString(KEY_MOTION, DEF_MOTION)
                        .putBoolean(KEY_MIGRATED_FIXED, true)
                        .apply();
            }
        } catch (Throwable ignored) {
            // 迁移失败无所谓：最坏是继续用旧值，不影响任何功能
        }
    }

    // ==================================================================
    // 一、读取当前设置（带默认值兜底）
    // ==================================================================

    @NonNull
    public static String scheme(@NonNull Context c) {
        return prefs(c).getString(KEY_SCHEME, DEF_SCHEME);
    }

    @NonNull
    public static String glass(@NonNull Context c) {
        return prefs(c).getString(KEY_GLASS, DEF_GLASS);
    }

    public static float surfaceAlpha(@NonNull Context c) {
        return prefs(c).getFloat(KEY_ALPHA, DEF_ALPHA);
    }

    @NonNull
    public static String radius(@NonNull Context c) {
        return prefs(c).getString(KEY_RADIUS, DEF_RADIUS);
    }

    @NonNull
    public static String motion(@NonNull Context c) {
        return prefs(c).getString(KEY_MOTION, DEF_MOTION);
    }

    @NonNull
    public static String accent(@NonNull Context c) {
        return prefs(c).getString(KEY_ACCENT, DEF_ACCENT);
    }

    @NonNull
    public static String darkMode(@NonNull Context c) {
        return prefs(c).getString(KEY_DARK, DEF_DARK);
    }

    @NonNull
    public static String density(@NonNull Context c) {
        return prefs(c).getString(KEY_DENSITY, DEF_DENSITY);
    }

    public static boolean haptics(@NonNull Context c) {
        return prefs(c).getBoolean(KEY_HAPTICS, DEF_HAPTICS);
    }

    // ==================================================================
    // 二、写入设置
    // ==================================================================

    public static void put(@NonNull Context c, @NonNull String key, String value) {
        prefs(c).edit().putString(key, value).putInt(KEY_GEN, generation(c) + 1).apply();
    }

    public static void putAlpha(@NonNull Context c, float value) {
        prefs(c).edit().putFloat(KEY_ALPHA, snapAlpha(value))
                .putInt(KEY_GEN, generation(c) + 1).apply();
    }

    public static void putHaptics(@NonNull Context c, boolean value) {
        prefs(c).edit().putBoolean(KEY_HAPTICS, value)
                .putInt(KEY_GEN, generation(c) + 1).apply();
    }

    /** 当前设置代号，用于判断「设置是否变过，需要重建界面」 */
    public static int generation(@NonNull Context c) {
        return prefs(c).getInt(KEY_GEN, 0);
    }

    /** 把任意透明度吸附到 0.50~0.95 的 0.05 栅格上 */
    public static float snapAlpha(float a) {
        float v = Math.round(a / ALPHA_STEP) * ALPHA_STEP;
        if (v < ALPHA_MIN) v = ALPHA_MIN;
        if (v > ALPHA_MAX) v = ALPHA_MAX;
        // 消除浮点累积误差（0.70000005 -> 0.70）
        return Math.round(v * 100f) / 100f;
    }

    /**
     * 把外观设置全部清空，回到默认值。
     *
     * <p><b>v1.20：当前没有任何 UI 调用它。</b>外观设置页上的「恢复默认外观」按钮已被移除 ——
     * 设计风格 / 玻璃强度 / 动效等级都只剩唯一选项，"恢复默认"已经没有语义了。
     * 方法本身保留为公共入口：将来若重新开放可选方案，直接接回去即可。
     */
    public static void resetAll(@NonNull Context c) {
        prefs(c).edit().clear().apply();
    }

    // ==================================================================
    // 三、把主题叠加上去
    // ==================================================================

    /**
     * 在 Activity.onCreate 里、super.onCreate 之前调用。
     * 就地修改 Activity 的主题，之后 inflate 的 View（含 Fragment 里的）都会取到新值。
     *
     * 为什么要在 super.onCreate 之前：AppCompat 会在 super.onCreate 里应用深浅模式，
     * 这时候把 setDefaultNightMode 设好，首次创建就能一次到位，不会先建错再重建。
     * 注意这里不碰窗口颜色 —— 窗口属性等 setContentView 之后再设更稳。
     */
    public static void setupActivity(@NonNull Activity a) {
        applyNightMode(a);
        Resources.Theme theme = a.getTheme();
        // Base 已由 AndroidManifest 的 android:theme 指定，这里只叠 Overlay
        theme.applyStyle(styleForScheme(scheme(a)), true);
        theme.applyStyle(styleForAlpha(surfaceAlpha(a)), true);
        theme.applyStyle(styleForRadius(radius(a)), true);
        theme.applyStyle(styleForDensity(density(a)), true);
        theme.applyStyle(styleForMotion(motion(a)), true);
        theme.applyStyle(styleForAccent(accent(a)), true);
        theme.applyStyle(styleForGlass(glass(a)), true);
    }

    /** 深浅模式：跟随系统 / 强制浅色 / 强制深色。需要 AppCompatActivity 才会生效。 */
    public static void applyNightMode(@NonNull Context c) {
        String mode = darkMode(c);
        int target;
        if ("light".equals(mode)) {
            target = AppCompatDelegate.MODE_NIGHT_NO;
        } else if ("dark".equals(mode)) {
            target = AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            target = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target);
        }
    }

    /**
     * 状态栏 / 导航栏跟随页面风格。
     *
     * <p>主题是「玻璃顶栏 + 色斑背景」之后，状态栏颜色改用「半透明浅色」，
     * 与玻璃顶栏视觉融合（而不是「顶栏白 + 状态栏纯色」这种割裂）。
     * <ul>
     *   <li>状态栏背景：半透明白（alpha 0.5）—— 让下面的色斑能透上来；</li>
     *   <li>状态栏文字：暗色 —— 在浅色半透明上能看清；</li>
     *   <li>导航栏背景：与页面底色一致（避免「白页 + 黑条」）。</li>
     * </ul>
     * 必须在 setContentView 之后调用。
     */
    public static void applyWindowColors(@NonNull Activity a) {
        try {
            android.view.Window w = a.getWindow();
            // 状态栏背景：浅色模式 = 50% 透明白（与玻璃顶栏同源），
            // 深色模式 = 50% 透明深色（与深色玻璃顶栏同源）。
            // 系统会忽略低于 30 的 alpha，所以低位机退化为纯色，但视觉与顶栏一致。
            boolean dark = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
                    || (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        && (a.getResources().getConfiguration().uiMode
                            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            == android.content.res.Configuration.UI_MODE_NIGHT_YES);
            w.setStatusBarColor(dark ? 0x80101418 : 0x80FFFFFF);
            w.setNavigationBarColor(color(a, android.R.attr.colorBackground));

            androidx.core.view.WindowInsetsControllerCompat ctl =
                    new androidx.core.view.WindowInsetsControllerCompat(w, w.getDecorView());
            // 浅色状态栏背景上要暗色文字；深色背景上要浅色文字
            ctl.setAppearanceLightStatusBars(!dark);
            ctl.setAppearanceLightNavigationBars(!dark);
        } catch (Throwable ignored) {
            // 某些 ROM 会拒绝设置状态栏颜色，不影响功能
        }
    }

    // ---------------- 枚举值 -> 样式资源 ----------------

    private static int styleForScheme(String v) {
        if ("B".equals(v)) return R.style.ThemeOverlay_MockGPS_SchemeB;
        if ("C".equals(v)) return R.style.ThemeOverlay_MockGPS_SchemeC;
        return R.style.ThemeOverlay_MockGPS_SchemeA;
    }

    private static int styleForGlass(String v) {
        if ("off".equals(v)) return R.style.ThemeOverlay_MockGPS_Glass_Off;
        if ("light".equals(v)) return R.style.ThemeOverlay_MockGPS_Glass_Light;
        if ("strong".equals(v)) return R.style.ThemeOverlay_MockGPS_Glass_Strong;
        return R.style.ThemeOverlay_MockGPS_Glass_Medium;
    }

    private static int styleForRadius(String v) {
        if ("compact".equals(v)) return R.style.ThemeOverlay_MockGPS_Radius_Compact;
        if ("round".equals(v)) return R.style.ThemeOverlay_MockGPS_Radius_Round;
        return R.style.ThemeOverlay_MockGPS_Radius_Standard;
    }

    private static int styleForDensity(String v) {
        if ("compact".equals(v)) return R.style.ThemeOverlay_MockGPS_Density_Compact;
        if ("comfortable".equals(v)) return R.style.ThemeOverlay_MockGPS_Density_Comfortable;
        return R.style.ThemeOverlay_MockGPS_Density_Standard;
    }

    private static int styleForMotion(String v) {
        if ("off".equals(v)) return R.style.ThemeOverlay_MockGPS_Motion_Off;
        if ("basic".equals(v)) return R.style.ThemeOverlay_MockGPS_Motion_Basic;
        if ("full".equals(v)) return R.style.ThemeOverlay_MockGPS_Motion_Full;
        return R.style.ThemeOverlay_MockGPS_Motion_Rich;
    }

    private static int styleForAccent(String v) {
        if ("teal".equals(v)) return R.style.ThemeOverlay_MockGPS_Accent_Teal;
        if ("purple".equals(v)) return R.style.ThemeOverlay_MockGPS_Accent_Purple;
        if ("green".equals(v)) return R.style.ThemeOverlay_MockGPS_Accent_Green;
        if ("amber".equals(v)) return R.style.ThemeOverlay_MockGPS_Accent_Amber;
        return R.style.ThemeOverlay_MockGPS_Accent_Blue;
    }

    /** 透明度 -> glass_surface_sNN 对应的 Overlay */
    private static int styleForAlpha(float a) {
        int step = Math.round(snapAlpha(a) * 100f);
        switch (step) {
            case 50: return R.style.ThemeOverlay_MockGPS_SurfaceAlpha_s50;
            case 55: return R.style.ThemeOverlay_MockGPS_SurfaceAlpha_s55;
            case 60: return R.style.ThemeOverlay_MockGPS_SurfaceAlpha_s60;
            case 65: return R.style.ThemeOverlay_MockGPS_SurfaceAlpha_s65;
            case 70: return R.style.ThemeOverlay_MockGPS_SurfaceAlpha_s70;
            case 75: return R.style.ThemeOverlay_MockGPS_SurfaceAlpha_s75;
            case 80: return R.style.ThemeOverlay_MockGPS_SurfaceAlpha_s80;
            case 85: return R.style.ThemeOverlay_MockGPS_SurfaceAlpha_s85;
            case 90: return R.style.ThemeOverlay_MockGPS_SurfaceAlpha_s90;
            case 95: return R.style.ThemeOverlay_MockGPS_SurfaceAlpha_s95;
            default: return R.style.ThemeOverlay_MockGPS_SurfaceAlpha_s70;
        }
    }

    // ==================================================================
    // 四、从主题属性取值
    // 统一走 ContextCompat.getColor —— Context.getColor(int) 需要 API 23，
    // 而本工程 minSdk 是 21，直接调会在 21/22 上抛 NoSuchMethodError。
    // ==================================================================

    private static boolean resolve(@NonNull Context c, @AttrRes int attr, @NonNull TypedValue out) {
        return c.getTheme().resolveAttribute(attr, out, true);
    }

    @ColorInt
    public static int color(@NonNull Context c, @AttrRes int attr) {
        TypedValue tv = new TypedValue();
        if (!resolve(c, attr, tv)) return 0;
        if (tv.resourceId != 0) return ContextCompat.getColor(c, tv.resourceId);
        return tv.data;
    }

    public static int intValue(@NonNull Context c, @AttrRes int attr, int def) {
        TypedValue tv = new TypedValue();
        if (!resolve(c, attr, tv)) return def;
        if (tv.type == TypedValue.TYPE_INT_DEC || tv.type == TypedValue.TYPE_INT_HEX) return tv.data;
        if (tv.resourceId != 0) {
            try {
                return c.getResources().getInteger(tv.resourceId);
            } catch (Throwable ignored) {
                return def;
            }
        }
        return def;
    }

    public static boolean bool(@NonNull Context c, @AttrRes int attr, boolean def) {
        TypedValue tv = new TypedValue();
        if (!resolve(c, attr, tv)) return def;
        if (tv.type == TypedValue.TYPE_INT_BOOLEAN) return tv.data != 0;
        if (tv.resourceId != 0) {
            try {
                return c.getResources().getBoolean(tv.resourceId);
            } catch (Throwable ignored) {
                return def;
            }
        }
        return def;
    }

    public static float dimen(@NonNull Context c, @AttrRes int attr, float def) {
        TypedValue tv = new TypedValue();
        if (!resolve(c, attr, tv)) return def;
        try {
            return tv.getDimension(c.getResources().getDisplayMetrics());
        } catch (Throwable ignored) {
            return def;
        }
    }

    // ==================================================================
    // 五、设计参数的语义化读取（View 与效果类用这些，不直接碰 R.attr）
    // ==================================================================

    /** 玻璃模糊半径（dp）。0 = 关闭真实模糊，只保留半透明。 */
    public static int glassBlurDp(@NonNull Context c) {
        return Math.max(0, intValue(c, R.attr.integerGlassBlur, 16));
    }

    /** 是否绘制玻璃内高光（方案 B 为 true） */
    public static boolean glassSpecular(@NonNull Context c) {
        return bool(c, R.attr.booleanGlassSpecular, false);
    }

    /** 是否对玻璃做动态染色（方案 B 为 true） */
    public static boolean glassDynamicTint(@NonNull Context c) {
        return bool(c, R.attr.booleanGlassDynamicTint, false);
    }

    /** 动效等级：0 关 / 1 基础 / 2 丰富 / 3 全开 */
    public static int motionLevel(@NonNull Context c) {
        return intValue(c, R.attr.integerMotionLevel, 2);
    }

    /** 当前设计方案：0 柔光 / 1 液态 / 2 动效优先 */
    public static int designScheme(@NonNull Context c) {
        return intValue(c, R.attr.enumDesignScheme, 0);
    }

    /** 卡片内边距（密度预设的落点） */
    public static float cardPaddingPx(@NonNull Context c) {
        return dimen(c, R.attr.dimenCardPadding, 16f);
    }

    /**
     * 把「坐标设置」这类卡片补上顶部内高光。
     * 为什么不在 XML 里做：MaterialCardView 的背景由它自己的 MaterialShapeDrawable 掌管，
     * 再写 android:background 会被覆盖，两套机制打架。所以高光用叠加 View 的方式补。
     */
    public static void applySpecularHighlight(@NonNull View container) {
        if (!glassSpecular(container.getContext())) return;
        View line = container.findViewById(R.id.glass_highlight_overlay);
        if (line == null) {
            line = new View(container.getContext());
            line.setId(R.id.glass_highlight_overlay);
            line.setBackgroundColor(color(container.getContext(), R.attr.colorGlassHighlight));
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            Math.max(1, (int) container.getResources().getDisplayMetrics().density));
            line.setLayoutParams(lp);
            if (container instanceof android.widget.FrameLayout) {
                ((android.widget.FrameLayout) container).addView(line);
            }
        }
    }

    /** 版本号展示：始终与 build.gradle 的 versionName 一致，不再写死在布局里 */
    @NonNull
    public static String versionLabel() {
        return "v" + BuildConfig.VERSION_NAME;
    }

    /** 供调试：把当前设置拼成一行 */
    @NonNull
    public static String describe(@NonNull Context c) {
        return "方案" + scheme(c) + " / 玻璃" + glass(c) + " / 透明" + surfaceAlpha(c)
                + " / 圆角" + radius(c) + " / 密度" + density(c) + " / 动效" + motion(c)
                + " / 主色" + accent(c) + " / 深浅" + darkMode(c)
                + (Build.VERSION.SDK_INT >= 31 ? " / 真模糊" : " / 低版本降级模糊");
    }
}
