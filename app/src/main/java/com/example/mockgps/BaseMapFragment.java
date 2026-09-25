package com.example.mockgps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.ScaleBarOverlay;

import java.io.File;
import java.util.Locale;

/**
 * 地图页基类：封装「选点」与「轨迹」两个地图页共用的地图基础设施。
 *
 * <p>坐标系：App 对内对外统一用 WGS-84；高德瓦片是 GCJ-02，所以「地图上的点是 GCJ-02」，
 * 写入系统 / 画线时要与 WGS-84 互转（见 {@link CoordUtil}）。
 *
 * <p>子类只需实现 {@link #onMapTap}（单击地图的行为）与 {@link #onCenterClicked()}（回到定位按钮），
 * 并在 {@link #addExtraOverlays()} 里加入自己的图层（选点标记 / 轨迹线 / 画笔 overlay 等）。
 */
public abstract class BaseMapFragment extends Fragment {

    /**
     * 支持 {x}{y}{z} 占位符的瓦片源（osmdroid 内置 XYTileSource 只认 /z/x/y 形式）。
     *
     * <p><b>关于"强行放大"</b>：本类只负责「声明可服务到 z{@link #DECLARED_MAX_Z}」，
     * 让 osmdroid 允许用户缩放到该层级。真正把超出源真实上限（高德 = z{@link #AMAP_REAL_MAX_Z}）
     * 的部分画成"裁剪放大"的逻辑在 {@link ScalingTilesOverlay}，那里会在超限层级上
     * 取 z18 祖先瓦片的对应 1/2^k 区域来绘制 —— 而不是把这整张 z18 图重复铺满每一格。
     */
    private static class TemplateTileSource extends OnlineTileSourceBase {
        /** 声明可服务的最大层级（含超限的裁剪放大部分）。 */
        static final int DECLARED_MAX_Z = 22;

        TemplateTileSource(String name, int min, int max, int tileSize, String ext, String[] urls) {
            super(name, min, max, tileSize, ext, urls);
        }

        @NonNull
        @Override
        public String getTileURLString(long pMapTileIndex) {
            int z = MapTileIndex.getZoom(pMapTileIndex);
            int x = MapTileIndex.getX(pMapTileIndex);
            int y = MapTileIndex.getY(pMapTileIndex);
            return getBaseUrl()
                    .replace("{x}", Integer.toString(x))
                    .replace("{y}", Integer.toString(y))
                    .replace("{z}", Integer.toString(z));
        }
    }

    /** 高德矢量路网（GCJ-02，国内可直连，无需 Key）真正提供瓦片的最大层级。 */
    protected static final int AMAP_REAL_MAX_Z = 18;

    /** 高德瓦片边长（像素）。 */
    private static final int AMAP_TILE_SIZE = 256;

    /**
     * 高德矢量路网（GCJ-02，国内可直连，无需 Key）。
     * 真实瓦片到 z{@value #AMAP_REAL_MAX_Z}，z19~z22 由 {@link ScalingTilesOverlay} 裁剪放大绘制。
     */
    protected static final OnlineTileSourceBase SRC_AMAP =
            new TemplateTileSource("AMap", 3, TemplateTileSource.DECLARED_MAX_Z, AMAP_TILE_SIZE, ".png",
                    new String[]{
                    "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                    "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                    "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                    "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}"});

    /** 高德卫星影像（GCJ-02），真实瓦片同样到 z18，更高层级同样走裁剪放大。 */
    protected static final OnlineTileSourceBase SRC_SAT =
            new TemplateTileSource("AMapSat", 3, TemplateTileSource.DECLARED_MAX_Z, AMAP_TILE_SIZE, ".png",
                    new String[]{
                    "https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}",
                    "https://webst02.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}",
                    "https://webst03.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}",
                    "https://webst04.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}"});

    protected MapView mapView;
    protected Marker realMarker;   // 真实 GPS 蓝点

    /**
     * 替换掉 osmdroid 默认瓦片层的那个 overlay，负责 z19~z22 的「裁剪放大」。
     *
     * <p>持有引用只为一件事：切换地图源（高德 ⇄ 卫星）时清掉它的源瓦片副本缓存
     * —— 缓存按瓦片索引存，换了图源同一个索引就是完全不同的图。
     */
    private ScalingTilesOverlay scalingTiles;

    /**
     * 上一次视图销毁时的地图中心（GCJ-02 —— 直接就是 MapView 用的坐标系），按子类类名缓存。
     *
     * <p>每次 onCreateView 都是新的 MapView，中心默认是世界原点 (0,0)（几内亚湾的海面）。
     * 记下来、在下次 setupMap 时恢复，切页回来才不会跳位置、也不会看着像白屏。
     * 用「静态表 + 子类类名」而非实例字段：ViewPager2 切页时 Fragment 可能被彻底销毁后重建，
     * 实例字段会随实例一起没了、中心退回原点；静态缓存跨重建仍可用，切页 / 转屏回来都不跳。
     */
    private static final java.util.Map<String, IGeoPoint> SAVED_CENTERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private boolean realUpdatesOn = false;
    protected boolean centeredOnReal = false;

    protected MainActivity activity() {
        return (MainActivity) requireActivity();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initOsmdroid();
    }

    private void initOsmdroid() {
        File base = new File(requireContext().getCacheDir(), "osmdroid");
        File tiles = new File(base, "tiles");
        base.mkdirs();
        tiles.mkdirs();
        IConfigurationProvider cfg = Configuration.getInstance();
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(tiles);
        cfg.load(requireContext().getApplicationContext(),
                requireContext().getSharedPreferences("osmdroid", 0));
        cfg.setUserAgentValue("com.example.mockgps");
    }

    /**
     * 子类在 onCreateView 中先 inflate 布局，再调用本方法完成地图通用初始化。
     *
     * <p>整个方法套 try/catch 是 v1.20 修复：用户报告"点选点 / 轨迹 tab 闪退"。
     * osmdroid 6.1.18 的 MapView 在 onCreateView 阶段尚未 attach 到 window，
     * 任何 setTileSource / setMultiTouchControls / addOverlays 调用都有可能
     * 在某些 ROM 上触发 IllegalStateException。这里把每个关键步骤单独
     * try/catch，保证一个步骤失败不会让整张地图黑掉 / 让 Fragment 崩掉。
     */
    protected void setupMap(View v) {
        try {
            mapView = v.findViewById(R.id.map);
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "findViewById map failed", t);
            return;
        }
        if (mapView == null) {
            return;
        }
        // ===== 段 1：基础设置 =====
        try {
            mapView.setTileSource(SRC_AMAP);
            mapView.setBuiltInZoomControls(false);
            mapView.setMultiTouchControls(true);
            mapView.setTilesScaledToDpi(false);
            mapView.setMinZoomLevel(3.0);
            mapView.setHorizontalMapRepetitionEnabled(false);
            mapView.setVerticalMapRepetitionEnabled(false);
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "mapView basic set failed", t);
        }

        // ===== 段 2：overlay 替换 =====
        try {
            if (mapView.getOverlayManager() != null && mapView.getTileProvider() != null) {
                scalingTiles = new ScalingTilesOverlay(mapView.getTileProvider(), requireContext(),
                        AMAP_REAL_MAX_Z, AMAP_TILE_SIZE, false, false);
                mapView.getOverlayManager().setTilesOverlay(scalingTiles);
            }
            applyZoomLimit(SRC_AMAP);
            mapView.getController().setZoom(16.0);
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "overlay replace failed", t);
        }

        // ===== 段 3：比例尺 =====
        try {
            ScaleBarOverlay scaleBar = new ScaleBarOverlay(mapView);
            scaleBar.setUnitsOfMeasure(ScaleBarOverlay.UnitsOfMeasure.metric);
            scaleBar.setAlignBottom(true);
            int pad = (int) (10 * getResources().getDisplayMetrics().density);
            scaleBar.setScaleBarOffset(pad, pad);
            mapView.getOverlays().add(scaleBar);
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "scaleBar add failed", t);
        }

        // ===== 段 4：真实位置蓝点 =====
        try {
            realMarker = new Marker(mapView);
            realMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            realMarker.setTitle("我的位置");
            realMarker.setVisible(false);
            Drawable dot = ContextCompat.getDrawable(requireContext(), R.drawable.ic_my_location);
            if (dot != null) realMarker.setIcon(dot);
            mapView.getOverlays().add(realMarker);
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "realMarker add failed", t);
        }

        // ===== 段 5：子类自己的图层 =====
        try {
            addExtraOverlays();
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "addExtraOverlays failed", t);
        }

        // ===== 段 6：单击事件 =====
        try {
            MapEventsOverlay events = new MapEventsOverlay(new MapEventsReceiver() {
                @Override
                public boolean singleTapConfirmedHelper(GeoPoint p) {
                    onMapTap(p);
                    return true;
                }

                @Override
                public boolean longPressHelper(GeoPoint p) {
                    return false;
                }
            });
            mapView.getOverlays().add(events);
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "MapEventsOverlay add failed", t);
        }

        // ===== 段 7：地图源切换 + 缩放/居中按钮 =====
        try {
            MaterialButtonToggleGroup toggle = v.findViewById(R.id.toggle_map);
            if (toggle != null) {
                toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                    if (!isChecked) return;
                    switchTileSource(checkedId == R.id.src_sat ? SRC_SAT : SRC_AMAP);
                });
            }
            ImageButton btnZoomIn = v.findViewById(R.id.btn_zoom_in);
            ImageButton btnZoomOut = v.findViewById(R.id.btn_zoom_out);
            ImageButton btnCenter = v.findViewById(R.id.btn_center_sel);
            if (btnZoomIn != null) btnZoomIn.setOnClickListener(x -> {
                double z = Math.min(mapView.getZoomLevelDouble() + 0.25,
                        mapView.getMaxZoomLevel());
                mapView.getController().setZoom(z);
            });
            if (btnZoomOut != null) btnZoomOut.setOnClickListener(x -> {
                double z = Math.max(mapView.getZoomLevelDouble() - 0.25,
                        mapView.getMinZoomLevel());
                mapView.getController().setZoom(z);
            });
            if (btnCenter != null) btnCenter.setOnClickListener(x -> onCenterClicked());
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "toggle/zoom button bind failed", t);
        }

        // ===== 段 8：真实位置订阅 =====
        try {
            enableRealLocation();
            centerOnRealIfIdle(true);
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "enableRealLocation failed", t);
        }

        // ===== 段 9：MapView 兜底 resume + 恢复/确定地图中心 =====
        try {
            mapView.onResume();
            mapView.invalidate();

            // 每次 onCreateView 都会新建一个 MapView，而它的中心默认是世界原点 (0,0) ——
            // 那是几内亚湾的海洋，屏幕上就是一片空白，看起来像"地图是白的"（真机反馈）。
            // 这里按优先级把中心摆到有内容的地方：
            //   ① 上次这个页面的地图中心 —— 切页回来不跳位置；
            //   ② 已选坐标 —— 用户真正关心的点；
            //   ③ 真实位置（centerOnRealIfIdle 内部有守卫，没有可用位置时会自己返回）。
            IGeoPoint restored = SAVED_CENTERS.get(getClass().getName());
            if (restored != null) {
                mapView.getController().setCenter(restored);
            } else {
                MainActivity.SharedState st = activity().getState();
                if (st.valid && Double.isFinite(st.lat) && Double.isFinite(st.lng)) {
                    mapView.getController().setCenter(toMapPoint(st.lat, st.lng));
                } else {
                    centerOnRealIfIdle(false);
                }
            }
            IGeoPoint c = mapView.getMapCenter();
            DiagLog.d("BaseMapFragment.setupMap " + getClass().getSimpleName()
                    + " mapW=" + mapView.getWidth() + " mapH=" + mapView.getHeight()
                    + " centerRestored=" + (restored != null)
                    + " center=" + (c != null ? c.getLatitude() + "," + c.getLongitude() : "null"));
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "mapView onResume failed", t);
        }

        // ===== 段 10：玻璃底图 =====
        try {
            setupGlass(v);
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "setupGlass swallowed", t);
        }
    }

    /**
     * 给底部那张玻璃卡片挂上「玻璃染色层」。
     *
     * <p>找不到 glass_backdrop 容器、或外层不是 MaterialCardView 时静默跳过 ——
     * 染色只是观感增强，缺了它卡片仍然是正常的半透明玻璃。
     *
     * <p>v1.20 改：不再需要监听地图 scroll/zoom 做重绘 —— 染色是一层静态纯色，
     * 与地图内容无关（原来那套"抓地图快照"的实现已被删除，见 GlassBackdrop 类注释）。
     */
    private void setupGlass(View root) {
        View host = root.findViewById(R.id.glass_backdrop);
        if (host == null) {
            return;
        }
        // 向上找最近的 MaterialCardView 作为挂载对象
        MaterialCardView card = null;
        ViewParent p = host.getParent();
        while (p instanceof View) {
            if (p instanceof MaterialCardView) {
                card = (MaterialCardView) p;
                break;
            }
            p = ((View) p).getParent();
        }
        if (card == null) {
            return;
        }
        GlassBackdrop.attach(card, host);
    }

    /** 子类追加自己的图层（在比例尺 / 真实蓝点之后、单击事件之前加入）。 */
    protected void addExtraOverlays() {
    }

    /** 单击地图（子类实现：选点页→定位；轨迹页→加节点）。 */
    protected abstract void onMapTap(GeoPoint p);

    /** “回到定位”按钮（子类实现）。 */
    protected abstract void onCenterClicked();

    /** 把 WGS-84 坐标显示到地图上（先转 GCJ-02）。 */
    protected GeoPoint toMapPoint(double lat, double lng) {
        double[] g = CoordUtil.wgs84ToGcj02(lat, lng);
        return new GeoPoint(g[0], g[1]);
    }

    protected void switchTileSource(OnlineTileSourceBase src) {
        mapView.setTileSource(src);
        // 换了图源，自有源瓦片副本全部作废 —— 同一个瓦片索引现在对应完全不同的图，
        // 不清就会把上一个图源的像素继续画出来。
        if (scalingTiles != null) {
            scalingTiles.clearSourceCache();
        }
        applyZoomLimit(src);
        mapView.invalidate();
        Toast.makeText(requireContext(), "已切换到：" + src.name(), Toast.LENGTH_SHORT).show();
    }

    /**
     * 设置缩放范围。
     *
     * <p>最大缩放固定为 {@link TemplateTileSource#DECLARED_MAX_Z}（22）——瓦片源本身
     * 声明可服务到该层级；超出真实上限（z18）的部分由 {@link ScalingTilesOverlay}
     * 取 z18 瓦片的对应区域裁剪放大绘制，所以用户能一直放大下去，既不会白屏也不会重复平铺。
     */
    private void applyZoomLimit(OnlineTileSourceBase src) {
        if (mapView == null) return;
        mapView.setMaxZoomLevel((double) TemplateTileSource.DECLARED_MAX_Z);
        if (mapView.getZoomLevelDouble() > TemplateTileSource.DECLARED_MAX_Z) {
            mapView.getController().setZoom((double) TemplateTileSource.DECLARED_MAX_Z);
        }
    }

    // ==================== 真实位置（蓝点） ====================

    // 下面两处调用都已经被 hasLocationPermission() 把关，并且各自包了 try/catch(Exception)。
    // lint 的 MissingPermission 只认 ContextCompat.checkSelfPermission 那几种固定写法，
    // 看不懂本工程自己的 hasLocationPermission() 辅助方法，所以这里显式抑制。
    // 注意：抑制的前提是守卫真的在 —— 改动时不要把 hasLocationPermission() 检查删掉。
    @SuppressLint("MissingPermission")
    private Location bestRealLocation() {
        if (!hasLocationPermission()) return null;
        LocationManager lm = activity().getLocationManager();
        if (lm == null) return null;

        String[] order = activity().isMockActive()
                ? new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}
                : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER};

        for (String p : order) {
            Location l = null;
            try {
                l = lm.getLastKnownLocation(p);
            } catch (Exception ignored) {
            }
            if (l != null) return l;
        }
        return null;
    }

    protected void enableRealLocation() {
        if (mapView == null || !hasLocationPermission()) return;
        Location cached = bestRealLocation();
        if (cached != null) showRealPosition(cached);
        registerRealUpdates();
    }

    @SuppressLint("MissingPermission")
    private void registerRealUpdates() {
        if (realUpdatesOn || mapView == null || !hasLocationPermission()) return;
        LocationManager lm = activity().getLocationManager();
        if (lm == null) return;
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f,
                        realListener, Looper.getMainLooper());
                realUpdatesOn = true;
            }
        } catch (Exception ignored) {
        }
    }

    private void removeRealUpdates() {
        if (!realUpdatesOn) return;
        realUpdatesOn = false;
        LocationManager lm = activity().getLocationManager();
        if (lm == null) return;
        try {
            lm.removeUpdates(realListener);
        } catch (Exception ignored) {
        }
    }

    private void showRealPosition(Location loc) {
        if (mapView == null || realMarker == null || loc == null) return;
        realMarker.setPosition(toMapPoint(loc.getLatitude(), loc.getLongitude()));
        realMarker.setVisible(true);
    }

    protected void centerOnRealIfIdle(boolean animate) {
        if (mapView == null || centeredOnReal) return;
        if (!Double.isNaN(activity().getState().lat)) return;
        if (activity().getState().valid) return;
        Location loc = bestRealLocation();
        if (loc == null) return;
        GeoPoint gp = toMapPoint(loc.getLatitude(), loc.getLongitude());
        if (animate) {
            mapView.getController().animateTo(gp);
        } else {
            mapView.getController().setCenter(gp);
        }
        centeredOnReal = true;
        mapView.invalidate();
    }

    public void refreshMyLocation() {
        if (mapView == null) return;
        enableRealLocation();
        centerOnRealIfIdle(true);
        mapView.invalidate();
    }

    protected boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** 对 drawable 重新着色（用于起/终点标记）。 */
    protected Drawable tint(int resId, int color) {
        Drawable d = ContextCompat.getDrawable(requireContext(), resId);
        if (d == null) return null;
        Drawable tinted = d.mutate();
        DrawableCompat.setTint(tinted, color);
        return tinted;
    }

    protected void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private final LocationListener realListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            showRealPosition(location);
            centerOnRealIfIdle(true);
            if (mapView != null) mapView.invalidate();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        try {
            if (!isAdded()) {
                return;
            }
            if (mapView != null) {
                // 退出重进后 MapView 可能处于 paused 状态：必须补一次 onResume 才会重新请求瓦片，
                // 否则会整屏空白（这是"退出软件重进地图白屏"的直接原因）。
                mapView.onResume();
                mapView.invalidate();
            }
            enableRealLocation();
            centerOnRealIfIdle(true);
        } catch (Throwable t) {
            // 切页时序里任何一个 NPE / IllegalState 都不应让应用挂掉：
            // 用户描述的"一用就闪退"很可能就来自这里。吞掉异常，
            // 玻璃不强求恢复，等用户切回时 setupGlass 会重建。
            android.util.Log.w("BaseMapFragment", "onResume swallowed", t);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            if (mapView != null) {
                mapView.onPause();
            }
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "onPause swallowed", t);
        }
    }

    @Override
    public void onDestroyView() {
        try {
            removeRealUpdates();
        } catch (Throwable ignored) { }
        try {
            if (mapView != null) {
                // 记下中心点：下次 onCreateView 会新建 MapView，中心默认是世界原点 (0,0)，
                // 不恢复就会跳到几内亚湾的海面上 —— 看起来就像"地图是白的"。
                try {
                    if (mapView.getMapCenter() != null) {
                        SAVED_CENTERS.put(getClass().getName(), mapView.getMapCenter());
                    }
                } catch (Throwable ignored) { }

                // 顺序很重要：先 onDetach 再置空，避免 osmdroid 内部 handler 回调到已销毁的 View
                mapView.onPause();
                mapView.onDetach();
                mapView = null;
            }
        } catch (Throwable t) {
            android.util.Log.w("BaseMapFragment", "onDestroyView mapView detach swallowed", t);
        }
        realMarker = null;
        // overlay 本体会随 MapView.onDetach() 一起走（那时它自己会清掉源瓦片副本缓存），
        // 这里只是把引用放掉
        scalingTiles = null;
        centeredOnReal = false;
        super.onDestroyView();
    }
}
