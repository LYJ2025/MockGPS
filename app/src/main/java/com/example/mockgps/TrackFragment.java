package com.example.mockgps;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButtonToggleGroup;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 轨迹模拟地图页：手绘 / 折线规划路线，按配速沿路线匀速模拟，并实时显示「真实轨迹」。
 *
 * <p>与「选点」页是两个独立的地图实例，互不干扰。规划路线（蓝）与真实走过的轨迹（绿）分别显示，
 * 方便对比验证模拟效果。
 */
public class TrackFragment extends BaseMapFragment {

    private static final int MODE_POLY = 1;
    private static final int MODE_FREE = 2;
    private int mode = MODE_POLY;

    private Polyline routeLine;       // 规划路线（蓝）
    private Polyline trailLine;       // 实际走过的轨迹（绿）
    private Marker startMarker;       // 起点（绿）
    private Marker endMarker;         // 终点（红）
    private Marker playbackMarker;    // 播放当前位置（蓝点）

    private MaterialButtonToggleGroup trackModeGrp;
    private EditText etSpeed;
    private TextView tvTrackProgress;
    private com.google.android.material.button.MaterialButton btnDrawToggle;
    /** 「开始 / 停止 / 继续」三态合一的那个按钮（原来的"开始"+"停止"已合并到它）。 */
    private com.google.android.material.button.MaterialButton btnTrackStart;
    private MaterialSwitch swTrail;
    private MainActivity.TrailListener trailListener;

    /**
     * 一次性布局监听：视图真正完成布局（有尺寸且已 attach 到 Window）后做一次可靠补画。
     *
     * <p>这是修「切回轨迹页后蓝点 / 绿起点 / 绿线消失」的关键。原先靠
     * {@code v.post(() -> redrawAll())} 补画，但 {@code post} 在 ViewPager2 重建场景里
     * 可能早于 {@code onAttachedToWindow} 触发 —— 那时 {@code mapView.invalidate()} 会被合并丢弃，
     * 第一次自然 onDraw 时图层还是空的，画面就停在「什么都没有」，直到手动平移/缩放才出现。
     * {@code onGlobalLayout} 一定在「测量 + 布局 + attach」之后回调，是画一次最可靠的时机。
     */
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener redrawOnLayout =
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (mapView == null || getView() == null) return;
                try {
                    getView().getViewTreeObserver().removeOnGlobalLayoutListener(this);
                } catch (Throwable ignored) { }
                // 关键：不要在这里同步 redrawAll()。onGlobalLayout 仍处于布局流程中，
                // 此刻 invalidate() 会被合并丢弃，第一次 onDraw 时 overlay 还是空的，
                // 表现就是"切回轨迹页什么都没有"。推迟到下一帧再画才真正渲染出来。
                if (mapView != null) mapView.post(() -> redrawAll());
                }
            };

    /** 整个轨迹控制面板（卡片）。绘制中隐藏它，把地图完全让出来。 */
    private View cardTrack;
    /** 绘制中的迷你悬浮窗（只有「结束画 / 结束设点」一个按钮）。 */
    private View cardDrawEnd;

    /**
     * 折线模式是否处于「设点中」。
     *
     * <p>它不改变加节点的规则（折线模式本来点地图就加节点），只是表示
     * "控制面板已收起、只留悬浮窗"，让地图整屏可用 —— 与画笔模式的绘制态对齐。
     * 注意它是 Fragment 字段，会跨视图重建保留，所以 onCreateView / onResume
     * 都要按它恢复面板显隐。
     */
    private boolean polyAdding;

    /**
     * 绘制手势里的"是否出现过双指"标记。
     * 一次手势（从所有手指抬起开始算）里只要出现第二根手指，就整段让给地图做缩放，
     * 不再采集轨迹；全部抬起后由 {@link #onDrawTouch} 清零，下一手势重新判断。
     */
    private boolean drawGestureMultiTouch;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_track, container, false);
        setupMap(v);
        DiagLog.d("TrackFragment.onCreateView setupMap done mapView=" + (mapView != null)
                + " routeLine=" + (routeLine != null) + " trailLine=" + (trailLine != null)
                + " startMarker=" + (startMarker != null) + " endMarker=" + (endMarker != null)
                + " playbackMarker=" + (playbackMarker != null));
        // 画笔模式「开始画」后的触摸接管必须挂上：单指画线、双指仍可缩放。
        // 这一步上一版漏了，导致"开始画"后没有任何触摸拦截、地图照常平移。
        setupDrawTouch();

        // ---- 控制区 ----
        trackModeGrp = v.findViewById(R.id.track_mode);
        etSpeed = v.findViewById(R.id.et_speed);
        tvTrackProgress = v.findViewById(R.id.tv_track_progress);
        btnDrawToggle = v.findViewById(R.id.btn_draw_toggle);
        swTrail = v.findViewById(R.id.sw_trail);
        cardTrack = v.findViewById(R.id.card_track);
        cardDrawEnd = v.findViewById(R.id.card_draw_end);

        trackModeGrp.check(R.id.mode_poly);
        trackModeGrp.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            mode = checkedId == R.id.mode_free ? MODE_FREE : MODE_POLY;
            // 切模式时两种"进行中"状态都要退出，避免残留
            activity().getTrackEngine().setDrawing(false);
            polyAdding = false;
            updateDrawButton();
            updateTrackHint();
            setDrawingUi(false);   // 面板必须恢复 —— 否则会一直停在「只剩悬浮窗」的状态
        });

        v.findViewById(R.id.btn_track_collapse).setOnClickListener(x -> {
            View body = v.findViewById(R.id.track_body);
            if (body == null) return;
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            x.setRotation(show ? 180 : 0);
            // 内容高度变了，显式请求重新测量：卡片是 wrap_content，
            // 不主动 requestLayout 的话个别机型不会立刻收缩（表现为"只隐藏了按钮、窗口没变小"）。
            if (cardTrack != null) cardTrack.requestLayout();
        });
        v.findViewById(R.id.btn_draw_toggle).setOnClickListener(x -> toggleDraw());
        if (cardDrawEnd != null) {
            cardDrawEnd.findViewById(R.id.btn_draw_end).setOnClickListener(x -> toggleDraw());
        }
        btnTrackStart = v.findViewById(R.id.btn_track_start);
        btnTrackStart.setOnClickListener(x -> onTrackButton());

        v.findViewById(R.id.btn_track_undo).setOnClickListener(x -> {
            activity().getTrackEngine().removeLast();
            redrawRoute();
            updateTrackHint();
            onRouteChanged();
        });
        v.findViewById(R.id.btn_track_clear).setOnClickListener(x -> {
            activity().getTrackEngine().clear();
            // 进度作废 + 按钮回「开始」；同时把"最后推送点"清掉，位置不再被轨迹钉住
            activity().clearTrackProgress();
            redrawRoute();
            updateTrackHint();
            updateTrackButton();
        });
        v.findViewById(R.id.btn_trail_clear).setOnClickListener(x -> {
            activity().clearSharedTrail();   // 清共享绿线
            redrawTrail();
        });

        if (swTrail != null) {
            swTrail.setChecked(true);
            swTrail.setOnCheckedChangeListener((btn, on) -> {
                if (trailLine != null) trailLine.setVisible(on);
                mapView.invalidate();
            });
        }

        // GPS 抖动开关：默认开（±15cm，真实 GPS 漂移量级）；关掉后轨迹严格贴合规划线
        MaterialSwitch swNoise = v.findViewById(R.id.sw_noise);
        if (swNoise != null) {
            TrackEngine eng = activity().getTrackEngine();
            swNoise.setChecked(eng.isNoiseOn());
            swNoise.setOnCheckedChangeListener((btn, on) -> {
                eng.setNoiseOn(on);
                toast(on ? "已开启 GPS 抖动（±15cm）" : "已关闭抖动：轨迹严格贴线");
            });
        }

        // 注册轨迹 tick 回调（驱动蓝点与进度）。
        // 注意：回调可能在 Fragment 视图销毁后仍被 tick 触发，所以每个引用都要判空，
        // 否则会 NPE 闪退（切页时最容易踩到）。
        activity().setTrackListener((p, dist, total, fin) -> {
            if (mapView == null || playbackMarker == null) return;
            playbackMarker.setPosition(toMapPoint(p.lat, p.lng));
            playbackMarker.setVisible(true);
            // 蓝点位置变了必须触发一次重绘，否则地图不会自动刷新（之前只改了 Marker 位置
            // 却没 invalidate —— 这就是"有数据、不显示"的直接原因之一）。
            if (mapView != null) mapView.invalidate();
            updateProgress(dist, total);
            if (fin) {
                toast("轨迹模拟完成（已到终点）");
                updateTrackButton();   // 彻底跑完 → 按钮回「开始」
            }
        });

        // 绿线改由共享轨迹驱动（每 tick 追加一点），避免每次全量重算
        trailListener = this::redrawTrail;
        activity().addTrailListener(trailListener);

        updateDrawButton();
        updateTrackHint();

        // 视图建好后安排补画（切页重建后必须重画路线/轨迹/蓝点）。
        //
        // 关键点：onCreateView 此刻 MapView 还没完成测量 / attach，直接 invalidate() 在
        // osmdroid + ViewPager2 重建场景里会被合并丢弃，第一次 onDraw 时 overlay 还是空的 ——
        // 这正是「切回轨迹页蓝点/起点/绿线消失」的根因。因此补画一律延后到「下一帧 / attach 之后」
        // 再执行（见下方三重保险调度，以及 redrawOnLayout 里也是 post 到下一帧）。
        updateTrackButton();
        // 恢复面板 / 悬浮窗的显隐：polyAdding 是 Fragment 字段，跨视图重建会保留，
        // 不恢复的话切页回来会出现"面板不见了但也没在设点"的错位
        setDrawingUi(polyAdding || activity().getTrackEngine().isDrawing());
        // 多重保险，确保切回轨迹页后蓝点 / 绿起点 / 绿线一定被重绘出来：
        //   ① onGlobalLayout（布局完成，内部再 post 到下一帧）
        //   ② mapView.post（当前帧之后）
        //   ③ postDelayed 250ms 兜底 —— 等价于"等一会让它自己出现"，这正是之前手动
        //      平移 / 缩放能救回来的原因，这里把它自动化。三种时机里至少有一种一定在
        //      视图真正可见之后触发，绝不会出现"卡在空白、除非手动操作"。
        try {
            v.getViewTreeObserver().addOnGlobalLayoutListener(redrawOnLayout);
        } catch (Throwable ignored) { }
        if (mapView != null) {
            mapView.post(() -> redrawAll());
            mapView.postDelayed(() -> redrawAll(), 250);
        }
        return v;
    }

    /**
     * 把「播放当前位置」蓝点摆回它该在的地方。
     *
     * <p>这个 Marker 属于页面 —— 每次 onCreateView 都会重建，初始是隐藏的；
     * 而轨迹 tick 挂在 Activity 上照跑不误。所以切页回来会看到「线在、点没了」。
     * 这里按 TrackEngine 的运行状态 + Activity 记下的最后一点把它恢复出来。
     */
    private void restorePlaybackMarker() {
        if (!isAdded() || mapView == null) return;
        if (playbackMarker == null) return;
        try {
            final TrackEngine engine = activity().getTrackEngine();
            final TrackEngine.TrackPoint last = activity().getLastTrackPoint();
            // 只要"有最后一点"就显示蓝点：运行中显示在当前位置，暂停/已到终点也停在最后一点，
            // 而不是只在 isRunning() 时显示（否则暂停切页回来蓝点会消失）。
            final boolean show = last != null;
            playbackMarker.setVisible(show);
            if (show) {
                playbackMarker.setPosition(toMapPoint(last.lat, last.lng));
            }
            mapView.invalidate();
        } catch (Throwable ignored) { }
    }

    /**
     * 一次性把路线（蓝）、起终点标记、轨迹（绿）、播放蓝点全部重画。
     *
     * <p>三个重画各自独立 try/catch：以前它们顺序写在 {@code onResume} 里，只要
     * {@code redrawRoute()} 抛一次异常被外层 catch 吞掉，后面的 {@code redrawTrail()}
     * 和 {@code restorePlaybackMarker()} 就整段被跳过 —— 表现就是「蓝点 + 绿起点 + 绿轨迹
     * 一起消失」。拆开各自兜底后，任一个失败都不影响另外两个。
     */
    private void redrawAll() {
        if (!isAdded() || mapView == null) {
            DiagLog.d("TrackFragment.redrawAll SKIP isAdded=" + isAdded()
                    + " mapView=" + (mapView != null));
            return;
        }
        // 关键诊断：地图宽高 + 中心。若切回轨迹页后中心被拽回真实 GPS（而非停在路线上），
        // 就是「蓝点/起点/绿线消失」的根因；post() 之后宽高应是真实尺寸。
        DiagLog.d("TrackFragment.redrawAll run mapW=" + mapView.getWidth()
                + " mapH=" + mapView.getHeight()
                + " center=" + mapView.getMapCenter().getLatitude()
                + "," + mapView.getMapCenter().getLongitude()
                + " smooth=" + activity().getTrackEngine().getSmooth().size()
                + " nodeCount=" + activity().getTrackEngine().nodeCount()
                + " trail=" + activity().getSharedTrail().size()
                + " lastTP=" + (activity().getLastTrackPoint() != null));
        try { redrawRoute(); } catch (Throwable t) {
            DiagLog.e("redrawRoute", t);
        }
        try { redrawTrail(); } catch (Throwable t) {
            DiagLog.e("redrawTrail", t);
        }
        try { restorePlaybackMarker(); } catch (Throwable t) {
            DiagLog.e("restorePlaybackMarker", t);
        }
        DiagLog.d("TrackFragment.redrawAll done"
                + " startMarker=" + (startMarker != null)
                + " endMarker=" + (endMarker != null)
                + " trailLine=" + (trailLine != null)
                + " playbackMarker=" + (playbackMarker != null));
    }

    /** 新建「规划路线」图层（蓝）。 */
    private Polyline newRouteLine() {
        Polyline l = new Polyline();
        l.setColor(0xFF1E80D8);
        l.setWidth(6f);
        l.setGeodesic(false);
        return l;
    }

    /** 新建「实际轨迹」图层（绿）。 */
    private Polyline newTrailLine() {
        Polyline l = new Polyline();
        l.setColor(0xFF1BAA59);
        l.setWidth(5f);
        l.setGeodesic(false);
        return l;
    }

    @Override
    protected void addExtraOverlays() {
        // 规划路线（蓝）
        routeLine = newRouteLine();
        mapView.getOverlays().add(routeLine);

        // 实际走过的轨迹（绿）
        trailLine = newTrailLine();
        mapView.getOverlays().add(trailLine);

        // 起点（绿）/ 终点（红）标记
        startMarker = new Marker(mapView);
        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        startMarker.setTitle("起点");
        startMarker.setVisible(false);
        startMarker.setIcon(tint(R.drawable.ic_location_dot,
                ContextCompat.getColor(requireContext(), R.color.state_ok_text)));
        mapView.getOverlays().add(startMarker);

        endMarker = new Marker(mapView);
        endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        endMarker.setTitle("终点");
        endMarker.setVisible(false);
        endMarker.setIcon(tint(R.drawable.ic_location_dot, 0xE0483B));
        mapView.getOverlays().add(endMarker);

        // 播放当前位置蓝点
        playbackMarker = new Marker(mapView);
        playbackMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        playbackMarker.setTitle("当前位置");
        playbackMarker.setVisible(false);
        Drawable pd = ContextCompat.getDrawable(requireContext(), R.drawable.ic_my_location);
        if (pd != null) playbackMarker.setIcon(pd);
        mapView.getOverlays().add(playbackMarker);

        // 画笔的触摸处理不再挂 Overlay —— 见 setupDrawTouch()。
        // 用 MapView 的 OnTouchListener 才能同时拿到"这一下是几根手指"，
        // 从而做到「单指画线、双指仍然能缩放地图」；Overlay.onTouchEvent 拿不到这个全局视角。
    }

    /**
     * 绘制期间的触摸接管。
     *
     * <h4>为什么用 OnTouchListener 而不是 Overlay</h4>
     * Overlay 的 {@code onTouchEvent} 依赖 MapView 的遍历顺序，而且拿不到
     * 「本次手势一共有几根手指」这种全局信息；{@code OnTouchListener} 在
     * {@code View.dispatchTouchEvent} 的第一站被调用，既保证一定被调用到，
     * 也能靠 {@code event.getPointerCount()} 完整区分单指 / 双指。
     *
     * <h4>规则</h4>
     * <ul>
     *   <li><b>单指拖动</b> → 消费掉（返回 true）：地图不平移，同时采集轨迹点；</li>
     *   <li><b>双指</b> → 完全不消费（返回 false）：交给地图做缩放；</li>
     *   <li>一次手势里只要出现过双指，整段都不再采集，直到手指全部抬起。</li>
     * </ul>
     *
     * <h4>为什么 ACTION_DOWN 不消费</h4>
     * 地图的缩放检测器必须先看到 DOWN，才能识别后面的双指缩放。
     * 「单指不许平移」是靠后面的 ACTION_MOVE 返回 true 保证的 ——
     * 只要有一个事件被消费，MapView 就不会把它交给自己的滚动手势检测器。
     */
    private void setupDrawTouch() {
        if (mapView == null) return;
        mapView.setOnTouchListener((v, event) -> onDrawTouch(event));
    }

    /** @return true 表示消费掉这次触摸（地图不响应） */
    private boolean onDrawTouch(MotionEvent e) {
        if (mapView == null) {
            return false;
        }
        // 非「画笔 + 绘制中」：完全不管，地图该怎么动就怎么动
        if (mode != MODE_FREE || !activity().getTrackEngine().isDrawing()) {
            return false;
        }

        final int action = e.getActionMasked();

        // 手势收尾：清掉"出现过双指"的标记，下一次手势重新判断
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            drawGestureMultiTouch = false;
        }

        // 本次手势已经出现过双指 → 整段让给地图缩放，绝不插手
        if (drawGestureMultiTouch) {
            return false;
        }

        // 出现第二根手指 → 从这一刻起放弃绘制，整段交给地图
        if (action == MotionEvent.ACTION_POINTER_DOWN || e.getPointerCount() >= 2) {
            drawGestureMultiTouch = true;
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            sampleFreehandAt(e);
            return true;   // 消费 → 地图不平移
        }

        if (action == MotionEvent.ACTION_DOWN) {
            // 采一个起点，但**不消费** DOWN —— 让地图的缩放检测器还能看到它
            sampleFreehandAt(e);
            return false;
        }

        if (action == MotionEvent.ACTION_UP) {
            // 消费 UP → 地图不会把它当成一次单击，
            // 绘制中就不会误触发「点地图选点」
            return true;
        }

        return false;
    }

    /** 把当前触摸位置换算成地图坐标并采集一个点。 */
    private void sampleFreehandAt(MotionEvent e) {
        if (mapView == null) return;
        final float x = e.getX(), y = e.getY();
        // 夹在地图矩形内，越界的触摸忽略
        if (x < 0 || y < 0 || x > mapView.getWidth() || y > mapView.getHeight()) {
            return;
        }
        final IGeoPoint ig = mapView.getProjection().fromPixels((int) x, (int) y);
        if (ig == null) {
            return;
        }
        addFreehand(new GeoPoint(ig.getLatitude(), ig.getLongitude()));
    }

    @Override
    protected void onMapTap(GeoPoint p) {
        if (mode == MODE_POLY) {
            // 只有点过「开始设点」(polyAdding) 后，点地图才加节点；
            // 没进入设点态时点地图什么都不做 —— 避免误触乱加节点。
            if (polyAdding) {
                addPolylineNode(p);
            }
        }
        // FREE 模式由 OnTouchListener（setupDrawTouch）消费触摸，这里忽略单击
    }

    @Override
    protected void onCenterClicked() {
        if (mapView == null) return;
        TrackEngine engine = activity().getTrackEngine();
        if (engine.nodeCount() >= 1) {
            TrackEngine.TrackPoint s = engine.getNode(0);
            mapView.getController().animateTo(toMapPoint(s.lat, s.lng));
        } else {
            toast("还没有路线，先画一条");
        }
    }

    /**
     * 轨迹页禁止「自动回真实位置」。
     *
     * <p>基类 {@link BaseMapFragment#centerOnRealIfIdle} 会在每次 onResume / 收到真实定位时，
     * 把地图中心拽回真实 GPS 坐标。对一个「模拟定位」App 来说这是致命的：用户画的路线在
     * 别处（假位置），一旦切到主页再切回轨迹页，地图就被拖到真实位置，路线 / 蓝点 / 绿线
     * 全跑到屏幕外，看起来就像「切页后蓝点 / 绿起点 / 绿轨迹消失了」（真机诊断日志证实：
     * 全程无 onDestroyView，即不是视图重建，而是中心被挪走）。
     *
     * <p>因此：只要已经有一条路线（{@code nodeCount() >= 1}），就认定用户正在看这条路线，
     * 绝不自动跳回真实位置；没有路线时才沿用基类「回真实位置」的默认行为。
     */
    @Override
    protected void centerOnRealIfIdle(boolean animate) {
        MainActivity act = (MainActivity) getActivity();
        // 有路线就认定用户在看这条路线，绝不自动跳回真实位置：
        // 否则切页回来路线 / 蓝点 / 绿线会被拽到屏幕外，看起来就像「消失了」。
        if (act != null && act.getTrackEngine().nodeCount() >= 1) {
            return;
        }
        // getActivity() 为空（极端时序）时，宁可什么都不做，也别退化到「跳回真实位置」
        // 把内容拽出屏幕 —— 那正是「切回轨迹页元素消失」的伪装表现之一。
        if (act == null) return;
        super.centerOnRealIfIdle(animate);
    }

    // ==================== 轨迹编辑 ====================

    /** 「开始画 / 结束画」开关：只有处于绘制中，OnTouchListener 才消费单指触摸。 */
    /**
     * 「开始 / 结束」按钮的总入口 —— 两种模式各走一条路：
     * 折线是「开始设点 / 结束设点」，画笔是「开始画 / 结束画」。
     */
    private void toggleDraw() {
        if (mode == MODE_FREE) {
            toggleFreehandDraw();
        } else {
            togglePolyAdding();
        }
    }

    /**
     * 折线模式的「开始设点 / 结束设点」。
     *
     * <p>它控制"点地图是否加节点"：只有 {@code polyAdding == true}（点过「开始设点」后）
     * 点地图才会加节点；没点「开始设点」时点地图什么都不做（见 {@link #onMapTap}）。
     * 配合把控制面板收起、只留一个「结束设点」悬浮窗，让地图整屏可用，手感与画笔模式对齐。
     */
    private void togglePolyAdding() {
        polyAdding = !polyAdding;
        updateDrawButton();
        updateTrackHint();
        setDrawingUi(polyAdding);
        toast(polyAdding
                ? "开始设点：点地图依次添加节点，设完点「结束设点」"
                : "结束设点");
    }

    /** 画笔模式的「开始画 / 结束画」。 */
    private void toggleFreehandDraw() {
        TrackEngine engine = activity().getTrackEngine();
        boolean now = !engine.isDrawing();
        engine.setDrawing(now);
        if (!now) {
            engine.smoothRoute();   // 结束画：对采集到的毛刺做低通平滑，避免蓝点跟着锯齿乱转
            redrawRoute();
            onRouteChanged();       // 路线变了，暂停中的进度作废
        }
        updateDrawButton();
        updateTrackHint();
        setDrawingUi(now);
        toast(now ? "开始画：按住地图拖动绘制，画完点「结束画」" : "结束画：已平滑路线");
    }

    /**
     * 绘制中的界面切换。
     *
     * <p>点「开始画」后把整个控制面板（{@link #cardTrack}）隐藏，只留一个
     * 「结束画」迷你悬浮窗（{@link #cardDrawEnd}）浮在底部 —— 手绘时地图不被面板遮挡；
     * 点「结束画」再把面板恢复回来。
     */
    private void setDrawingUi(boolean drawing) {
        if (cardTrack != null) {
            cardTrack.setVisibility(drawing ? View.GONE : View.VISIBLE);
        }
        if (cardDrawEnd != null) {
            cardDrawEnd.setVisibility(drawing ? View.VISIBLE : View.GONE);
            // 这个悬浮窗是两种模式共用的，文案要跟着模式变
            if (drawing) {
                TextView label = cardDrawEnd.findViewById(R.id.btn_draw_end);
                if (label != null) {
                    label.setText(mode == MODE_FREE ? "结束画" : "结束设点");
                }
            }
        }
    }

    /**
     * 刷新「开始 / 结束」那颗按钮。
     *
     * <p>两种模式都要用它，只是文案不同：画笔是「开始画 / 结束画」，
     * 折线是「开始设点 / 结束设点」。按下后整个控制面板会收起、
     * 只留一个迷你悬浮窗（见 {@link #setDrawingUi}），这样点地图时不会被面板挡住。
     */
    private void updateDrawButton() {
        if (btnDrawToggle == null) return;
        final TrackEngine engine = activity().getTrackEngine();
        final boolean free = mode == MODE_FREE;

        // 两种模式都显示。只有画笔模式会真正"消费单指触摸"（见 setupDrawTouch），
        // 折线模式的设点靠的是普通的单击事件，不受影响。
        btnDrawToggle.setVisibility(View.VISIBLE);
        btnDrawToggle.setEnabled(true);

        final boolean active = free ? engine.isDrawing() : polyAdding;
        btnDrawToggle.setText(free
                ? (active ? "结束画" : "开始画")
                : (active ? "结束设点" : "开始设点"));
        btnDrawToggle.setTextColor(ContextCompat.getColor(requireContext(),
                active ? R.color.state_ok_text : R.color.brand_primary));
    }

    private void addPolylineNode(GeoPoint p) {
        double[] w = CoordUtil.gcj02ToWgs84(p.getLatitude(), p.getLongitude());
        activity().getTrackEngine().addNode(w[0], w[1]);
        redrawRoute();
        updateTrackHint();
        onRouteChanged();
        toast("已添加节点，共 " + activity().getTrackEngine().nodeCount() + " 个");
    }

    /**
     * 画笔采样：与上一节点相距 >=0.8m 才记一个点。
     * 采集时密一点保证曲线保真，多余的密集点在「结束画」时由
     * {@link TrackEngine#smoothRoute(double)} 统一做低通 + 抽稀 + 移动平均，
     * 既不会爆点，也不会画成锯齿。
     */
    private void addFreehand(GeoPoint g) {
        if (mapView == null) return;
        TrackEngine engine = activity().getTrackEngine();
        if (engine.nodeCount() == 0) {
            engine.addNodeGcj(g.getLatitude(), g.getLongitude());
        } else {
            TrackEngine.TrackPoint last = engine.getNode(engine.nodeCount() - 1);
            double[] w = CoordUtil.gcj02ToWgs84(g.getLatitude(), g.getLongitude());
            if (haversineWgs(last.lat, last.lng, w[0], w[1]) >= 0.8) {
                engine.addNodeGcj(g.getLatitude(), g.getLongitude());
            }
        }
        redrawRoute();
    }

    /**
     * 把点集写进指定图层；如果那个 Polyline 已经失效，就重建一个再写。
     *
     * <h4>为什么必须这样写</h4>
     * 用 {@code javap} 反编译 osmdroid 6.1.18 确认：{@code PolyOverlayWithIW.setPoints()}
     * 的第一条指令就是 {@code mOutline.setPoints(points)}，<b>没有任何 null 检查</b>；
     * 而 {@code mOutline} 全类只在 {@code onDetach()} 里被置空（{@code usePath()} 是重建）。
     * 也就是说：一个 Polyline 只要经历过 {@code MapView.onDetach()}，它就"半死"了 ——
     * 偏偏从外部又探测不到（{@code mOutline} 是 protected，跨包访问不到）。
     *
     * <p>而 {@code MapView.onDetach()} 的触发时机与 ViewPager2 的 Fragment 生命周期交织
     * （切页、重建视图都会踩到），很难用静态规则一一堵死。与其猜时序，不如在每次写点的
     * 时候自愈：抛出 NPE 就说明这个对象废了 —— 从地图上摘掉、换一个新的、重写一次。
     *
     * @param isRoute true = 规划路线（蓝），false = 实际轨迹（绿）
     * @return 真正写成功的图层；{@code null} 表示连重建都没成功（本帧跳过即可）
     */
    @Nullable
    private Polyline writePoints(boolean isRoute, List<GeoPoint> pts) {
        Polyline line = isRoute ? routeLine : trailLine;
        if (line != null) {
            try {
                line.setPoints(pts);
                return line;
            } catch (NullPointerException broken) {
                // 内部 LinearRing 已被清空 —— 摘掉这个"半死"对象，下面重建
                try {
                    if (mapView != null) {
                        mapView.getOverlays().remove(line);
                    }
                } catch (Throwable ignored) { }
            } catch (Throwable t) {
                return null;
            }
        }
        if (mapView == null) {
            return null;
        }
        try {
            line = isRoute ? newRouteLine() : newTrailLine();
            mapView.getOverlays().add(line);
            line.setPoints(pts);
        } catch (Throwable t) {
            return null;
        }
        if (isRoute) {
            routeLine = line;
        } else {
            trailLine = line;
        }
        return line;
    }

    private void redrawRoute() {
        // 与 redrawTrail 同理：视图可能已被 ViewPager2 销毁，而回调还在继续。
        // 注意：不能用 getView()==null 把关 —— onCreateView 里为了"切回轨迹页立刻补画"而调用本方法时，
        // getView() 仍是 null（视图还没 return），会被误杀，导致切页回来路线/起终点不显示。
        if (!isAdded() || mapView == null) return;
        TrackEngine engine = activity().getTrackEngine();
        List<GeoPoint> pts = new ArrayList<>();
        for (TrackEngine.TrackPoint t : engine.getSmooth()) {
            pts.add(toMapPoint(t.lat, t.lng));
        }
        if (writePoints(true, pts) == null) return;   // 图层不可用：本帧连起终点标记也跳过
        // 起终点标记
        if (startMarker != null) {
            if (engine.nodeCount() >= 1) {
                TrackEngine.TrackPoint s = engine.getNode(0);
                startMarker.setPosition(toMapPoint(s.lat, s.lng));
                startMarker.setVisible(true);
            } else {
                startMarker.setVisible(false);
            }
        }
        if (endMarker != null) {
            if (engine.nodeCount() >= 2) {
                TrackEngine.TrackPoint e = engine.getNode(engine.nodeCount() - 1);
                endMarker.setPosition(toMapPoint(e.lat, e.lng));
                endMarker.setVisible(true);
            } else {
                endMarker.setVisible(false);
            }
        }
        if (mapView != null) mapView.invalidate();
    }

    /**
     * 用共享的实际轨迹（含抖动）重画绿线。
     *
     * <p>第一行的「视图还在不在」检查是必须的：轨迹 tick 挂在 {@code MainActivity} 上，
     * 与 Fragment 生命周期无关。切页时 ViewPager2 会销毁离屏页的视图，而 tick 仍在每秒回调
     * —— 此时 osmdroid 的 Polyline 内部 LinearRing 已被 {@code MapView.onDetach()} 清空，
     * {@code setPoints()} 会直接抛 NullPointerException。
     * （真机崩溃栈：TrackFragment.redrawTrail → PolyOverlayWithIW.setPoints）
     */
    private void redrawTrail() {
        // 不能用 getView()==null 把关（见 redrawRoute 注释）：onCreateView 里补画时 getView() 仍为 null，
        // 会被误杀，导致切页回来已走过的绿线不显示。
        if (!isAdded() || mapView == null) return;
        List<GeoPoint> pts = new ArrayList<>();
        for (TrackEngine.TrackPoint t : activity().getSharedTrail()) {
            pts.add(toMapPoint(t.lat, t.lng));
        }
        if (writePoints(false, pts) == null) return;
        mapView.invalidate();
    }

    private void onStartTrack() {
        TrackEngine engine = activity().getTrackEngine();
        if (!engine.canStart()) {
            toast("请先画一条至少 2 个点的路线");
            return;
        }
        // 收起键盘，避免软键盘把控制区顶飞
        hideKeyboard();

        double kmh;
        try {
            kmh = Double.parseDouble(etSpeed.getText().toString().trim());
        } catch (Exception e) {
            kmh = 8;
        }
        // 合理范围约束：0.5 ~ 30 km/h（慢走~快骑）。超出则夹取并提示，
        // 避免像 "80" 这种速度导致每秒位移远大于抖动，轨迹在屏幕上只是瞬移。
        double clamped = Math.max(0.5, Math.min(30.0, kmh));
        if (Math.abs(clamped - kmh) > 1e-6) {
            etSpeed.setText(String.format(Locale.CHINA, "%.1f", clamped));
            etSpeed.setSelection(etSpeed.getText().length());
            toast(String.format(Locale.CHINA, "配速已限制到 %.1f km/h（有效范围 0.5 ~ 30）", clamped));
        }
        kmh = clamped;

        // 清空上次残留的真实轨迹，避免和上一次叠加
        activity().clearSharedTrail();
        redrawTrail();
        boolean ok = activity().startTrack(kmh);
        if (ok) {
            if (playbackMarker != null) {
                playbackMarker.setPosition(toMapPoint(engine.getNode(0).lat, engine.getNode(0).lng));
                playbackMarker.setVisible(true);
            }
            if (mapView != null) mapView.invalidate();
            toast("轨迹模拟开始（配速 " + kmh + " km/h）");
            updateTrackButton();   // 开始跑 → 按钮变「停止」
        } else {
            toast("未能开始：请先在开发者选项中授权本应用为模拟位置应用");
        }
    }

    /** 收起软键盘，防止键盘把底部控制区顶出屏幕。 */
    private void hideKeyboard() {
        View focus = requireActivity().getCurrentFocus();
        if (focus != null) {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            requireActivity().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    // ==================== 「开始 / 停止 / 继续」三态按钮 ====================

    /** 按钮的三个状态。 */
    private static final int BTN_START = 0;    // 还没开始 / 已跑完 / 已清空
    private static final int BTN_STOP = 1;     // 正在跑
    private static final int BTN_RESUME = 2;   // 中途暂停，可以继续

    /**
     * 由 {@link TrackEngine} 的状态推出按钮现在该是哪个态。
     *
     * <p>判据说明：光看 {@code isRunning()} 不够 —— 还要把「从没跑过 / 已跑完」
     * 与「跑到一半暂停」区分开。后者要求「有进度（{@code distM > 0}）且没跑完且路线还在」，
     * 三条同时成立才说明是暂停态、可以继续。
     */
    private int trackButtonState() {
        final TrackEngine e = activity().getTrackEngine();
        if (e.isRunning()) {
            return BTN_STOP;
        }
        if (!e.isFinished() && e.canStart() && e.distanceM() > 0.5) {
            return BTN_RESUME;
        }
        return BTN_START;
    }

    /** 把按钮文字刷成当前状态。 */
    private void updateTrackButton() {
        if (btnTrackStart == null) return;
        switch (trackButtonState()) {
            case BTN_STOP:
                btnTrackStart.setText("停止");
                break;
            case BTN_RESUME:
                btnTrackStart.setText("继续");
                break;
            default:
                btnTrackStart.setText("开始");
                break;
        }
    }

    /** 点一下那个三态按钮。 */
    private void onTrackButton() {
        switch (trackButtonState()) {
            case BTN_STOP:
                activity().pauseTrack();
                toast("已停止：位置停在原地，点「继续」接着跑");
                break;
            case BTN_RESUME:
                activity().resumeTrack();
                toast("继续模拟");
                break;
            default:
                onStartTrack();
                break;
        }
        updateTrackButton();
        updateTrackHint();
    }

    /**
     * 路线被改动后调用（撤销 / 新增节点 / 结束画）。
     *
     * <p>如果此刻正处在「暂停」态，进度已经和新路线对不上（{@code distM} 是按旧路线算的），
     * 继续跑只会错乱 —— 直接作废回「开始」。
     */
    private void onRouteChanged() {
        final TrackEngine e = activity().getTrackEngine();
        if (!e.isRunning() && !e.isFinished()) {
            activity().clearTrackProgress();
        }
        updateTrackButton();
    }

    private void updateProgress(double dist, double total) {
        if (tvTrackProgress == null) return;
        double v = activity().getTrackEngine().speedKmh();
        double pct = total > 0 ? dist / total * 100 : 0;
        double remSec = v > 0 ? (total - dist) / (v / 3.6) : 0;
        tvTrackProgress.setText(String.format(Locale.CHINA,
                "里程 %.0f / %.0f m (%.0f%%) · 剩余 ≈ %.0f s", dist, total, pct, remSec));
    }

    private void updateTrackHint() {
        if (tvTrackProgress == null || etSpeed == null) return;
        if (activity().getTrackEngine().isRunning()) return; // 运行中由 updateProgress 接管
        TrackEngine engine = activity().getTrackEngine();
        String modeName = mode == MODE_POLY ? "折线" : "画笔";
        String state;
        if (mode == MODE_FREE) {
            state = engine.isDrawing() ? "绘制中…" : "点「开始画」后可拖动绘制";
        } else {
            state = polyAdding ? "设点中…依次点地图添加节点" : "点「开始设点」后可连续设点";
        }
        tvTrackProgress.setText(String.format(Locale.CHINA,
                "[%s] %s · 路线 %d 点 · 总长 %.0f m · 配速 %s km/h",
                modeName, state, engine.nodeCount(), engine.totalLengthM(),
                etSpeed.getText().toString().trim()));
    }

    // ==================== 工具 ====================

    /** 两 WGS-84 点大圆距离（米） */
    private double haversineWgs(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)));
    }

    @Override
    public void onResume() {
        super.onResume();
        DiagLog.d("TrackFragment.onResume isAdded=" + isAdded() + " mapView=" + (mapView != null));
        try {
            if (!isAdded()) return;
            if (mapView == null) return;   // 视图尚未创建（ViewPager2 时序），onCreateView 里会做
            activity().addTrailListener(trailListener);
            // 三个重画各自独立兜底（见 redrawAll），一个失败不连累另外两个
            redrawAll();
            // 兜底：onResume 里的 invalidate 在某些 ROM / 时序下仍可能被合并丢弃，
            // 延后 250ms 再画一次，保证切回轨迹页后一定渲染出来。
            if (mapView != null) mapView.postDelayed(() -> redrawAll(), 250);
            if (!activity().getTrackEngine().isRunning()) updateTrackHint();
            // 切走再切回时，绘制状态可能仍是「绘制中」——把面板/悬浮窗显隐同步回来，
            // 否则会出现"面板不见了但也没在画"的错位。
            setDrawingUi(polyAdding || activity().getTrackEngine().isDrawing());
            updateTrackButton();
        } catch (Throwable t) {
            DiagLog.e("TrackFragment.onResume swallowed", t);
        }
    }

    @Override
    public void onDestroyView() {
        // 移除监听器不能依赖 isAdded()：ViewPager2 销毁离屏页时，Fragment 可能已经
        // 处于 detached 状态（isAdded() == false），那样这次移除会被整个跳过，
        // 回调随后继续打到已销毁的视图上 —— 这正是"轨迹运行中切页闪退"的成因。
        // getActivity() 不要求 added 状态，判空就足够。
        final MainActivity act = (MainActivity) getActivity();
        DiagLog.d("TrackFragment.onDestroyView attached=" + (act != null)
                + " lastTP=" + (act != null && act.getLastTrackPoint() != null)
                + " trail=" + (act != null ? act.getSharedTrail().size() : -1)
                + " nodeCount=" + (act != null ? act.getTrackEngine().nodeCount() : -1));
        if (act != null) {
            try {
                act.removeTrailListener(trailListener);
            } catch (Throwable ignored) { }
            try {
                // tick 回调同理：清空引用，避免视图销毁后仍被触发
                act.setTrackListener(null);
            } catch (Throwable ignored) { }
        }
        trailListener = null;

        // 先把自建图层从地图上摘掉，再清空字段。
        // 只清字段、却把对象留在 mapView.getOverlays() 里，会让随后的 MapView.onDetach()
        // 去清理一批"我们已经不再持有"的对象，顺序一旦不巧就是 NPE。
        try {
            if (mapView != null) {
                List<Overlay> ov = mapView.getOverlays();
                if (trailLine != null) ov.remove(trailLine);
                if (routeLine != null) ov.remove(routeLine);
                if (startMarker != null) ov.remove(startMarker);
                if (endMarker != null) ov.remove(endMarker);
                if (playbackMarker != null) ov.remove(playbackMarker);
            }
        } catch (Throwable ignored) { }

        routeLine = null;
        trailLine = null;
        startMarker = null;
        endMarker = null;
        playbackMarker = null;
        trackModeGrp = null;
        etSpeed = null;
        tvTrackProgress = null;
        btnDrawToggle = null;
        btnTrackStart = null;
        swTrail = null;
        cardTrack = null;
        cardDrawEnd = null;
        // 解绑一次性布局监听：视图已销毁，避免残留监听回调到空 MapView
        try {
            if (getView() != null) {
                getView().getViewTreeObserver().removeOnGlobalLayoutListener(redrawOnLayout);
            }
        } catch (Throwable ignored) { }
        super.onDestroyView();
    }
}
