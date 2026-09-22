package com.example.mockgps;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 地图选点页：整屏地图，点哪儿就虚拟定位到哪儿。
 *
 * <p>坐标系：App 对内对外统一用 WGS-84；高德瓦片是 GCJ-02，
 * 所以「地图上的点」与「写入系统的坐标」之间要做双向换算
 * （见 {@link CoordUtil}）。
 *
 * <p>首次进入且尚未选点时，地图会自动居中到<b>真实 GPS 位置</b>并画一个蓝点，
 * 而不是停在世界原点的一片空白上。
 */
public class MapFragment extends Fragment {

    /** 支持 {x}{y}{z} 占位符的瓦片源（osmdroid 内置 XYTileSource 只认 /z/x/y 形式） */
    private static class TemplateTileSource extends OnlineTileSourceBase {
        TemplateTileSource(String name, int min, int max, int tileSize, String ext, String[] urls) {
            super(name, min, max, tileSize, ext, urls);
        }

        @NonNull
        @Override
        public String getTileURLString(long pMapTileIndex) {
            return getBaseUrl()
                    .replace("{x}", Integer.toString(MapTileIndex.getX(pMapTileIndex)))
                    .replace("{y}", Integer.toString(MapTileIndex.getY(pMapTileIndex)))
                    .replace("{z}", Integer.toString(MapTileIndex.getZoom(pMapTileIndex)));
        }
    }

    /** 高德矢量路网（GCJ-02，国内可直连，无需 Key） */
    private static final OnlineTileSourceBase SRC_AMAP =
            new TemplateTileSource("AMap", 3, 19, 256, ".png", new String[]{
                    "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                    "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                    "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                    "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}"});

    /** 高德卫星影像（GCJ-02） */
    private static final OnlineTileSourceBase SRC_SAT =
            new TemplateTileSource("AMapSat", 3, 19, 256, ".png", new String[]{
                    "https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}",
                    "https://webst02.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}",
                    "https://webst03.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}",
                    "https://webst04.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}"});

    private MapView mapView;
    private Marker marker;        // 虚拟定位选点标记
    private Marker realMarker;    // 真实 GPS 位置（蓝点）

    private TextView tvCoord, tvState;
    private double selLat = Double.NaN, selLng = Double.NaN;

    /** 是否已注册真实位置更新 */
    private boolean realUpdatesOn = false;
    /** 本次进入是否已按真实位置居中过（避免反复抢用户手势） */
    private boolean centeredOnReal = false;

    private MainActivity activity() {
        return (MainActivity) requireActivity();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // osmdroid 配置必须在 MapView 创建（inflate）之前加载
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        tvCoord = v.findViewById(R.id.tv_map_coord);
        tvState = v.findViewById(R.id.tv_map_state);

        mapView = v.findViewById(R.id.map);
        mapView.setTileSource(SRC_AMAP);
        mapView.setBuiltInZoomControls(false);
        mapView.setMultiTouchControls(true);
        mapView.setMinZoomLevel(3.0);
        mapView.setMaxZoomLevel(19.0);
        mapView.getController().setZoom(16.0);

        // 真实位置蓝点（先加，后加的浮层会盖在上面）
        realMarker = new Marker(mapView);
        realMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        realMarker.setTitle("我的位置");
        realMarker.setVisible(false);
        Drawable dot = ContextCompat.getDrawable(requireContext(), R.drawable.ic_my_location);
        if (dot != null) realMarker.setIcon(dot);
        mapView.getOverlays().add(realMarker);

        // 选点标记：未选点时不显示（否则会画在世界原点）
        marker = new Marker(mapView);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setTitle("虚拟定位");
        marker.setVisible(false);
        mapView.getOverlays().add(marker);

        MapEventsOverlay events = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                onMapPicked(p);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        });
        mapView.getOverlays().add(events);

        MaterialButtonToggleGroup toggle = v.findViewById(R.id.toggle_map);
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            switchTileSource(checkedId == R.id.src_sat ? SRC_SAT : SRC_AMAP);
        });

        ImageButton btnZoomIn = v.findViewById(R.id.btn_zoom_in);
        ImageButton btnZoomOut = v.findViewById(R.id.btn_zoom_out);
        ImageButton btnCenter = v.findViewById(R.id.btn_center_sel);
        ImageButton btnFav = v.findViewById(R.id.btn_map_fav);
        btnZoomIn.setOnClickListener(x -> mapView.getController().zoomIn());
        btnZoomOut.setOnClickListener(x -> mapView.getController().zoomOut());
        btnCenter.setOnClickListener(x -> centerSelected());
        btnFav.setOnClickListener(x -> favoriteSelected());

        enableRealLocation();
        syncFromState(false);
        // 没有选点也没有主页坐标时，定位到真实 GPS
        centerOnRealIfIdle(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
        enableRealLocation();
        syncFromState(true);
        centerOnRealIfIdle(true);
        // 从主页切过来时把 UI 上的生效状态刷新一次
        refreshStateText();
    }

    @Override
    public void onPause() {
        super.onPause();
        removeRealUpdates();
        mapView.onPause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        removeRealUpdates();
        if (mapView != null) {
            mapView.onDetach();
            mapView = null;
        }
        realMarker = null;
        marker = null;
    }

    // ==================== 选点 ====================

    /** 地图上的点是 GCJ-02，换算回 WGS-84 后再交给虚拟定位引擎 */
    private void onMapPicked(GeoPoint p) {
        double[] w = CoordUtil.gcj02ToWgs84(p.getLatitude(), p.getLongitude());
        selLat = w[0];
        selLng = w[1];
        marker.setPosition(p);
        marker.setVisible(true);
        mapView.invalidate();

        boolean ok = activity().applyMock(selLat, selLng);
        updateCoordText();
        refreshStateText();
        if (ok) {
            toast("已定位到 " + MainActivity.fmt(selLat) + ", " + MainActivity.fmt(selLng));
        } else {
            toast("未能写入模拟位置：请授权定位权限，并在开发者选项中选中本应用");
        }
    }

    /** 把 WGS-84 坐标显示到地图上（先转 GCJ-02） */
    private GeoPoint toMapPoint(double lat, double lng) {
        double[] g = CoordUtil.wgs84ToGcj02(lat, lng);
        return new GeoPoint(g[0], g[1]);
    }

    private void switchTileSource(OnlineTileSourceBase src) {
        mapView.setTileSource(src);
        // 换图层后重画标记，避免 marker 被瓦片覆盖
        if (!Double.isNaN(selLat)) {
            marker.setPosition(toMapPoint(selLat, selLng));
        }
        mapView.invalidate();
        toast("已切换到：" + mapView.getTileProvider().getTileSource().name());
    }

    private void centerSelected() {
        if (Double.isNaN(selLat)) {
            toast("还没有选点，请先点一下地图");
            return;
        }
        mapView.getController().animateTo(toMapPoint(selLat, selLng));
    }

    // ==================== 状态同步 ====================

    /** 进入页面时把主页（输入框 / 列表）里的坐标搬到地图上 */
    private void syncFromState(boolean animate) {
        MainActivity.SharedState st = activity().getState();
        if (!st.valid) return;
        if (!Double.isNaN(selLat) && Math.abs(selLat - st.lat) < 1e-9
                && Math.abs(selLng - st.lng) < 1e-9) {
            return;
        }
        selLat = st.lat;
        selLng = st.lng;
        GeoPoint gp = toMapPoint(selLat, selLng);
        marker.setPosition(gp);
        marker.setVisible(true);
        if (animate) {
            mapView.getController().animateTo(gp);
        } else {
            mapView.getController().setCenter(gp);
        }
        mapView.invalidate();
        updateCoordText();
    }

    private void updateCoordText() {
        if (tvCoord == null) return;
        tvCoord.setText(Double.isNaN(selLat)
                ? "尚未选点"
                : MainActivity.fmt(selLat) + ", " + MainActivity.fmt(selLng));
    }

    private void refreshStateText() {
        if (tvState == null) return;
        if (activity().isMockActive() && !Double.isNaN(selLat)) {
            tvState.setText(String.format(Locale.CHINA, "虚拟定位已生效：%.6f, %.6f", selLat, selLng));
            tvState.setTextColor(requireContext().getColor(R.color.state_ok_text));
        } else {
            tvState.setText("虚拟定位：未启用");
            tvState.setTextColor(requireContext().getColor(R.color.state_idle_text));
        }
    }

    // ==================== 真实位置（蓝点） ====================

    /**
     * 取一个「真实」的最近位置。
     *
     * <p>注意：本 App 的虚拟定位只覆盖 {@code gps} 提供方，
     * 所以 {@code network} / {@code passive} 始终反映真实位置；
     * 虚拟定位开启时不能再把 {@code gps} 的缓存当成真实位置，否则蓝点会跟着跳到假位置。
     */
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

    /** 权限具备时：先把缓存里的真实位置画出来，再注册实时更新 */
    private void enableRealLocation() {
        if (mapView == null || !hasLocationPermission()) return;
        Location cached = bestRealLocation();
        if (cached != null) showRealPosition(cached);
        registerRealUpdates();
    }

    private void registerRealUpdates() {
        if (realUpdatesOn || mapView == null || !hasLocationPermission()) return;
        LocationManager lm = activity().getLocationManager();
        if (lm == null) return;
        try {
            // 只监听 network：本 App 的模拟不覆盖它，拿到的永远是真实位置
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

    /** 尚无选点、主页也没有坐标时，把视野居中到真实位置 */
    private void centerOnRealIfIdle(boolean animate) {
        if (mapView == null) return;
        if (centeredOnReal) return;
        if (!Double.isNaN(selLat)) return;                 // 已有选点，不抢视野
        if (activity().getState().valid) return;            // 主页已有坐标，交给 syncFromState
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

    /** 权限结果回来后由 MainActivity 调用 */
    public void refreshMyLocation() {
        if (mapView == null) return;
        enableRealLocation();
        centerOnRealIfIdle(true);
        mapView.invalidate();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
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

    // ==================== 收藏当前选点 ====================

    private void favoriteSelected() {
        if (Double.isNaN(selLat)) {
            toast("请先点一下地图选个位置");
            return;
        }
        final double lat = selLat, lng = selLng;

        EditText input = new EditText(requireContext());
        input.setHint("备注，例如：家 / 公司 / 常用打卡点");
        input.setSingleLine(true);
        input.setTextSize(15f);

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(requireContext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, 0, pad, 0);
        box.addView(input, lp);

        new AlertDialog.Builder(requireContext())
                .setTitle("收藏该位置")
                .setMessage(String.format(Locale.CHINA,
                        "坐标：%.6f, %.6f\n备注留空则自动用坐标做名称", lat, lng))
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = MainActivity.fmt(lat) + ", " + MainActivity.fmt(lng);
                    }
                    List<FavoriteStore.Favorite> favs =
                            new ArrayList<>(FavoriteStore.load(requireContext()));
                    favs.add(0, new FavoriteStore.Favorite(name, lat, lng));
                    FavoriteStore.save(requireContext(), favs);
                    toast("已收藏：" + name + "\n回主页「我的收藏」可查看");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
