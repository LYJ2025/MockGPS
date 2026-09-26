package com.example.mockgps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 虚拟定位助手 —— 主容器。
 *
 * <p>三个页面（顶部 Tab 点击切换，关闭左右滑动以防手势误触）：
 * <ol>
 *   <li>{@link HomeFragment} 主页：行政区列表（大区 → 省级 → 地级）、收藏夹、虚拟定位开关</li>
 *   <li>{@link PickFragment} 选点地图：整屏地图，点哪儿就定位到哪儿</li>
 *   <li>{@link TrackFragment} 轨迹模拟地图：手绘 / 折线规划路线并沿其模拟，显示真实轨迹</li>
 * </ol>
 *
 * <p>本 Activity 只负责三件事：权限、需要授权模拟位置的 location 注入引擎，
 * 以及两个页面共享的「当前坐标」状态。
 *
 * <p>注意：使用前必须在系统「开发者选项 → 选择模拟位置信息应用」里选中本 App。
 */
public class MainActivity extends AppCompatActivity {

    static final int REQ_LOCATION = 1001;

    /** 开屏教程弹窗：每个进程最多自动弹一次（转屏重建不算新进程）。 */
    private static boolean tutorialShownThisProcess;
    private static final String[] PERMS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };

    /** 虚拟定位通过覆盖系统 gps 提供方实现，绝大多数地图 / GPS 类 App 都监听该提供方 */
    private static final String PROVIDER = LocationManager.GPS_PROVIDER;

    // ---------------- 页面间共享的状态 ----------------

    /** 当前已选定的目标坐标（WGS-84） */
    static class SharedState {
        double lat = Double.NaN;
        double lng = Double.NaN;
        boolean valid = false;

        void set(double la, double ln) {
            // 防御：绝不让 NaN / Infinity 进入共享状态。
            //
            // 一旦进来就会自我循环：syncFromState() 把它渲染成字符串 "NaN" 写进输入框，
            // 而 "NaN" 能被 Double.parseDouble 解析回 NaN（不抛异常），
            // 且 NaN 参与的任何 < / > 比较都是 false —— 于是它能绕过全部范围校验，
            // 把坐标永久污染。真机反馈的"经纬度均显示 NaN"就是这个链条。
            if (!Double.isFinite(la) || !Double.isFinite(ln)) {
                return;
            }
            lat = la;
            lng = ln;
            valid = true;
        }

        /**
         * 坐标是否<b>真的</b>可用：{@code valid} 且两个分量都是有限值。
         *
         * <p>判断"能不能拿去用"必须用这个，不能只看 {@code valid} ——
         * {@code valid} 是个独立字段，曾经在 {@code startTrack()} 里被直接赋成 true，
         * 而此时 lat/lng 还是初始的 {@code Double.NaN}。
         * 后果：轨迹跑起来后点「清空」→ {@code isRunning()} 变 false → tick 落到
         * {@code else if (state.valid)} 分支 → 把 NaN 推给系统定位 → 在 vivo 定制 ROM 上直接崩
         * （它的 Location 加密逻辑会对 "NaN" 调 {@code Integer.parseInt}）。
         */
        boolean isUsable() {
            return valid && Double.isFinite(lat) && Double.isFinite(lng);
        }
    }

    private final SharedState state = new SharedState();

    /**
     * 最近一次推出去的轨迹点。
     *
     * <p>切页回来时用它把地图上的「播放蓝点」摆回原处 —— 那个 Marker 属于页面，
     * 视图重建后就没了；而 TrackEngine 也取不到"当前走到哪"（{@code computeNext} 会推进进度）。
     */
    private TrackEngine.TrackPoint lastTrackPoint;

    public SharedState getState() {
        return state;
    }

    /** 最近一次推送的轨迹点；没在跑轨迹时为 null。 */
    public TrackEngine.TrackPoint getLastTrackPoint() {
        return lastTrackPoint;
    }

    // ---------------- 轨迹模拟（A 线） ----------------

    private final TrackEngine trackEngine = new TrackEngine();
    /** true：mock 引擎当前在跑轨迹；false：普通固定点模拟 */
    private boolean trackMode = false;
    private TabLayout mTabs; // 顶部三页分页栏：轨迹模拟运行中禁用其点击切换

    /**
     * 实际推出去的轨迹点（含抖动），跨页面共享。
     *
     * <p>存在 Activity 而不是 Fragment 里：轨迹页画它、选点页也要能看它，
     * 且切换页面 / 转屏后不丢。由 {@link TrackEngine} 每 tick 追加。
     */
    private final List<TrackEngine.TrackPoint> sharedTrail = new ArrayList<>();

    /** 轨迹列表变化的监听（轨迹页 / 选点页据此重绘绿线）。 */
    public interface TrailListener {
        void onTrailChanged();
    }

    private final List<TrailListener> trailListeners = new ArrayList<>();

    public void addTrailListener(TrailListener l) {
        if (l != null && !trailListeners.contains(l)) trailListeners.add(l);
    }

    public void removeTrailListener(TrailListener l) {
        trailListeners.remove(l);
    }

    public List<TrackEngine.TrackPoint> getSharedTrail() {
        return sharedTrail;
    }

    public void clearSharedTrail() {
        sharedTrail.clear();
        notifyTrailChanged();
    }

    private void notifyTrailChanged() {
        for (TrailListener l : new ArrayList<>(trailListeners)) {
            try {
                l.onTrailChanged();
            } catch (Throwable t) {
                // 单个画面重绘失败绝不能拖垮整个应用。
                //
                // 典型场景（真机崩溃就是这个）：轨迹模拟运行中切页 —— ViewPager2 会销毁
                // 离屏页的 Fragment，而轨迹 tick 挂在 Activity 上照跑不误；回调打到"视图
                // 已销毁"的 Fragment 时，osmdroid 的 Polyline 内部 LinearRing 已被
                // MapView.onDetach() 清空，setPoints() 直接抛 NullPointerException。
                //
                // 各 Fragment 里也做了"视图还在不在"的检查，这里是最后一道防线：
                // 漏掉一个也不该闪退。
                android.util.Log.w("MainActivity", "trail listener dropped", t);
            }
        }
    }

    public void setTrackListener(TrackEngine.Listener l) {
        trackEngine.setListener(l);
    }

    public TrackEngine getTrackEngine() {
        return trackEngine;
    }

    public boolean isTrackMode() {
        return trackMode;
    }

    /** 开始轨迹模拟（speedKmh = 目标配速 km/h） */
    public boolean startTrack(double kmh) {
        if (!trackEngine.canStart()) return false;
        ensureProvider();
        trackMode = true;
        // 注意：这里**不能**再写 state.valid = true。
        // 轨迹模拟推的是 trackEngine 算出来的点，与"已选虚拟坐标"无关；
        // 而 valid 是独立字段，置真后若 lat/lng 仍是初始 NaN，轨迹一停
        // tick 就会把 NaN 推给系统定位（真机崩溃就是这么来的）。
        mockActive = true;
        clearSharedTrail();          // 清掉上一次的绿线，避免叠加
        trackEngine.start(kmh);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
        refreshNavLock();   // 运行中 → 禁用一切页面切换
        return true;
    }

    public void stopTrack() {
        trackMode = false;
        mockActive = false;
        trackEngine.stop();
        handler.removeCallbacks(tick);
        try {
            locationManager.setTestProviderEnabled(PROVIDER, false);
        } catch (SecurityException ignored) {
        }
        refreshNavLock();   // 已停止 → 恢复页面切换
    }

    // ---------------- 轨迹「开始 / 停止 / 继续」三态 ----------------

    /**
     * 暂停轨迹模拟：<b>保留进度</b>，之后可以继续。
     *
     * <p>注意 tick 不停 —— 它会在 {@code trackMode && lastTrackPoint != null} 分支里
     * 把最后一点反复推出去，让虚拟位置停在原地（不推的话位置会被真实的/已选的坐标接管）。
     */
    public void pauseTrack() {
        trackEngine.pause();
        refreshNavLock();   // 暂停 → 恢复页面切换
    }

    /** 从暂停处继续。已经跑完（finished）时什么也不做。 */
    public void resumeTrack() {
        trackEngine.resume();
        if (trackEngine.isRunning()) {
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, 1000);
        }
        refreshNavLock();   // 继续 → 重新禁用页面切换
    }

    /**
     * 作废轨迹进度：按钮回到「开始」，位置也不再被轨迹钉住。
     *
     * <p>用于「清空」，以及路线被改动（撤销 / 新增节点）而当前又处于暂停态的时候 ——
     * 那时 {@code distM} 已经和新的路线对不上，继续跑只会错乱。
     */
    public void clearTrackProgress() {
        lastTrackPoint = null;
        trackEngine.stop();     // running = false, finished = true → 按钮回「开始」
        refreshNavLock();       // 停止 → 恢复页面切换
    }

    /**
     * 轨迹模拟「进行中」时禁用一切页面切换，其余状态恢复正常。
     *
     * <p>实现要点：本 App 的左右滑动切换早已关闭（{@code setUserInputEnabled(false)}），
     * 能切页的唯一入口就是顶部 Tab 点击。所以这里在锁定态给每个 Tab 的视图挂一个
     * {@code OnTouchListener} 并返回 {@code true} 把触摸事件直接吞掉 —— 点击不会传导到
     * TabLayout，分页根本切不动（点非当前页时顺带提示）。解锁态移除监听、恢复可点。
     *
     * <p>相比「先切走再 setCurrentItem 锁回」，吞事件是从源头禁止，不会出现
     * 「只弹了提示、页面却切走了」的漏网，也没有重入回调导致递归闪退的风险。
     */
    private void refreshNavLock() {
        if (mTabs == null) return;
        boolean locked = trackEngine.isRunning();
        int count = mTabs.getTabCount();
        for (int i = 0; i < count; i++) {
            final TabLayout.Tab tab = mTabs.getTabAt(i);
            if (tab == null) continue;
            final View tabView = tab.view;
            if (tabView == null) continue;
            final int idx = i;
            if (locked) {
                tabView.setOnTouchListener((v, e) -> {
                    if (e.getAction() == MotionEvent.ACTION_DOWN
                            && mTabs.getSelectedTabPosition() != idx) {
                        Toast.makeText(MainActivity.this, "请先停止模拟轨迹",
                                Toast.LENGTH_SHORT).show();
                    }
                    return true; // 吞掉触摸：运行中禁止任何分页切换
                });
                tabView.setClickable(false);
            } else {
                tabView.setOnTouchListener(null);
                tabView.setClickable(true);
            }
        }
    }

    // ---------------- 定位引擎 ----------------

    private LocationManager locationManager;
    private boolean mockActive = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 持续模拟：按共享状态每秒刷一次，防止被其他 App 覆盖回真实位置 */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (mockActive) {
                long now = SystemClock.elapsedRealtimeNanos();
                if (trackMode && trackEngine.isRunning()) {
                    TrackEngine.TrackPoint p = trackEngine.computeNext(now);
                    if (p != null) {
                        // 坐标必须是有限值才往里放：NaN / Infinity 一旦进了 sharedTrail，
                        // 会被经纬度换算原样传下去，最后在 osmdroid 里画成异常点位。
                        if (Double.isFinite(p.lat) && Double.isFinite(p.lng)) {
                            pushTrackPoint(p, now);
                            lastTrackPoint = p;      // 供切页回来恢复播放蓝点
                            // 同步到跨页面共享的实际轨迹，供地图页画绿线
                            sharedTrail.add(new TrackEngine.TrackPoint(p.lat, p.lng));
                            notifyTrailChanged();
                        }
                    }
                } else if (trackMode && lastTrackPoint != null) {
                    // 轨迹「暂停中」或「已跑完停在终点」：把最后一点原样再推一次，
                    // 让位置钉在原地。绝不能落到下面的 state 分支 —— 那会让位置跳到
                    // 已选的虚拟坐标上，看起来像"一暂停就跑掉了"。
                    pushTrackPoint(lastTrackPoint, now);
                } else if (state.isUsable()) {
                    // 用 isUsable() 而不是 valid：valid 可能为真、而坐标还是初始 NaN
                    pushLocation(state.lat, state.lng);
                }
                handler.postDelayed(this, 1000);
            }
        }
    };

    /**
     * 本页创建时所处的「主题代号」。从外观设置页返回时，如果代号变了，
     * 说明用户在那边改了外观，本页需要 recreate() 才能让新主题生效。
     * 原因：ThemeOverlay 是编译期资源，已 inflate 的 View 不会自己更新。
     */
    private int appliedThemeGeneration = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v1.20 一次性默认值迁移。必须排在 setupActivity 读取设置之前，
        // 否则本次启动拿到的仍是旧的 0.60 默认值，要等下次启动才生效。
        ThemeManager.migrateDefaultsOnce(this);

        // 主题必须在 super.onCreate 之前叠好：
        // AppCompat 会在 super.onCreate 里应用深浅模式，提前设好可避免「先建错再重建」。
        ThemeManager.setupActivity(this);
        super.onCreate(savedInstanceState);
        // 崩溃记录：把未捕获异常的完整堆栈写进
        // /sdcard/Android/data/com.example.mockgps/files/crash_last.txt
        // 「切到轨迹页闪退」这类问题无法在开发机复现，靠它拿真实堆栈定位。
        CrashLogger.install(this);
        DiagLog.init(this);

        try {
            setContentView(R.layout.activity_main);
            ThemeManager.applyWindowColors(this);
            appliedThemeGeneration = ThemeManager.generation(this);

            // 版本号从构建配置读，不再写死在布局里（原来布局写 v1.17，而 build.gradle 是 1.19）
            TextView tvVersion = findViewById(R.id.tv_version);
            tvVersion.setText(ThemeManager.versionLabel());

            // 外观设置入口（标题栏右侧）
            ImageButton btnAppearance = findViewById(R.id.btn_appearance);
            btnAppearance.setOnClickListener(v -> {
                Motion.haptic(v);
                startActivity(new Intent(MainActivity.this, AppearanceActivity.class));
            });
            Motion.press(btnAppearance);

            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

            ViewPager2 pager = findViewById(R.id.pager);
            pager.setAdapter(new PageAdapter(this));
            // offscreenPageLimit=1：只常驻相邻一页。
            // 之前设为 2（三页全常驻）会让两张地图同时存在于视图树里，
            // 退到后台再回来时非当前页的 MapView 生命周期与 osmdroid 内部状态错配 → 整屏白图。
            pager.setOffscreenPageLimit(1);
            pager.setUserInputEnabled(false); // 关闭左右滑动翻页：避免地图手势误触发翻页，改用顶部 Tab 点击切换

            mTabs = findViewById(R.id.tabs_main);
            new TabLayoutMediator(mTabs, pager,
                    (tab, position) -> tab.setText(
                            position == 0 ? "主页" : position == 1 ? "选点" : "轨迹")
            ).attach();

            // 轨迹模拟「进行中」时禁用一切页面切换：直接吞掉顶部 Tab 的点击触摸（切都切不动），
            // 而不是「先切走再锁回」——后者在 TabLayout+ViewPager2 点击链路上不可靠，会照样切走。
            // 注：左右滑动早已被禁（见上方 setUserInputEnabled(false)），唯一入口就是 Tab 点击。
            // 暂停 / 停止 / 结束后 isRunning() 自动变 false，自动恢复，无需额外恢复逻辑。
            refreshNavLock();

            requestPermissions();

            // 检查上次闪退日志，如果存在则弹窗提示用户分享给我
            checkLastCrashAndPrompt();

            // 开屏检测：尚未在「开发者选项 → 模拟位置信息应用」里选中本 App 时，
            // 自动弹出开启教程 GIF（每个进程最多一次，转屏重建不会重复弹）。
            if (!tutorialShownThisProcess && !isMockLocationGranted()) {
                tutorialShownThisProcess = true;
                DevTutorialDialog.showTutorial(this, true);
            }
        } catch (Throwable t) {
            // 任何一个步骤失败都不应让应用挂掉 —— CrashLogger 已经在 install 时挂上了
            // Thread.setDefaultUncaughtExceptionHandler，这条 catch 主要是让 logcat 能看见
            // 上下文（"onCreate 阶段"），方便定位。
            android.util.Log.e("MainActivity", "onCreate swallowed", t);
        }
    }

    /**
     * 检查上次崩溃日志：
     * <ul>
     *   <li>主位置（应用专属目录）—— 总是存在；</li>
     *   <li>备位置（公共下载目录）—— Android 10+ 通过 MediaStore 写入，文件名固定 crash_last.txt。</li>
     * </ul>
     * 如果有上次未处理的崩溃日志（mtime > 上次标记"已处理"的时间），弹窗。
     * 任何按钮（分享/复制/忽略）都会调 markHandled 写时间戳，下次不再弹。
     * —— 这样可以**完全关掉弹窗循环**，不会每次冷启动都重弹。
     */
    private void checkLastCrashAndPrompt() {
        try {
            File f = CrashLogger.lastCrashFile(this);
            if (f == null || !f.exists() || f.length() < 50) return;
            long mtime = f.lastModified();
            long handled = CrashLogger.lastHandledTime(this);
            if (handled > 0 && mtime <= handled) {
                // 用户上次已经看过/处理过这个崩溃，不重复打扰
                return;
            }

            String content = readFile(f);
            if (content == null) return;

            // 弹窗：复制 / 分享 / 忽略 —— 任何按钮都会 markHandled，下次不弹
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("检测到上次闪退记录")
                    .setMessage("已自动写入日志到：\n" + f.getAbsolutePath()
                            + "\n\n（Android 10+ 同时写到了「Download/MockGPS-crash/crash_last.txt」）\n\n"
                            + "可以一键复制或分享给开发者，帮你定位问题。\n\n"
                            + "点任一按钮后会记住，**下次启动不再弹**；直到下次再有新崩溃为止。")
                    .setPositiveButton("分享", (d, w) -> {
                        CrashLogger.markHandled(this);
                        shareText(content);
                    })
                    .setNeutralButton("复制", (d, w) -> {
                        CrashLogger.markHandled(this);
                        copyToClipboard(content);
                    })
                    .setNegativeButton("忽略", (d, w) -> CrashLogger.markHandled(this))
                    .setCancelable(false)
                    .show();
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "checkLastCrashAndPrompt failed", t);
        }
    }

    private String readFile(File f) {
        try {
            byte[] data = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            }
            return new String(data, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("MockGPS 崩溃日志", text));
            android.widget.Toast.makeText(this,
                    "已复制到剪贴板（粘贴到聊天框发我即可）",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "复制失败：" + t.getMessage(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void shareText(String text) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, "MockGPS 崩溃日志：\n\n" + text);
            startActivity(Intent.createChooser(send, "分享崩溃日志"));
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "分享失败：" + t.getMessage(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNavLock(); // 按当前引擎状态同步导航锁（如运行中从别处返回本页）
        // 从外观设置页返回：设置变过就重建自己，让新外观真正生效
        if (appliedThemeGeneration >= 0
                && appliedThemeGeneration != ThemeManager.generation(this)) {
            recreate();
        }
    }

    private static class PageAdapter extends FragmentStateAdapter {
        PageAdapter(@NonNull FragmentActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (position == 0) return new HomeFragment();
            if (position == 1) return new PickFragment();
            return new TrackFragment();
        }

        @Override
        public int getItemCount() {
            return 3;
        }
    }

    // ---------------- 虚拟定位 ----------------

    /**
     * 应用虚拟定位。
     *
     * @return 是否成功；false 表示权限不足 / 坐标非法
     */
    public boolean applyMock(double lat, double lng) {
        if (!hasLocationPermission()) {
            requestPermissions();
            return false;
        }
        if (Double.isNaN(lat) || Double.isNaN(lng)
                || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return false;
        }
        trackMode = false;
        trackEngine.stop();
        ensureProvider();
        pushLocation(lat, lng);
        state.set(lat, lng);
        mockActive = true;
        return true;
    }

    public void stopMock() {
        handler.removeCallbacks(tick);
        mockActive = false;
        trackMode = false;
        trackEngine.stop();
        try {
            locationManager.setTestProviderEnabled(PROVIDER, false);
        } catch (SecurityException ignored) {
        }
        try {
            locationManager.removeTestProvider(PROVIDER);
        } catch (IllegalArgumentException ignored) {
        }
    }

    public boolean isMockActive() {
        return mockActive;
    }

    /** 开关「持续模拟」 */
    public void setContinuous(boolean on) {
        handler.removeCallbacks(tick);
        if (on && mockActive) {
            handler.postDelayed(tick, 1000);
        }
    }

    /**
     * 注册并启用测试提供方（只有被选为模拟位置应用时才不会抛异常）
     *
     * <p>@SuppressLint("WrongConstant") 的理由：lint 认为这两个参数只接受
     * {@code ProviderProperties.POWER_USAGE_*} / {@code ProviderProperties.ACCURACY_*}，
     * 而 AOSP 文档给的可选值恰恰是 {@code Criteria.POWER_*} / {@code Criteria.ACCURACY_*}。
     * 两者数值完全一致（已用 javap -constants 核对 android-34 的 android.jar）：
     * Criteria.POWER_LOW = 1 = ProviderProperties.POWER_USAGE_LOW，
     * Criteria.ACCURACY_FINE = 1 = ProviderProperties.ACCURACY_FINE。
     * 所以这是 lint 的误报，不是真问题。
     */
    @SuppressLint("WrongConstant")
    private void ensureProvider() {
        try {
            locationManager.addTestProvider(PROVIDER, false, false, false, false,
                    true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        } catch (Exception ignored) {
            // 已存在 / 未授权，统一在下一步给出提示
        }
        try {
            locationManager.setTestProviderEnabled(PROVIDER, true);
        } catch (SecurityException e) {
            toast("请在「开发者选项 → 选择模拟位置信息应用」中选中本应用");
        }
    }

    private void pushLocation(double lat, double lng) {
        // 最后一道防线：绝不让非有限值进入 Location。
        //
        // vivo 定制 ROM 在 setTestProviderLocation 的链路里会对 Location 加密，
        // 内部把字段转字符串再 Integer.parseInt —— 拿到 "NaN" 就抛
        // IllegalArgumentException（而且是在 system_server 里抛，我们只收到 RemoteException）。
        // 上游已经尽量挡住了，这里再兜一次：宁可这一帧不推，也不能崩。
        if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
            return;
        }
        try {
            Location loc = new Location(PROVIDER);
            loc.setLatitude(lat);
            loc.setLongitude(lng);
            loc.setAltitude(0);
            loc.setAccuracy(1.0f);
            loc.setTime(System.currentTimeMillis());
            loc.setBearing(0);
            loc.setSpeed(0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            }
            locationManager.setTestProviderLocation(PROVIDER, loc);
        } catch (SecurityException e) {
            toast("模拟定位被拒绝：请先在开发者选项中授权本应用");
        }
    }

    /** 推送轨迹插值点：含速度、航向、精度抖动、假卫星数，更逼近真实 GPS。 */
    private void pushTrackPoint(TrackEngine.TrackPoint p, long now) {
        if (!Double.isFinite(p.lat) || !Double.isFinite(p.lng)) {
            return;
        }
        try {
            Location loc = new Location(PROVIDER);
            loc.setLatitude(p.lat);
            loc.setLongitude(p.lng);
            loc.setAltitude(0);                 // 平地恒定海拔
            // accuracy / speed / bearing 也必须是有限值：TrackEngine 里但凡出现除零，
            // 这几个 float 就可能是 NaN；而 Location.toString() 会把它们渲染成 "NaN"，
            // 同样会踩到 vivo ROM 那个 Integer.parseInt（见 pushLocation 的说明）。
            loc.setAccuracy(safeFloat(p.accuracy, 5f));
            loc.setSpeed(safeFloat(p.speed, 0f));
            loc.setBearing(safeFloat(p.bearing, 0f));
            loc.setTime(System.currentTimeMillis());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                loc.setElapsedRealtimeNanos(now);
            }
            Bundle extras = new Bundle();
            extras.putInt("satellites", p.satellites);
            loc.setExtras(extras);
            locationManager.setTestProviderLocation(PROVIDER, loc);
        } catch (SecurityException e) {
            toast("模拟定位被拒绝：请先在开发者选项中授权本应用");
        }
    }

    /** 把非有限的 float 换成兜底值（NaN / ±Infinity 都会让 vivo ROM 的 Location 加密崩）。 */
    private static float safeFloat(float v, float fallback) {
        return Float.isFinite(v) ? v : fallback;
    }

    // ---------------- 辅助 ----------------

    public LocationManager getLocationManager() {
        return locationManager;
    }

    public boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void requestPermissions() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this, PERMS, REQ_LOCATION);
        }
    }

    /** 跳转到系统「开发者选项」页面（无需任何无障碍权限）。 */
    public void openDevSettings() {
        DevSettings.openDevSettings(this);
    }

    /**
     * 检测本 App 是否已被选为「模拟位置信息应用」。
     * 通过 AppOps 的 android:mock_location 判定，minSdk 21 即可用（字符串形式，不依赖隐藏 API）。
     */
    public boolean isMockLocationGranted() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode = aom.checkOpNoThrow("android:mock_location",
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static String fmt(double v) {
        if (!Double.isFinite(v)) return "";
        return String.format(Locale.US, "%.6f", v);
    }

    public void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 权限变化后通知地图页刷新真实位置蓝点
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof BaseMapFragment) {
                ((BaseMapFragment) f).refreshMyLocation();
            }
        }
    }
}
