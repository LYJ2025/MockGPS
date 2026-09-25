package com.example.mockgps;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;

import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 选点地图页：整屏地图，点哪儿就虚拟定位到哪儿（WGS-84）。
 * 与「轨迹」页完全独立（两个地图实例），互不干扰。
 *
 * <p>本页也叠加显示「实际轨迹」（轨迹页跑出来的绿线），方便边选点边看模拟轨迹走到哪了。
 */
public class PickFragment extends BaseMapFragment {

    private Marker pickMarker;     // 虚拟定位选点标记
    private double selLat = Double.NaN, selLng = Double.NaN;
    private TextView tvCoord, tvState;
    private org.osmdroid.views.overlay.Polyline pickTrailLine;   // 实际轨迹（绿）
    private MainActivity.TrailListener trailListener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_map, container, false);
        setupMap(v);

        tvCoord = v.findViewById(R.id.tv_map_coord);
        tvState = v.findViewById(R.id.tv_map_state);

        // 选点标记：未选点时不显示
        pickMarker = new Marker(mapView);
        pickMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        pickMarker.setTitle("虚拟定位");
        pickMarker.setVisible(false);
        mapView.getOverlays().add(pickMarker);

        // 实际轨迹（绿）：与轨迹页共享同一份点，实时叠加显示
        pickTrailLine = new org.osmdroid.views.overlay.Polyline();
        pickTrailLine.setColor(0xFF1BAA59);
        pickTrailLine.setWidth(5f);
        pickTrailLine.setGeodesic(false);
        mapView.getOverlays().add(pickTrailLine);
        trailListener = this::redrawPickTrail;

        ImageButton btnFav = v.findViewById(R.id.btn_map_fav);
        if (btnFav != null) btnFav.setOnClickListener(x -> favoriteSelected());

        syncFromState(false);
        updateCoordText();
        refreshStateText();
        // 建好视图后补一次重画：ViewPager2 可能是「先 onResume 后 onCreateView」，
        // 那时 mapView 还是 null，onResume 里的重画直接被跳过了
        redrawPickTrail();
        return v;
    }

    /**
     * 重画共享的实际轨迹（绿线）。
     *
     * <p>第一行的「视图还在不在」检查必须留着：轨迹 tick 挂在 {@code MainActivity} 上，
     * 与 Fragment 生命周期无关；切页后 ViewPager2 会销毁离屏页视图，而回调仍在继续 ——
     * 那时 osmdroid 的 Polyline 内部 LinearRing 已被 {@code MapView.onDetach()} 清空，
     * {@code setPoints()} 会抛 NullPointerException。
     */
    private void redrawPickTrail() {
        if (!isAdded() || getView() == null) return;
        if (mapView == null) return;
        List<GeoPoint> pts = new ArrayList<>();
        for (TrackEngine.TrackPoint t : activity().getSharedTrail()) {
            pts.add(toMapPoint(t.lat, t.lng));
        }
        // 与 TrackFragment.writePoints 同样的自愈逻辑：osmdroid 的 Polyline 一旦经历过
        // MapView.onDetach()，内部 LinearRing 会被清空，setPoints() 直接抛 NPE（已用 javap 确认）。
        // 抛出就说明这个对象废了 —— 摘掉重建，不能让整页崩掉。
        if (pickTrailLine != null) {
            try {
                pickTrailLine.setPoints(pts);
                mapView.invalidate();
                return;
            } catch (NullPointerException broken) {
                try {
                    mapView.getOverlays().remove(pickTrailLine);
                } catch (Throwable ignored) { }
            } catch (Throwable t) {
                return;
            }
        }
        try {
            pickTrailLine = new org.osmdroid.views.overlay.Polyline();
            pickTrailLine.setColor(0xFF1BAA59);
            pickTrailLine.setWidth(5f);
            pickTrailLine.setGeodesic(false);
            mapView.getOverlays().add(pickTrailLine);
            pickTrailLine.setPoints(pts);
            mapView.invalidate();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void addExtraOverlays() {
        // 选点标记已在 onCreateView 中加入；此处无需额外图层
    }

    @Override
    protected void onMapTap(GeoPoint p) {
        // 地图上的点是 GCJ-02，换算回 WGS-84 后再交给虚拟定位引擎
        double[] w = CoordUtil.gcj02ToWgs84(p.getLatitude(), p.getLongitude());
        selLat = w[0];
        selLng = w[1];
        pickMarker.setPosition(p);
        pickMarker.setVisible(true);
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

    @Override
    protected void onCenterClicked() {
        if (mapView == null) return;
        if (Double.isNaN(selLat)) {
            toast("还没有选点，请先点一下地图");
            return;
        }
        mapView.getController().animateTo(toMapPoint(selLat, selLng));
    }

    private void syncFromState(boolean animate) {
        if (mapView == null || pickMarker == null) return;
        MainActivity.SharedState st = activity().getState();
        if (!st.valid) return;
        if (!Double.isNaN(selLat) && Math.abs(selLat - st.lat) < 1e-9
                && Math.abs(selLng - st.lng) < 1e-9) {
            return;
        }
        selLat = st.lat;
        selLng = st.lng;
        GeoPoint gp = toMapPoint(selLat, selLng);
        pickMarker.setPosition(gp);
        pickMarker.setVisible(true);
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
            tvState.setTextColor(ContextCompat.getColor(requireContext(), R.color.state_ok_text));
        } else {
            tvState.setText("虚拟定位：未启用");
            tvState.setTextColor(ContextCompat.getColor(requireContext(), R.color.state_idle_text));
        }
    }

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

    @Override
    public void onResume() {
        super.onResume();
        try {
            if (!isAdded()) return;
            if (mapView == null) return;   // 视图尚未创建（ViewPager2 时序），onCreateView 里会做
            activity().addTrailListener(trailListener);
            syncFromState(false);
            updateCoordText();
            refreshStateText();
            redrawPickTrail();
        } catch (Throwable t) {
            // 切页时序里任何一个 NPE 都不能让应用挂掉。
            // logcat 出来再定位，不要让用户看到闪退。
            android.util.Log.w("PickFragment", "onResume swallowed", t);
        }
    }

    @Override
    public void onDestroyView() {
        // 不能依赖 isAdded() 判断是否移除监听器：ViewPager2 销毁离屏页时 Fragment
        // 可能已经 detached，那样移除会被跳过、回调继续打到已销毁的视图上。
        final MainActivity act = (MainActivity) getActivity();
        if (act != null) {
            try {
                act.removeTrailListener(trailListener);
            } catch (Throwable ignored) { }
        }
        trailListener = null;

        // 先把图层从地图上摘掉再清字段 —— 只清字段会让 MapView.onDetach()
        // 去清理一批我们已不再持有的对象。
        try {
            if (mapView != null) {
                if (pickTrailLine != null) mapView.getOverlays().remove(pickTrailLine);
                if (pickMarker != null) mapView.getOverlays().remove(pickMarker);
            }
        } catch (Throwable ignored) { }

        pickMarker = null;
        pickTrailLine = null;
        tvCoord = null;
        tvState = null;
        super.onDestroyView();
    }
}
