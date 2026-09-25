package com.example.mockgps;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.Locale;

/**
 * 外观设置页。
 *
 * 设计要点：
 *   1) 选项分组全部由 res/values/arrays.xml 里的 appearance_*_labels/_values 生成，
 *      选项定义只有一处 —— 以后加方案 / 换预设只改 arrays.xml，不动这里的代码。
 *   2) 任何改动都写入 SharedPreferences 并 recreate() 本页。
 *      ThemeOverlay 是编译期资源，运行时无法局部刷新，重建是平台唯一正解。
 *      为了让栈里的 MainActivity 也跟上，ThemeManager 维护了「主题代号」，
 *      MainActivity 在 onResume 里比对后自行重建（见 ThemeManager.KEY_GEN）。
 *   3) 透明度滑杆用整数刻度（50~95，步进 5）再除以 100：
 *      Material Slider 对浮点 stepSize 的整除性校验很敏感（0.95-0.5 在 float 下算出来是
 *      0.44999998，会直接抛异常），整数刻度可以彻底绕开。
 */
public class AppearanceActivity extends AppCompatActivity {

    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.setupActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appearance);
        ThemeManager.applyWindowColors(this);

        content = findViewById(R.id.appearance_content);
        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(v -> finish());
        Motion.press(back);

        buildGroups();
    }

    // v1.20：标题栏右侧原本显示当前方案（"液态玻璃"），已按要求移除 ——
    // 方案固定后这行字没有信息量，只是占地方。

    // ==================================================================
    // 分组构建
    // ==================================================================

    private void buildGroups() {
        content.removeAllViews();

        // v1.20：设计风格 / 玻璃强度 / 动效等级 各自都已固定为唯一取值，
        // 没有任何东西可选 —— 所以这**_三张卡片整组不再出现在设置页里_**
        // （先前只是"保留只读展示 + 去掉按钮"，用户要求连卡片一起去掉）。
        // 它们的当前取值固定在 ThemeManager.DEF_SCHEME / DEF_GLASS / DEF_MOTION，
        // 并已由 migrateDefaultsOnce() 把历史值归位。

        addAlphaSlider();

        addChoice("圆角", R.array.appearance_radius_labels, R.array.appearance_radius_values,
                ThemeManager.KEY_RADIUS, ThemeManager.DEF_RADIUS,
                "同时作用于卡片、按钮与底部弹层");

        addChoice("密度", R.array.appearance_density_labels, R.array.appearance_density_values,
                ThemeManager.KEY_DENSITY, ThemeManager.DEF_DENSITY,
                "调整卡片内边距，控制一屏能放多少内容");

        addChoice("主色预设", R.array.appearance_accent_labels, R.array.appearance_accent_values,
                ThemeManager.KEY_ACCENT, ThemeManager.DEF_ACCENT,
                "标题栏、选中态、按钮、开关会一起跟着变");

        addChoice("深浅模式", R.array.appearance_dark_labels, R.array.appearance_dark_values,
                ThemeManager.KEY_DARK, ThemeManager.DEF_DARK,
                "跟随系统 / 强制浅色 / 强制深色");

        addHapticsSwitch();
        // v1.20：已移除「恢复默认外观（方案 A）」按钮 —— 方案已固定，没有"恢复"的语义了

        addTutorialButton();
    }

    /** 「开发者选项-模拟定位 开启教程」手动入口：重新弹出开屏那个 GIF 教程。 */
    private void addTutorialButton() {
        MaterialCardView card = newCard();
        LinearLayout box = newBody(card);
        box.addView(cardTitle("模拟位置开启教程"));
        box.addView(cardHint("若未开启「开发者选项 → 模拟位置信息应用」，本应用无法工作。"
                + "点下方按钮可再次查看图文教程。"));

        MaterialButton btn = new MaterialButton(this);
        btn.setText(R.string.dev_tutorial_title);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        btn.setLayoutParams(blp);
        btn.setOnClickListener(v -> {
            Motion.haptic(v);
            DevTutorialDialog.showTutorial(this, true);
        });
        box.addView(btn);
        content.addView(card);
        Motion.rise(card);
    }

    /** 一组单选：标题 + 说明 + 分段按钮 */
    private void addChoice(String title, int labelsRes, int valuesRes,
                           final String key, String def, String hint) {
        MaterialCardView card = newCard();
        LinearLayout box = newBody(card);

        box.addView(cardTitle(title));
        box.addView(cardHint(hint));

        final String[] labels = getResources().getStringArray(labelsRes);
        final String[] values = getResources().getStringArray(valuesRes);
        final String now = ThemeManager.prefs(this).getString(key, def);

        MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(this);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(8);
        group.setLayoutParams(glp);

        int base = 1000 + Math.abs(key.hashCode() % 1000);
        int checkedId = View.NO_ID;
        int n = Math.min(labels.length, values.length);
        for (int i = 0; i < n; i++) {
            MaterialButton b = (MaterialButton) getLayoutInflater()
                    .inflate(R.layout.item_segmented_button, group, false);
            b.setId(base + i);
            b.setText(labels[i]);
            group.addView(b);
            if (values[i].equalsIgnoreCase(now)) {
                checkedId = base + i;
            }
            final String value = values[i];
            b.setOnClickListener(v -> {
                ThemeManager.put(this, key, value);
                Motion.haptic(v);
                recreate();
            });
        }
        box.addView(group);
        // check() 必须在按钮都加进 group 之后调用
        if (checkedId != View.NO_ID) {
            group.check(checkedId);
        }

        content.addView(card);
        Motion.rise(card);
    }

    /** 玻璃透明度滑杆（整数刻度 50~95，步进 5） */
    private void addAlphaSlider() {
        MaterialCardView card = newCard();
        LinearLayout box = newBody(card);

        box.addView(cardTitle("玻璃透明度"));
        box.addView(cardHint("染色层的浓淡。数值越大越接近实心卡片，越小越透"));

        final TextView value = cardHint(String.format(Locale.CHINA, "%.0f%%",
                ThemeManager.surfaceAlpha(this) * 100f));
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        value.setTextColor(ThemeManager.color(this, R.attr.colorPrimary));
        box.addView(value);

        Slider slider = new Slider(this);
        slider.setValueFrom(50f);
        slider.setValueTo(95f);
        slider.setStepSize(5f);
        slider.setValue(Math.round(ThemeManager.surfaceAlpha(this) * 100f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        slider.setLayoutParams(slp);
        slider.addOnChangeListener((s, v, fromUser) ->
                value.setText(String.format(Locale.CHINA, "%.0f%%", v)));
        // 拖动结束才写入并重建：中途每动一下就重建，界面会被打断得没法用
        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider s) {
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider s) {
                float a = ThemeManager.snapAlpha(s.getValue() / 100f);
                if (Math.abs(a - ThemeManager.surfaceAlpha(AppearanceActivity.this)) > 0.001f) {
                    ThemeManager.putAlpha(AppearanceActivity.this, a);
                    recreate();
                }
            }
        });
        box.addView(slider);
        content.addView(card);
    }

    /** 触感反馈开关 */
    private void addHapticsSwitch() {
        MaterialCardView card = newCard();
        LinearLayout box = newBody(card);
        box.addView(cardTitle("触感反馈"));
        box.addView(cardHint("动效等级为「全开」时，点击按钮会有轻微振动"));

        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setChecked(ThemeManager.haptics(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        sw.setLayoutParams(lp);
        sw.setOnCheckedChangeListener((b, checked) -> {
            ThemeManager.putHaptics(this, checked);
            Motion.haptic(b);
        });
        box.addView(sw);
        content.addView(card);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private MaterialCardView newCard() {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout newBody(MaterialCardView card) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) ThemeManager.cardPaddingPx(this);
        box.setPadding(pad, pad, pad, pad);
        card.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private TextView cardTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(ThemeManager.color(this, R.attr.colorOnSurface));
        return t;
    }

    private TextView cardHint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        t.setTextColor(ThemeManager.color(this, R.attr.colorOnSurfaceVariant));
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
