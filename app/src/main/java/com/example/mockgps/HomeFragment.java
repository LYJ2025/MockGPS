package com.example.mockgps;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 主页：坐标输入 / 虚拟定位控制 / 行政区列表 / 收藏夹。
 *
 * <p>行政区是下钻式列表，省级模式两级（大区 → 省级），地级模式三级（大区 → 省级 → 地级）。
 * 列表用 weight 占满页面剩余高度，避免窗口太小程序项放不下。
 */
public class HomeFragment extends Fragment {

    // 选择行政区划的标签：0=省级 1=地级 2=收藏
    private static final int TAB_PROVINCE = 0;
    private static final int TAB_CITY = 1;
    private static final int TAB_FAV = 2;

    private MainActivity activity() {
        return (MainActivity) requireActivity();
    }

    private EditText etLat, etLng;
    private CheckBox cbContinuous;
    private TextView tvStatus;

    // ---- 行政区下钻 ----
    private int divisionMode = TAB_PROVINCE;
    private int divisionLevel = 0;          // 0=大区 1=省级 2=地级
    private int selRegion = -1;
    private int selProvince = -1;
    private ListView lvDivision;
    private TextView tvDivisionPath, tvDivisionHint, tvDivisionCount;
    private ImageButton btnDivisionBack;
    private final List<String> divisionItems = new ArrayList<>();
    private ArrayAdapter<String> divisionAdapter;

    // ---- 收藏 ----
    private final List<FavoriteStore.Favorite> favorites = new ArrayList<>();
    private FavoriteAdapter favAdapter;
    private ListView lvFav;
    private TextView tvFavEmpty;
    private TabLayout tabs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        etLat = v.findViewById(R.id.et_lat);
        etLng = v.findViewById(R.id.et_lng);
        cbContinuous = v.findViewById(R.id.cb_continuous);
        tvStatus = v.findViewById(R.id.tv_status);

        v.findViewById(R.id.btn_apply).setOnClickListener(x -> applyFromInput());
        v.findViewById(R.id.btn_stop).setOnClickListener(x -> {
            activity().stopMock();
            cbContinuous.setChecked(false);
            setStatus("虚拟定位已停止", 0);
            toast("已停止虚拟定位");
        });
        v.findViewById(R.id.btn_open_dev).setOnClickListener(x -> activity().openDevSettings());
        cbContinuous.setOnCheckedChangeListener((b, checked) -> activity().setContinuous(checked));

        setupCollapse(v);

        // 输入框内容变化时同步到共享状态，让另一页面的地图也能跟着走
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { syncStateFromInput(); }
        };
        etLat.addTextChangedListener(watcher);
        etLng.addTextChangedListener(watcher);

        tabs = v.findViewById(R.id.tabs_picker);
        setupTabs(v);
        setupDivisionPicker(v);
        setupFavorites(v);
        syncFromState();
    }

    /** 从地图页切回来时，把共享坐标同步进输入框 */
    @Override
    public void onResume() {
        super.onResume();
        syncFromState();
    }

    /** 时间长不看时把「坐标设置」折叠起来，把整屏高度让给行政区 / 收藏列表 */
    private View panelCtrl;
    private TextView tvCtrlSummary;
    private ImageButton btnCtrlToggle;
    private boolean ctrlExpanded = true;

    private void setupCollapse(View v) {
        panelCtrl = v.findViewById(R.id.panel_ctrl);
        tvCtrlSummary = v.findViewById(R.id.tv_ctrl_summary);
        btnCtrlToggle = v.findViewById(R.id.btn_ctrl_toggle);
        btnCtrlToggle.setOnClickListener(x -> setCtrlExpanded(!ctrlExpanded));
    }

    private void setCtrlExpanded(boolean expanded) {
        ctrlExpanded = expanded;
        if (panelCtrl == null) return;
        panelCtrl.setVisibility(expanded ? View.VISIBLE : View.GONE);
        tvCtrlSummary.setVisibility(expanded ? View.GONE : View.VISIBLE);
        // 折叠时箭头朝上，暗示「点一下展开」
        btnCtrlToggle.setRotation(expanded ? 0f : 180f);
    }

    // ==================== 坐标输入 ====================

    private void syncStateFromInput() {
        try {
            double lat = Double.parseDouble(etLat.getText().toString().trim());
            double lng = Double.parseDouble(etLng.getText().toString().trim());
            activity().getState().set(lat, lng);
        } catch (NumberFormatException ignored) {
            // 输入未完成时不同步
        }
    }

    private void syncFromState() {
        MainActivity.SharedState st = activity().getState();
        if (etLat == null || etLng == null) return;
        if (st.valid) {
            String la = MainActivity.fmt(st.lat), ln = MainActivity.fmt(st.lng);
            if (!la.equals(etLat.getText().toString())) etLat.setText(la);
            if (!ln.equals(etLng.getText().toString())) etLng.setText(ln);
        }
        if (activity().isMockActive() && st.valid) {
            setStatus(String.format(Locale.CHINA, "虚拟定位生效中：%.6f, %.6f", st.lat, st.lng), 1);
        }
    }

    private void applyFromInput() {
        double lat, lng;
        try {
            lat = Double.parseDouble(etLat.getText().toString().trim());
            lng = Double.parseDouble(etLng.getText().toString().trim());
        } catch (NumberFormatException e) {
            setStatus("经纬度格式不正确", 2);
            toast("经纬度格式不正确");
            return;
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            setStatus("经纬度超出有效范围", 2);
            toast("经纬度超出有效范围");
            return;
        }
        boolean ok = activity().applyMock(lat, lng);
        if (ok) {
            setStatus(String.format(Locale.CHINA, "虚拟定位已生效：%.6f, %.6f", lat, lng), 1);
            activity().setContinuous(cbContinuous.isChecked());
            toast("虚拟定位已生效");
        } else {
            setStatus("未生效：请授权定位权限并在开发者选项中选中本应用", 2);
        }
    }

    // ==================== 行政区下钻 ====================

    private void setupDivisionPicker(View v) {
        lvDivision = v.findViewById(R.id.lv_division);
        tvDivisionPath = v.findViewById(R.id.tv_division_path);
        tvDivisionHint = v.findViewById(R.id.tv_division_hint);
        tvDivisionCount = v.findViewById(R.id.tv_division_count);
        btnDivisionBack = v.findViewById(R.id.btn_division_back);

        divisionAdapter = new ArrayAdapter<String>(
                requireContext(), R.layout.item_division, R.id.tv_item, divisionItems) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                // 只有还能往下钻的项才显示右箭头
                View chevron = row.findViewById(R.id.img_chevron);
                if (chevron != null) {
                    chevron.setVisibility(itemHasChildren(position) ? View.VISIBLE : View.GONE);
                }
                return row;
            }
        };
        lvDivision.setAdapter(divisionAdapter);
        lvDivision.setOnItemClickListener((p, row, position, id) -> onDivisionItemClick(position));
        btnDivisionBack.setOnClickListener(x -> goUpDivision());
        refreshDivisionList();
    }

    private boolean itemHasChildren(int position) {
        if (divisionLevel == 0) return true;
        if (divisionLevel == 1) {
            if (divisionMode != TAB_CITY) return false;
            int[] idx = DivisionData.REGION_PROVINCES[selRegion];
            if (position < 0 || position >= idx.length) return false;
            return DivisionData.CITY_NAMES[idx[position]].length > 0;
        }
        return false;
    }

    private void setDivisionMode(int mode) {
        if (divisionMode != mode || divisionLevel != 0) {
            divisionMode = mode;
            divisionLevel = 0;
            selRegion = -1;
            selProvince = -1;
        }
        refreshDivisionList();
    }

    private void goUpDivision() {
        if (divisionLevel > 0) {
            divisionLevel--;
            refreshDivisionList();
        }
    }

    private void refreshDivisionList() {
        divisionItems.clear();
        if (divisionLevel == 0) {
            Collections.addAll(divisionItems, DivisionData.REGIONS);
            tvDivisionPath.setText("选择大区");
            btnDivisionBack.setVisibility(View.INVISIBLE);
        } else if (divisionLevel == 1) {
            for (int i : DivisionData.REGION_PROVINCES[selRegion]) {
                divisionItems.add(DivisionData.PROVINCE_NAMES[i]);
            }
            tvDivisionPath.setText(DivisionData.REGIONS[selRegion]);
            btnDivisionBack.setVisibility(View.VISIBLE);
        } else {
            Collections.addAll(divisionItems, DivisionData.CITY_NAMES[selProvince]);
            tvDivisionPath.setText(DivisionData.REGIONS[selRegion]
                    + " › " + DivisionData.PROVINCE_NAMES[selProvince]);
            btnDivisionBack.setVisibility(View.VISIBLE);
        }
        divisionAdapter.notifyDataSetChanged();

        boolean empty = divisionItems.isEmpty();
        lvDivision.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvDivisionCount.setText(empty ? "" : divisionItems.size() + " 项");

        if (divisionLevel == 0) {
            tvDivisionHint.setText("先选一个大区，再选具体行政区");
        } else if (divisionLevel == 1) {
            tvDivisionHint.setText(divisionMode == TAB_CITY
                    ? "点省级行政区展开其下辖地级行政区（无右箭头者直接定位到中心）"
                    : "点省级行政区即可定位到该省会 / 首府");
        } else {
            tvDivisionHint.setText("点地级行政区即可定位到该市中心");
        }
    }

    private void onDivisionItemClick(int position) {
        if (divisionLevel == 0) {
            selRegion = position;
            divisionLevel = 1;
            refreshDivisionList();
            return;
        }
        if (divisionLevel == 1) {
            int[] idx = DivisionData.REGION_PROVINCES[selRegion];
            if (position < 0 || position >= idx.length) return;
            int pi = idx[position];
            if (divisionMode == TAB_PROVINCE) {
                applyProvince(pi);
            } else if (DivisionData.CITY_NAMES[pi].length == 0) {
                // 直辖市 / 特别行政区没有地级建制，直接像省级那样定位到市中心（不弹提示）
                applyNoSubDivision(pi);
            } else {
                selProvince = pi;
                divisionLevel = 2;
                refreshDivisionList();
            }
            return;
        }
        applyCity(selProvince, position);
    }

    /**
     * 直辖市（京 / 津 / 沪 / 渝）与港澳特别行政区不设地级行政区，
     * 在地级列表里点到它们时，行为和省级列表一样：直接定位到该行政区的中心，不再弹提示。
     */
    private void applyNoSubDivision(int pi) {
        if (pi < 0 || pi >= DivisionData.PROVINCE_NAMES.length) return;
        String name = DivisionData.PROVINCE_NAMES[pi];
        locate(name, DivisionData.PROVINCE_LATLNG[pi][0],
                DivisionData.PROVINCE_LATLNG[pi][1], "");
    }

    private void applyProvince(int pi) {
        if (pi < 0 || pi >= DivisionData.PROVINCE_NAMES.length) return;
        locate(DivisionData.PROVINCE_NAMES[pi],
                DivisionData.PROVINCE_LATLNG[pi][0], DivisionData.PROVINCE_LATLNG[pi][1], "省级");
    }

    private void applyCity(int pi, int ci) {
        if (pi < 0 || pi >= DivisionData.CITY_NAMES.length) return;
        if (ci < 0 || ci >= DivisionData.CITY_NAMES[pi].length) return;
        locate(DivisionData.PROVINCE_NAMES[pi] + " · " + DivisionData.CITY_NAMES[pi][ci],
                DivisionData.CITY_LATLNG[pi][ci][0], DivisionData.CITY_LATLNG[pi][ci][1], "地级");
    }

    /** 选中某地：填坐标 → 应用虚拟定位 → 提示 */
    private void locate(String name, double lat, double lng, String type) {
        etLat.setText(MainActivity.fmt(lat));
        etLng.setText(MainActivity.fmt(lng));
        boolean ok = activity().applyMock(lat, lng);
        if (ok) {
            setStatus(String.format(Locale.CHINA, "已定位到%s%s：%.6f, %.6f",
                    type.isEmpty() ? "" : type + " ", name, lat, lng), 1);
            toast("已定位到 " + name);
        } else {
            setStatus("定位未生效：请在开发者选项中选中本应用", 2);
        }
    }

    // ==================== 标签页 ====================

    private void setupTabs(View v) {
        View pDiv = v.findViewById(R.id.panel_division);
        View pFav = v.findViewById(R.id.panel_fav);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                if (pos == TAB_FAV) {
                    pDiv.setVisibility(View.GONE);
                    pFav.setVisibility(View.VISIBLE);
                } else {
                    pDiv.setVisibility(View.VISIBLE);
                    pFav.setVisibility(View.GONE);
                    setDivisionMode(pos == TAB_CITY ? TAB_CITY : TAB_PROVINCE);
                }
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) { }

            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
    }

    // ==================== 收藏 ====================

    private void setupFavorites(View v) {
        lvFav = v.findViewById(R.id.lv_fav);
        tvFavEmpty = v.findViewById(R.id.tv_fav_empty);
        favAdapter = new FavoriteAdapter();
        lvFav.setAdapter(favAdapter);
        lvFav.setOnItemClickListener((p, row, position, id) -> applyFavorite(position));
        v.findViewById(R.id.btn_fav_add).setOnClickListener(x -> saveFavoriteFromInput());
        favorites.addAll(FavoriteStore.load(requireContext()));
        refreshFavorites();
    }

    private void refreshFavorites() {
        favAdapter.notifyDataSetChanged();
        boolean empty = favorites.isEmpty();
        tvFavEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        lvFav.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (tabs != null && tabs.getTabCount() > TAB_FAV) {
            TabLayout.Tab tab = tabs.getTabAt(TAB_FAV);
            if (tab != null) tab.setText("我的收藏（" + favorites.size() + "）");
        }
    }

    /** 把当前输入框里的坐标存为收藏，弹出备注输入 */
    private void saveFavoriteFromInput() {
        double lat, lng;
        try {
            lat = Double.parseDouble(etLat.getText().toString().trim());
            lng = Double.parseDouble(etLng.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("请先设置有效的经纬度");
            return;
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            toast("经纬度超出有效范围");
            return;
        }
        showNameDialog(lat, lng);
    }

    private void showNameDialog(double lat, double lng) {
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
                    favorites.add(0, new FavoriteStore.Favorite(name, lat, lng));
                    FavoriteStore.save(requireContext(), favorites);
                    refreshFavorites();
                    toast("已收藏：" + name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyFavorite(int position) {
        if (position < 0 || position >= favorites.size()) return;
        FavoriteStore.Favorite f = favorites.get(position);
        etLat.setText(MainActivity.fmt(f.lat));
        etLng.setText(MainActivity.fmt(f.lng));
        boolean ok = activity().applyMock(f.lat, f.lng);
        if (ok) {
            setStatus(String.format(Locale.CHINA, "已定位到收藏「%s」：%.6f, %.6f",
                    f.name, f.lat, f.lng), 1);
            toast("已定位到 " + f.name);
        } else {
            setStatus("定位未生效：请在开发者选项中选中本应用", 2);
        }
    }

    private void confirmDeleteFavorite(int position) {
        if (position < 0 || position >= favorites.size()) return;
        String name = favorites.get(position).name;
        new AlertDialog.Builder(requireContext())
                .setTitle("删除收藏")
                .setMessage("确定删除「" + name + "」？")
                .setPositiveButton("删除", (d, w) -> {
                    favorites.remove(position);
                    FavoriteStore.save(requireContext(), favorites);
                    refreshFavorites();
                    toast("已删除：" + name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private class FavoriteAdapter extends BaseAdapter {
        @Override public int getCount() { return favorites.size(); }
        @Override public Object getItem(int position) { return favorites.get(position); }
        @Override public long getItemId(int position) { return position; }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_favorite, parent, false);
            }
            FavoriteStore.Favorite f = favorites.get(position);
            ((TextView) row.findViewById(R.id.tv_fav_name)).setText(f.name);
            ((TextView) row.findViewById(R.id.tv_fav_coord))
                    .setText(MainActivity.fmt(f.lat) + ", " + MainActivity.fmt(f.lng));
            // 每次重新绑定都要重挂监听，避免 View 复用后 position 错位
            row.findViewById(R.id.btn_fav_delete)
                    .setOnClickListener(b -> confirmDeleteFavorite(position));
            return row;
        }
    }

    // ==================== 状态显示 ====================

    /** kind：0=空闲 1=成功 2=警告 */
    private void setStatus(String text, int kind) {
        tvStatus.setText(text);
        int bg = kind == 1 ? R.drawable.bg_status_ok
                : kind == 2 ? R.drawable.bg_status_warn : R.drawable.bg_status_idle;
        int fg = kind == 1 ? R.color.state_ok_text
                : kind == 2 ? R.color.state_warn_text : R.color.state_idle_text;
        tvStatus.setBackgroundResource(bg);
        tvStatus.setTextColor(requireContext().getColor(fg));
        if (tvCtrlSummary != null) {
            tvCtrlSummary.setText(text);
            tvCtrlSummary.setBackgroundResource(bg);
            tvCtrlSummary.setTextColor(requireContext().getColor(fg));
        }
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
