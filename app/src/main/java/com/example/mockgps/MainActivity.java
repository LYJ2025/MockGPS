package com.example.mockgps;

import android.Manifest;
import android.content.Intent;
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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.Locale;

/**
 * 虚拟定位助手 —— 主容器。
 *
 * <p>两个左右滑动的页面：
 * <ol>
 *   <li>{@link HomeFragment} 主页：行政区列表（大区 → 省级 → 地级）、收藏夹、虚拟定位开关</li>
 *   <li>{@link MapFragment} 地图选点：整屏地图，点哪儿就定位到哪儿</li>
 * </ol>
 *
 * <p>本 Activity 只负责三件事：权限、需要授权模拟位置的 location 注入引擎，
 * 以及两个页面共享的「当前坐标」状态。
 *
 * <p>注意：使用前必须在系统「开发者选项 → 选择模拟位置信息应用」里选中本 App。
 */
public class MainActivity extends FragmentActivity {

    static final int REQ_LOCATION = 1001;
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
            lat = la;
            lng = ln;
            valid = true;
        }
    }

    private final SharedState state = new SharedState();

    public SharedState getState() {
        return state;
    }

    // ---------------- 定位引擎 ----------------

    private LocationManager locationManager;
    private boolean mockActive = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 持续模拟：按共享状态每秒刷一次，防止被其他 App 覆盖回真实位置 */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (mockActive && state.valid) {
                pushLocation(state.lat, state.lng);
            }
            if (mockActive) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        ViewPager2 pager = findViewById(R.id.pager);
        pager.setAdapter(new PageAdapter(this));
        pager.setOffscreenPageLimit(1);   // 两个页面常驻，切回来不丢地图状态

        TabLayout tabs = findViewById(R.id.tabs_main);
        new TabLayoutMediator(tabs, pager,
                (tab, position) -> tab.setText(position == 0 ? "主页" : "地图选点")
        ).attach();

        requestPermissions();
    }

    private static class PageAdapter extends FragmentStateAdapter {
        PageAdapter(@NonNull FragmentActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return position == 0 ? new HomeFragment() : new MapFragment();
        }

        @Override
        public int getItemCount() {
            return 2;
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
        ensureProvider();
        pushLocation(lat, lng);
        state.set(lat, lng);
        mockActive = true;
        return true;
    }

    public void stopMock() {
        handler.removeCallbacks(tick);
        mockActive = false;
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

    /** 注册并启用测试提供方（只有被选为模拟位置应用时才不会抛异常） */
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

    /** 跳到系统「开发者选项」，抓不到入口时给出手动路径提示 */
    public void openDevSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            toast("找不到入口：请手动进「设置 → 关于手机 → 连点版本号 7 次」开启开发者选项");
        }
    }

    public static String fmt(double v) {
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
            if (f instanceof MapFragment) {
                ((MapFragment) f).refreshMyLocation();
            }
        }
    }
}
