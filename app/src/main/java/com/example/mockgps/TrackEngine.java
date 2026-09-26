package com.example.mockgps;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 轨迹模拟引擎（A 线：非 root，纯位置层逼真度）。
 *
 * <p>职责：维护一条由用户采集的路线（WGS-84 经纬度点序列），按给定配速沿路线前进，
 * 在每个 tick 算出「当前应当处于的坐标 + 速度 + 航向 + 精度抖动」，
 * 交给 MainActivity 的 mock 引擎当作模拟位置推出去。
 *
 * <p>逼真度覆盖：
 * <ul>
 *   <li>加速度剖面：起步加速段 / 匀速段 / 终点减速段，避免瞬间起停</li>
 *   <li>坐标平滑：画笔采集到的手抖毛刺在「结束画」时做低通平滑，避免蓝点跟着锯齿乱转</li>
 *   <li>精度抖动：平滑随机游走的噪声（贴着规划线轻微摆动，不会每帧瞬移）+ accuracy / 卫星数随机波动</li>
 *   <li>平地恒定海拔、单调时间戳（由调用方传入 nowNanos）</li>
 * </ul>
 *
 * <p>限制：本引擎只伪造「位置」。加速度计 / 陀螺仪 / 计步器等传感器无法在此伪造（非 root 不可行）。
 */
public class TrackEngine {

    /** 轨迹上的瞬时点。 */
    public static class TrackPoint {
        public double lat, lng;     // WGS-84
        public float bearing;       // 航向角（度，0=正北，顺时针）
        public float speed;         // m/s
        public float accuracy;      // 精度（米）
        public int satellites;      // 假卫星数

        public TrackPoint(double la, double ln) {
            lat = la;
            lng = ln;
        }
    }

    /** 每个 tick 的回调，用于驱动地图蓝点与进度 UI。 */
    public interface Listener {
        void onTick(TrackPoint p, double distM, double totalM, boolean finished);
    }

    private final List<TrackPoint> route = new ArrayList<>();
    private final List<TrackPoint> smooth = new ArrayList<>();
    private double totalLenM = 0;

    private double targetSpeed = 0;        // m/s
    private double accelDistM = 40;        // 加/减速段长度（米）

    private boolean running = false;
    private boolean finished = false;
    private long startNanos = 0;
    private long lastNanos = 0;
    private double distM = 0;              // 已走里程（米）
    private int tickCounter = 0;           // 用于控制 computeNext 日志频率

    private final Random rnd = new Random();
    /** GPS 抖动默认开启，但幅度只有十几厘米（真实手机静止时 GPS 漂移量级）。 */
    public static final boolean NOISE_ON_BY_DEFAULT = true;
    private boolean noiseOn = NOISE_ON_BY_DEFAULT;
    /**
     * 抖动幅度（米，1σ 左右的总包络）。默认 0.15m ≈ 真实跑步时 GPS 的十几厘米漂移，
     * 相当于「贴着规划线轻微起伏」，不会把轨迹画成锯齿或烟花。
     */
    private double noiseMeters = 0.15;
    private float accuracyBase = 8.0f;
    // 当前噪声偏移（度），采用平滑随机游走，避免每帧瞬移造成的“乱跳”
    private double noiseLat = 0;
    private double noiseLng = 0;

    /** 绘制中（画笔按下「开始画」后为 true）。绘制期间只加节点，不做任何插值/平滑处理。 */
    private boolean drawing = false;

    public void setDrawing(boolean d) {
        this.drawing = d;
    }

    public boolean isDrawing() {
        return drawing;
    }

    private Listener listener;

    public void setListener(Listener l) {
        BugTrace.event("TrackEngine", "setListener old=" + listenerHash(listener)
                + " new=" + listenerHash(l));
        this.listener = l;
    }

    private static String listenerHash(Listener l) {
        return l == null ? "null" : Integer.toHexString(l.hashCode());
    }

    // ============ 路线编辑 ============

    /** 加入一个 WGS-84 节点。 */
    public void addNode(double wgsLat, double wgsLng) {
        route.add(new TrackPoint(wgsLat, wgsLng));
        BugTrace.trace("TrackEngine", "addNode lat=" + wgsLat + " lng=" + wgsLng
                + " nodes=" + route.size());
        rebuild();
    }

    /** 加入一个 GCJ-02 节点（地图采集点），内部转 WGS-84。 */
    public void addNodeGcj(double gcjLat, double gcjLng) {
        double[] w = CoordUtil.gcj02ToWgs84(gcjLat, gcjLng);
        addNode(w[0], w[1]);
    }

    public void removeLast() {
        if (route.isEmpty()) return;
        route.remove(route.size() - 1);
        BugTrace.trace("TrackEngine", "removeLast nodes=" + route.size());
        rebuild();
    }

    public void clear() {
        route.clear();
        smooth.clear();
        totalLenM = 0;
        BugTrace.info("TrackEngine", "clear route");
    }

    public int nodeCount() { return route.size(); }

    public TrackPoint getNode(int i) { return route.get(i); }

    public List<TrackPoint> getSmooth() { return smooth; }

    /**
     * 对采集到的画笔路线做平滑与抽稀。
     *
     * <p>为什么要做：
     * <ul>
     *   <li>触摸事件频率高，慢速拖动会采出上千个极近的点（“爆点”），既卡又乱；</li>
     *   <li>手指/摇杆抖动会让相邻点来回摆，画出来是锯齿。</li>
     * </ul>
     *
     * <p>做法：先用「一阶低通 + 按最小间距抽稀」把抖动滤掉并压缩点数，
     * 再对整体做一次移动平均。这样既保留路线形状，又不会把刻意画的转角抹平。
     *
     * @param minSpacingM 相邻保留点的最小间距（米），建议 1.5~3m
     */
    public void smoothRoute(double minSpacingM) {
        if (route.size() < 3) return;
        BugTrace.info("TrackEngine", "smoothRoute before=" + route.size() + " minSpacing=" + minSpacingM);

        // 1) 一阶低通：alpha 越小越平滑。用 0.35 保留形状又滤掉手抖
        final double alpha = 0.35;
        List<TrackPoint> low = new ArrayList<>(route.size());
        double lat = route.get(0).lat, lng = route.get(0).lng;
        low.add(new TrackPoint(lat, lng));
        for (int i = 1; i < route.size(); i++) {
            lat += alpha * (route.get(i).lat - lat);
            lng += alpha * (route.get(i).lng - lng);
            low.add(new TrackPoint(lat, lng));
        }

        // 2) 抽稀：与上一个保留点相距 >= minSpacingM 才保留（末点强制保留）
        List<TrackPoint> thin = new ArrayList<>();
        thin.add(low.get(0));
        for (int i = 1; i < low.size() - 1; i++) {
            TrackPoint last = thin.get(thin.size() - 1);
            if (haversine(last, low.get(i)) >= minSpacingM) thin.add(low.get(i));
        }
        thin.add(low.get(low.size() - 1));

        // 3) 再做一次轻度移动平均（窗口 ±1），抹掉抽稀后残留的小折角
        List<TrackPoint> out = new ArrayList<>(thin.size());
        for (int i = 0; i < thin.size(); i++) {
            int a = Math.max(0, i - 1), b = Math.min(thin.size() - 1, i + 1);
            double sl = 0, sn = 0;
            for (int k = a; k <= b; k++) { sl += thin.get(k).lat; sn += thin.get(k).lng; }
            int c = b - a + 1;
            out.add(new TrackPoint(sl / c, sn / c));
        }

        route.clear();
        route.addAll(out);
        BugTrace.info("TrackEngine", "smoothRoute after=" + route.size());
        rebuild();
    }

    /** 兼容旧调用：默认 2m 最小间距。 */
    public void smoothRoute() {
        smoothRoute(2.0);
    }

    public void setNoiseMeters(double m) { noiseMeters = Math.max(0.05, m); }

    public void setNoiseOn(boolean on) {
        noiseOn = on;
        if (!on) {
            noiseLat = 0;
            noiseLng = 0;
        }
    }

    public boolean isNoiseOn() { return noiseOn; }

    public double getNoiseMeters() { return noiseMeters; }

    public boolean canStart() {
        return route.size() >= 2 && totalLenM > 1;
    }

    public double totalLengthM() { return totalLenM; }

    public boolean isRunning() { return running; }

    public boolean isFinished() { return finished; }

    public double distanceM() { return distM; }

    public double progressFraction() {
        return totalLenM > 0 ? Math.min(1.0, distM / totalLenM) : 0;
    }

    public double speedKmh() { return targetSpeed * 3.6; }

    // ============ 路线构建（总长） ============

    private void rebuild() {
        smooth.clear();
        // 画笔采的已经是密集点，再做贝塞尔圆角只会把轨迹拉歪，直接原样使用
        smooth.addAll(route);
        totalLenM = 0;
        for (int i = 1; i < smooth.size(); i++) {
            totalLenM += haversine(smooth.get(i - 1), smooth.get(i));
        }
    }

    /** 对每个内部节点做二次贝塞尔圆角，替换经过该节点的硬折角（仅用于折线模式，当前未启用）。 */
    @SuppressWarnings("unused")
    private void buildSmooth() {
        double r = 10.0; // 圆角半径（米），半径内用贝塞尔过渡
        smooth.add(route.get(0)); // 起点原样保留
        for (int i = 1; i < route.size() - 1; i++) {
            TrackPoint prev = route.get(i - 1);
            TrackPoint cur = route.get(i);
            TrackPoint next = route.get(i + 1);
            double dPrev = haversine(prev, cur);
            double dNext = haversine(cur, next);
            double rr = Math.min(r, Math.min(dPrev / 2.0, dNext / 2.0));
            if (rr < 1.0) {
                smooth.add(cur);
                continue;
            }
            TrackPoint p1 = lerpToward(cur, prev, rr / dPrev);
            TrackPoint p2 = lerpToward(cur, next, rr / dNext);
            int n = 6;
            for (int k = 1; k <= n; k++) {
                double t = (double) k / (n + 1);
                double u = 1 - t;
                double lat = u * u * p1.lat + 2 * u * t * cur.lat + t * t * p2.lat;
                double lng = u * u * p1.lng + 2 * u * t * cur.lng + t * t * p2.lng;
                smooth.add(new TrackPoint(lat, lng));
            }
        }
        smooth.add(route.get(route.size() - 1)); // 终点原样保留
    }

    /** 从 a 朝 b 方向前进 ratio 比例的点（小范围线性近似）。 */
    private TrackPoint lerpToward(TrackPoint a, TrackPoint b, double ratio) {
        return new TrackPoint(
                a.lat + (b.lat - a.lat) * ratio,
                a.lng + (b.lng - a.lng) * ratio);
    }

    // ============ 运行控制 ============

    public void start(double kmh) {
        if (!canStart()) {
            BugTrace.warn("TrackEngine", "start rejected canStart=false nodes=" + route.size() + " len=" + totalLenM);
            return;
        }
        targetSpeed = Math.max(0.1, kmh) / 3.6; // km/h -> m/s
        accelDistM = Math.min(40.0, totalLenM * 0.15);
        if (accelDistM < 5) accelDistM = totalLenM / 2.0;
        distM = 0;
        finished = false;
        // 每次开始重置噪声偏移，保证从干净状态起步
        noiseLat = 0;
        noiseLng = 0;
        startNanos = lastNanos = SystemClock.elapsedRealtimeNanos();
        running = true;
        BugTrace.event("TrackEngine", "START kmh=" + kmh + " totalM=" + (int) totalLenM
                + " accelM=" + (int) accelDistM + " listener=" + listenerHash(listener));
    }

    public void stop() {
        running = false;
        finished = true;
        BugTrace.event("TrackEngine", "STOP distM=" + (int) distM);
    }

    public void pause() {
        running = false;
        BugTrace.event("TrackEngine", "PAUSE distM=" + (int) distM);
    }

    public void resume() {
        if (finished) {
            BugTrace.warn("TrackEngine", "resume ignored because finished");
            return;
        }
        lastNanos = SystemClock.elapsedRealtimeNanos();
        running = true;
        BugTrace.event("TrackEngine", "RESUME distM=" + (int) distM);
    }

    /**
     * 推进并返回当前点。未运行或已结束时返回 null。
     * 用真实经过时间（nowNanos - lastNanos）算位移，保证配速均匀，不受 tick 抖动影响。
     */
    public TrackPoint computeNext(long nowNanos) {
        if (!running) return null;
        long dtMs = (nowNanos - lastNanos) / 1_000_000L;
        if (dtMs < 0) dtMs = 0;
        lastNanos = nowNanos;

        double v = speedAt(distM);                 // m/s
        distM += v * (dtMs / 1000.0);
        boolean fin = false;
        if (distM >= totalLenM) {
            distM = totalLenM;
            fin = true;
        }

        TrackPoint p = sampleAt(distM);
        p.speed = (float) v;
        p.bearing = bearingAt(distM);
        // 控制日志频率：每 5 次 tick 写一次状态，避免日志被刷爆
        tickCounter++;
        boolean shouldLog = fin || (tickCounter % 5 == 0);
        if (noiseOn) {
            // 抖动模型（关键：幅值必须远小于每 tick 位移，否则轨迹会画成“烟花”）。
            //
            // 1) 包络限制：最大偏移 maxDeg 取「设定抖动幅度」与「本 tick 位移的一半」中的较小值。
            //    低速时（如 0.36km/h 慢跑调试）位移很小，抖动随之一并变小，保证轨迹始终贴着规划线。
            // 2) 平滑随机游走：偏移量用 0.95 衰减 + 小幅高斯增量，不会每帧瞬移，看起来是连续小幅摆动。
            double stepM = v * (dtMs / 1000.0);
            double envelopeM = Math.min(noiseMeters, Math.max(0.03, stepM * 0.5));
            double maxDeg = envelopeM / 111_320.0;
            noiseLat = noiseLat * 0.95 + rnd.nextGaussian() * maxDeg * 0.35;
            noiseLng = noiseLng * 0.95 + rnd.nextGaussian() * maxDeg * 0.35;
            noiseLat = clamp(noiseLat, -maxDeg, maxDeg);
            noiseLng = clamp(noiseLng, -maxDeg, maxDeg);
            p.lat += noiseLat;
            p.lng += noiseLng;
            p.accuracy = Math.max(1.0f, (float) (accuracyBase + Math.abs(rnd.nextGaussian()) * 3.0));
            p.satellites = 7 + rnd.nextInt(6);     // 7~12
        } else {
            p.accuracy = accuracyBase;
            p.satellites = 10;
        }

        if (listener != null) listener.onTick(p, distM, totalLenM, fin);
        if (fin) {
            running = false;
            finished = true;
            BugTrace.event("TrackEngine", "FINISHED distM=" + (int) distM);
        }
        if (shouldLog) {
            BugTrace.trace("TrackEngine", "tick dtMs=" + dtMs + " distM=" + (int) distM
                    + " totalM=" + (int) totalLenM + " speed=" + String.format(Locale.US, "%.2f", v)
                    + " finished=" + fin + " listener=" + listenerHash(listener));
        }
        return p;
    }

    /**
     * 加速度剖面：起步加速 / 匀速 / 终点减速。
     *
     * <p><b>为什么不能从 0 速度起步</b>：本引擎按「已走里程 d」反推速度，
     * 若 d=0 时速度为 0，则每 tick 位移 = 0 × dt = 0，d 永远停在 0，
     * 速度永远为 0 —— 数学上无法自举，表现为「开始后原地不动」。
     * 因此加速段起点设为 targetSpeed 的 {@value #START_SPEED_RATIO}，保证每 tick 都有位移，
     * 之后线性升到目标速度，既自举得了，又保留了「起步不快」的运动学手感。
     */
    private static final double START_SPEED_RATIO = 0.35;

    private double speedAt(double d) {
        if (accelDistM <= 0) {
            // 路线极短：直接按目标速度，避免加速段吃满整条路线
            return targetSpeed;
        }
        if (d <= accelDistM) {
            // 加速段：0.35 → 1.0 线性升速
            double t = d / accelDistM;                  // 0..1
            return targetSpeed * (START_SPEED_RATIO + (1 - START_SPEED_RATIO) * t);
        }
        if (d >= totalLenM - accelDistM) {
            // 减速段：1.0 → 0.35，不停到 0，保证最后一 tick 仍有位移能真正到终点
            double remain = totalLenM - d;
            double t = Math.max(0, remain / accelDistM); // 1..0
            return targetSpeed * (START_SPEED_RATIO + (1 - START_SPEED_RATIO) * t);
        }
        return targetSpeed;
    }

    /** 在 smooth 折线上按里程 d 线性插值出坐标。 */
    private TrackPoint sampleAt(double d) {
        if (smooth.isEmpty()) return new TrackPoint(0, 0);
        if (d <= 0) return smooth.get(0);
        double acc = 0;
        for (int i = 1; i < smooth.size(); i++) {
            TrackPoint a = smooth.get(i - 1);
            TrackPoint b = smooth.get(i);
            double seg = haversine(a, b);
            if (seg < 1e-9) continue;
            if (acc + seg >= d) {
                double t = (d - acc) / seg;
                return new TrackPoint(
                        a.lat + (b.lat - a.lat) * t,
                        a.lng + (b.lng - a.lng) * t);
            }
            acc += seg;
        }
        return smooth.get(smooth.size() - 1);
    }

    /** 在 smooth 折线上按里程 d 求航向角（度）。 */
    private float bearingAt(double d) {
        if (smooth.size() < 2) return 0;
        double acc = 0;
        for (int i = 1; i < smooth.size(); i++) {
            TrackPoint a = smooth.get(i - 1);
            TrackPoint b = smooth.get(i);
            double seg = haversine(a, b);
            if (seg < 1e-9) continue;
            if (acc + seg >= d) return bearing(a, b);
            acc += seg;
        }
        return bearing(smooth.get(smooth.size() - 2), smooth.get(smooth.size() - 1));
    }

    // ============ 几何 ============

    /** 两点间大圆距离（米）。 */
    private double haversine(TrackPoint a, TrackPoint b) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(b.lat - a.lat);
        double dLng = Math.toRadians(b.lng - a.lng);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)));
    }

    /** 航向角（度，0=正北，顺时针）。 */
    private float bearing(TrackPoint a, TrackPoint b) {
        double rad = Math.PI / 180.0;
        double lat1 = a.lat * rad, lat2 = b.lat * rad;
        double dLng = (b.lng - a.lng) * rad;
        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (float) ((deg + 360) % 360);
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
