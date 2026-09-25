package com.example.mockgps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.osmdroid.tileprovider.MapTileProviderBase;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.util.TileLooper;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.TilesOverlay;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「矢量图强行放大」的正确实现。
 *
 * <h3>问题</h3>
 * 高德矢量 / 卫星瓦片只提供到 z{@value BaseMapFragment#AMAP_REAL_MAX_Z}。用户希望继续放大
 * （到 z22）以看清路网细节。此前的错误做法是把 URL 里的 z 改成 18 直接取出整张 z18 瓦片，
 * 而 osmdroid 会把这**一整张**图铺进当前层级的**每一格**里 —— 视觉上就变成"同一张地图
 * 无限重复平铺"，而不是放大。
 *
 * <h3>osmdroid 的真实绘制机制（已反编译 6.1.18 确认）</h3>
 * <pre>
 * TilesOverlay.drawTiles()
 *   └─ OverlayTileLooper.loop(zoom, viewPort, canvas)      // TileLooper.loop
 *        mTileZoomLevel = floor(zoom)                      // 瓦片层级
 *        for x,y in 覆盖视口的格号(该层级, 未取模):
 *            tileIndex = MapTileIndex.getTileIndex(mTileZoomLevel, mod(x,W), mod(y,W))
 *            handleTile(tileIndex, x, y)
 *   └─ handleTile():
 *        drawable = provider.getMapTile(tileIndex)
 *        projection.getPixelFromTile(x, y, rect)            // rect = 该格在屏幕上的像素矩形
 *        onTileReadyToDraw(canvas, drawable, rect):
 *            drawable.setBounds(rect); drawable.draw(canvas)   // ← 整张图缩放进该格
 * </pre>
 * 其中 {@code Projection.mTileSize = TileSystem.getTileSize(zoom) = 256 * 2^(zoom - floor(zoom))}。
 *
 * <h3>正确做法</h3>
 * 当层级超过源的真实上限时，把 z18 那张图**裁剪**出属于当前格的 1/2^k 区域，再缩放到该格，
 * 而不是整张铺进去。裁剪数学取自 osmdroid 自身的
 * {@code MapTileApproximater.approximateTileFromLowerZoom(BitmapDrawable, long, int)}：
 * <pre>
 *   subSize = 源图边长 >> k
 *   srcX = (tileX % 2^k) * subSize
 *   srcY = (tileY % 2^k) * subSize
 *   src  = Rect(srcX, srcY, srcX + subSize, srcY + subSize)
 *   dst  = Rect(0, 0, 源图边长, 源图边长)
 * </pre>
 * 这样 2^k × 2^k 个格子各自取到 z18 图的不同 1/2^k 区域，拼起来就是一张完整的
 * z18 图被整体等比放大 2^k 倍 —— 线条变粗、像素变糊，但路网位置准确、不重复。
 *
 * <h3>为什么不用默认的 OverlayTileLooper</h3>
 * {@code OverlayTileLooper} 是 {@link TilesOverlay} 的 private 内部类、且
 * {@code onTileReadyToDraw(Canvas, Drawable, Rect)} 收不到 tileIndex，无法得知该画源的哪一块。
 * 因此这里用自定义 {@link TileLooper}（public，可继承）重写 {@code drawTiles}，
 * 自己握有 tileIndex 与原始格号 x/y，裁剪信息完备。
 */
public class ScalingTilesOverlay extends TilesOverlay {

    /** 当前瓦片源真正提供瓦片的最大层级（超出部分做裁剪放大）。 */
    private final int realMaxZ;

    /** 源瓦片位图边长（像素）。高德 = 256。 */
    private final int srcTileSize;

    /**
     * 源瓦片尚未就绪时，最多向更低层级回退几级去找一张可用的瓦片。
     * 低层级瓦片通常已经在内存/磁盘缓存里（用户是一路放大上来的），
     * 用它顶上可以避免放大瞬间出现大片空白。
     */
    private static final int MAX_FALLBACK = 3;

    private final Rect mTmpRect = new Rect();
    private final Rect mSrcCrop = new Rect();
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** 自有源瓦片副本的 LRU 容量。16 张 × 256KB ≈ 4MB；放大到 z22 时屏幕最多用到 2~6 张。 */
    private static final int MAX_SOURCE_CACHE = 16;

    /**
     * 源瓦片位图的<b>自有副本</b>缓存（LRU，accessOrder = true）。
     *
     * <h4>为什么非要自己留一份</h4>
     * 超限层级每一帧都要拿源瓦片（z18）来裁剪放大，而
     * {@code mTileProvider.getMapTile()} 返回的是 osmdroid 的
     * {@code ReusableBitmapDrawable} —— 它受内存 LRU 与 BitmapPool 管理，
     * <b>随时可能被驱逐或回收</b>。
     *
     * <p>一旦某一帧拿不到有效位图，这一格就只能留空；而 {@code MapView} 每帧会先铺一层
     * 背景色再画瓦片 —— 于是屏幕上出现"<b>一闪一闪的白</b>"，正是真机反馈的现象
     * （放大到比例尺 ≤ 60m 时开始闪）。
     *
     * <p>把成功取到的源瓦片<b>拷贝一份</b>留在自己手里之后：某个源瓦片只要成功取到过一次，
     * 之后每一帧都命中本地缓存，完全不受 osmdroid 缓存策略影响。这是这个问题的根治点。
     *
     * <p>代价：最多 4MB 内存，且切换地图源时必须 {@link #clearSourceCache()}（否则会把
     * 上一个图源的像素当成新图源的用）。
     */
    private final LinkedHashMap<Long, Bitmap> mSourceCache =
            new LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Bitmap> eldest) {
                    if (size() > MAX_SOURCE_CACHE) {
                        recycleQuietly(eldest.getValue());
                        return true;
                    }
                    return false;
                }
            };

    /** 本 overlay 使用的 TileLooper：在标准遍历基础上加入「超限层级裁剪」。 */
    private final ScalingTileLooper mLooper = new ScalingTileLooper();

    /**
     * 自定义遍历器。
     *
     * <p>继承 osmdroid 的 {@link TileLooper} 以复用其「视口 → 格号范围 → 逐格回调」逻辑；
     * 唯一区别在 {@link #handleTile} 里把 tileIndex 连同原始格号一起交给本类，
     * 由本类决定是原生绘制还是裁剪绘制。
     */
    private final class ScalingTileLooper extends TileLooper {
        Canvas canvas;

        ScalingTileLooper() {
            // 与 TilesOverlay 默认一致：关闭水平/垂直重复
            super(false, false);
        }

        /** 暴露 TileLooper 的 protected loop()（外部类不是它的子类，无法直接调）。 */
        void run(Canvas c, double zoom, org.osmdroid.util.RectL viewPort) {
            canvas = c;
            loop(zoom, viewPort);
            canvas = null;
        }

        @Override
        public void handleTile(long tileIndex, int x, int y) {
            onHandleTile(canvas, tileIndex, x, y, mTileZoomLevel);
        }
    }

    public ScalingTilesOverlay(@NonNull MapTileProviderBase provider,
                              @NonNull Context context,
                              int realMaxZ,
                              int srcTileSize,
                              boolean horizontalWrap,
                              boolean verticalWrap) {
        super(provider, context, horizontalWrap, verticalWrap);
        this.realMaxZ = realMaxZ;
        this.srcTileSize = srcTileSize;
        setLoadingBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    public void drawTiles(@NonNull Canvas canvas, @NonNull Projection projection,
                          double zoom, @NonNull org.osmdroid.util.RectL viewPort) {
        setProjection(projection);
        mLooper.run(canvas, zoom, viewPort);
    }

    /**
     * 单个瓦片的绘制。层级未超限时与 osmdroid 默认行为完全一致；
     * 超限时改为「取源瓦片的对应 1/2^k 区域」。
     *
     * <p><b>v1.20 修「放大后闪烁并变全白」</b>：原来的兜底是
     * {@code mTileProvider.getMapTile(tileIndex)}，可那个 {@code tileIndex} 是
     * <b>当前层级（tz &gt; realMaxZ）</b>的索引 —— 而瓦片源只提供到 {@code realMaxZ}，
     * 请求它只会拿到占位图，并且因为缓存里永远存不下，<b>每一帧都会重新请求一次</b>，
     * 屏幕上就是"不停闪白"。
     *
     * <p>现在超限层级<b>一律不去请求 tz 层级的瓦片</b>：
     * 先试源真实最大层级，拿到就裁剪缩放；拿不到就往更低层级回退（最多 {@link #MAX_FALLBACK} 级）；
     * 全都没就绪就让这一格<b>留空</b>，下一帧再画。宁可短暂空白，也不要闪白。
     *
     * @param canvas    目标画布
     * @param tileIndex 当前层级下的瓦片索引（x/y 已按 2^tz 取模）
     * @param x         当前层级下的原始格号（未取模）—— 定位屏幕矩形要用它
     * @param y         同上
     * @param tz        当前瓦片层级 = floor(视图缩放)
     */
    private void onHandleTile(Canvas canvas, long tileIndex, int x, int y, int tz) {
        final Projection projection = getProjection();
        if (projection == null || canvas == null) return;

        if (tz <= realMaxZ) {
            // ---- 未超限：完全走 osmdroid 原生逻辑 ----
            Drawable d = mTileProvider.getMapTile(tileIndex);
            if (d == null) return;
            projection.getPixelFromTile(x, y, mTmpRect);
            onTileReadyToDraw(canvas, d, mTmpRect);
            return;
        }

        // ---- 超限：找一个「已就绪」的祖先层级瓦片，裁剪出本格区域后缩放填入 ----
        final int lowestZ = Math.max(0, realMaxZ - MAX_FALLBACK);
        for (int z = realMaxZ; z >= lowestZ; z--) {
            final int k = tz - z;
            if (k <= 0) break;

            // 原始格号（未取模）→ 该层级的格号：算术右移等价于 floor 除法（x/y 非负）
            final long srcIndex = MapTileIndex.getTileIndex(z, x >> k, y >> k);
            final Bitmap srcBmp = obtainSourceTile(srcIndex);
            if (srcBmp == null) {
                continue;   // 这一层还没下好，往更低层级试
            }

            final int sw = srcBmp.getWidth();
            final int sh = srcBmp.getHeight();
            final int subW = sw >> k;
            final int subH = sh >> k;
            if (subW <= 0 || subH <= 0) {
                continue;   // k 太大，裁出来的块为 0，换层级也一样，继续找
            }

            // 本格在该源瓦片内的第 (x & mask, y & mask) 个 1/2^k 小块
            final int mask = (1 << k) - 1;
            final int sx = (x & mask) * subW;
            final int sy = (y & mask) * subH;
            mSrcCrop.set(sx, sy, sx + subW, sy + subH);

            // 目标屏幕矩形 = 当前格（tz 层级）的像素矩形
            projection.getPixelFromTile(x, y, mTmpRect);
            canvas.drawBitmap(srcBmp, mSrcCrop, mTmpRect, mPaint);
            return;
        }

        // 所有候选层级都还没就绪：这一格留空，等下一帧。
        // ⚠️ 绝不要退回去请求 tz(> realMaxZ) 层级的瓦片 —— 源根本不提供那个层级，
        //    请求只会拿到占位图并不断重试，正是「放大后地图闪烁并变全白」的成因。
    }

    /** 尽力从 Drawable 取 Bitmap（ReusableBitmapDrawable 也继承自 BitmapDrawable）。 */
    private static Bitmap asBitmap(Drawable d) {
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) return b;
        }
        return null;
    }

    /** 源瓦片边长（供外部比例尺计算用）。 */
    public int getSrcTileSize() {
        return srcTileSize;
    }

    /** 当前源的真实最大层级。 */
    public int getRealMaxZoom() {
        return realMaxZ;
    }

    // ==================================================================
    // 源瓦片副本缓存（治"放大后闪烁白屏"的关键）
    // ==================================================================

    /**
     * 取一张可以安全绘制的源瓦片位图。
     *
     * <p>顺序：先查自有副本缓存 → 没有就向 osmdroid 要 → 要到就 <b>拷一份</b>存下来再返回。
     * 拷贝这一步是关键：osmdroid 给的是可复用位图，不拷贝就没法长期持有，
     * 而"拿不到图就留空"正是闪烁的来源。
     *
     * @param srcIndex 源层级（≤ {@link #realMaxZ}）的瓦片索引
     * @return 可安全绘制的位图；{@code null} 表示这一层还没就绪（调用方应跳过本格）
     */
    @Nullable
    private Bitmap obtainSourceTile(final long srcIndex) {
        final Bitmap cached = mSourceCache.get(srcIndex);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        final Bitmap fresh = asBitmap(mTileProvider.getMapTile(srcIndex));
        if (fresh == null) {
            return null;   // 还没下好，或给的是 loading 占位（非 BitmapDrawable）
        }

        Bitmap copy = null;
        try {
            copy = fresh.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            // OOM 之类：拷不了就先用这一帧的，只是下一帧还得再要一次
        }
        if (copy == null) {
            return fresh;
        }
        mSourceCache.put(srcIndex, copy);
        return copy;
    }

    /**
     * 清空自有副本缓存。
     *
     * <p><b>切换地图源时必须调用</b> —— 否则同一个瓦片索引会把上一个图源
     * （高德矢量 / 卫星影像）的像素继续当成新图源来用。
     */
    public void clearSourceCache() {
        for (Bitmap b : mSourceCache.values()) {
            recycleQuietly(b);
        }
        mSourceCache.clear();
    }

    private static void recycleQuietly(@Nullable Bitmap b) {
        if (b == null || b.isRecycled()) {
            return;
        }
        try {
            b.recycle();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDetach(@NonNull MapView mapView) {
        // 视图销毁时把副本缓存还给系统，别把 4MB 位图留在已废弃的 overlay 上
        clearSourceCache();
        super.onDetach(mapView);
    }
}
