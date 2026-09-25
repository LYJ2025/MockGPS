package com.example.mockgps;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;

/**
 * 玻璃卡片的染色层。
 *
 * <p><b>v1.20 重做</b>。之前的实现被真机反馈暴露出两个硬伤，都已从根上换掉：
 *
 * <ol>
 *   <li><b>卡片被强制设成完全透明</b> —— 原 {@code setupTint()} 里有一句
 *       {@code card.setCardBackgroundColor(0x00000000)}，把样式里配好的
 *       {@code ?attr/colorGlassSurface} 覆盖掉了。结果卡片的可见遮罩只剩染色图里的
 *       「30% 白 + 20% 主色」，在浅色地图上几乎全透。这正是用户看到的
 *       <b>「选点 / 轨迹的窗口过于透明」</b>。现在恢复为尊重
 *       {@code ?attr/colorGlassSurface}（由「卡片不透明度」滑杆的 s50~s95 斜坡控制）。</li>
 *
 *   <li><b>用内嵌 ImageView 当染色载体</b> —— ImageView 要铺满卡片只能用
 *       {@code match_parent}，而未确定高度的 {@code wrap_content} 容器里，
 *       {@code match_parent} 子 View 会被当成「父可用高度」来量，把卡片顶成满屏。
 *       为了压住它，原实现在 Java 里把 ImageView 的 LayoutParams <b>钉死</b>成当时的高宽；
 *       可一旦钉死，卡片高度就再也不能收缩了 —— 这是
 *       <b>「点折叠箭头只是把按钮隐藏、窗口并没有缩小」</b>的根因。
 *       现在改用 <b>{@code background}</b> 承载染色：drawable 完全不参与测量，
 *       卡片高度永远由内容决定，折叠/展开自然生效。</li>
 * </ol>
 *
 * <p>顺带删掉了原来的 Bitmap + {@code RenderEffect} 模糊那套。它画的内容是
 * 「纯白 + 纯主色」两层 {@code SRC_OVER} 填充，<b>对纯色做高斯模糊在视觉上等价于同一个纯色</b>，
 * 等于白算一遍，还额外常驻一张 1/4 尺寸的 {@code ARGB_8888} 位图和一个 400ms 定时器。
 * 现在直接用一个带 alpha 的纯色 drawable 表达，观感一致、开销归零。
 *
 * <p><b>挂载契约</b>：布局里需要一个容器 View 承接染色背景（约定 id 为
 * {@code @+id/glass_backdrop}，实际就是卡片内那一层 FrameLayout）；
 * 它向上查找最近的 {@link MaterialCardView} 作为卡片。
 */
public final class GlassBackdrop {

    /** 染色层的主色占比：只给玻璃一点冷调色感，遮罩主体交给卡片底色 */
    private static final int WASH_ALPHA = 0x14;          // ≈ 8%
    /** 方案 B（液态玻璃）「动态染色」开启时的主色占比：色感更明显 */
    private static final int WASH_ALPHA_DYNAMIC = 0x2E;  // ≈ 18%

    private final MaterialCardView card;
    private final View host;

    private GlassBackdrop(@NonNull MaterialCardView card, @NonNull View host) {
        this.card = card;
        this.host = host;
    }

    /**
     * 挂上玻璃染色。
     *
     * @return 玻璃强度=关（{@code glassBlurDp <= 0}）时返回 null，卡片退回纯半透明；
     *         任何异常同样返回 null —— 染色只是观感增强，挂不上也不该影响功能。
     */
    @Nullable
    public static GlassBackdrop attach(@NonNull MaterialCardView card, @NonNull View host) {
        if (ThemeManager.glassBlurDp(host.getContext()) <= 0) {
            return null;
        }
        GlassBackdrop bd = new GlassBackdrop(card, host);
        try {
            bd.apply();
        } catch (Throwable t) {
            return null;
        }
        return bd;
    }

    /** 重新计算并应用染色（主色 / 深浅模式变化后调用）。 */
    public void apply() {
        Context c = card.getContext();

        // 1) 卡片底色：尊重「卡片不透明度」滑杆。这是遮罩的主体，不能再被覆盖成透明。
        card.setCardBackgroundColor(ThemeManager.color(c, R.attr.colorGlassSurface));

        // 2) 玻璃色感：一层很淡的主色叠在卡片底色之上，让玻璃带一点冷调而不是死白。
        //    方案 B（液态玻璃）打开「动态染色」时主色更重，玻璃上的颜色感更明显。
        int alpha = ThemeManager.glassDynamicTint(c) ? WASH_ALPHA_DYNAMIC : WASH_ALPHA;
        GradientDrawable wash = new GradientDrawable();
        int primary = ThemeManager.color(c, R.attr.colorPrimary);
        wash.setColor((primary & 0x00FFFFFF) | (alpha << 24));
        host.setBackground(wash);
    }
}
