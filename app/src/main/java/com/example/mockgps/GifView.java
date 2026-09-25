package com.example.mockgps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;

/**
 * 从 assets 播放 GIF 的自定义 View，不依赖任何三方库。
 *
 * <p>用 {@link android.graphics.Movie} 解码并逐帧绘制；宽高按 GIF 原始尺寸等比缩放，
 * 宽度填满父容器（或 {@link #setMaxWidth(int)} 指定的最大值），高度按比例跟着变。
 * 卸载（弹窗关闭 / View 脱离窗口）后不再自我 invalidate，循环自然停止。
 */
public class GifView extends View {

    private Movie movie;
    private long movieStart;
    private float scale = 1f;
    private boolean loaded;
    private int intrinsicW, intrinsicH;
    private int maxWidth = Integer.MAX_VALUE;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GifView(Context context) {
        super(context);
    }

    public GifView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 限制绘制的最大宽度（px）。设为 0 表示不限制（随父容器）。 */
    public void setMaxWidth(int px) {
        this.maxWidth = px > 0 ? px : Integer.MAX_VALUE;
        if (loaded) requestLayout();
    }

    /** 加载 assets 下的 GIF；失败则标记 loaded=false，onDraw 画占位提示。 */
    public void loadAsset(String assetName) {
        InputStream is = null;
        try {
            is = getContext().getAssets().open(assetName);
            movie = Movie.decodeStream(is);
            if (movie != null) {
                intrinsicW = movie.width();
                intrinsicH = movie.height();
                loaded = intrinsicW > 0 && intrinsicH > 0;
            }
        } catch (IOException e) {
            loaded = false;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                    // 关不掉也无所谓，流来自 assets，不会泄漏
                }
            }
        }
        if (loaded) {
            movieStart = 0;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int specW = MeasureSpec.getSize(widthMeasureSpec);
        int availW = Math.min(specW, maxWidth);
        if (!loaded || intrinsicW <= 0) {
            // 没加载成功：给一个稳妥的占位尺寸，避免弹窗塌成一条线
            int w = availW == Integer.MAX_VALUE ? 300 : availW;
            setMeasuredDimension(w, 180);
            return;
        }
        scale = (float) availW / (float) intrinsicW;
        int h = Math.round(intrinsicH * scale);
        setMeasuredDimension(availW, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!loaded || movie == null) {
            // 占位：居中提示
            paint.setColor(0xFF888888);
            paint.setTextSize(14f * getResources().getDisplayMetrics().density);
            paint.setTextAlign(Paint.Align.CENTER);
            String msg = "教程动图加载失败，请前往「设置 → 开发者选项」手动开启";
            Rect bounds = new Rect();
            paint.getTextBounds(msg, 0, msg.length(), bounds);
            canvas.drawText(msg,
                    getWidth() / 2f,
                    (getHeight() + bounds.height()) / 2f,
                    paint);
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (movieStart == 0) {
            movieStart = now;
        }
        int dur = movie.duration();
        if (dur <= 0) dur = 1000;
        int rel = (int) ((now - movieStart) % dur);
        movie.setTime(rel);

        canvas.save();
        canvas.scale(scale, scale);
        movie.draw(canvas, 0, 0);
        canvas.restore();

        // 循环播放：脱离窗口后 postInvalidateDelayed 不会再来，循环自然终止
        if (getWindowToken() != null) {
            postInvalidateDelayed(16);
        }
    }
}
