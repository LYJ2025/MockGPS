package com.example.mockgps;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断日志：把「轨迹页切页重建 → 补画」全过程的每一步写进磁盘，便于在真机上复现后取回分析。
 *
 * <p>复现 v1.23 / v1.24「切回轨迹页蓝点 / 绿起点 / 绿轨迹消失」时，到底卡在哪一环
 * （重建后补画太早导致 MapView 还没尺寸、还是某次重画抛了异常被吞），光靠猜很容易跑偏。
 * 装上这个后，轨迹页的每次 onCreateView / onResume / redrawAll / onDestroyView 都会留下带
 * 时间戳的记录：地图宽高（确认是否还没完成布局）、各图层是否为空、路线 / 轨迹点数、
 * 每个标记最终 visible、任何异常的完整堆栈。
 *
 * <p>三个写入位置（与 {@link CrashLogger} 一致）：
 * <ol>
 *   <li>应用专属目录 {@code /Android/data/com.example.mockgps/files/diag.txt} —— adb pull 可拿；</li>
 *   <li>公共下载目录 {@code /Download/MockGPS-diag/diag.txt} —— 所有手机自带文件管理器直接可见；</li>
 *   <li>logcat（TAG = {@code MockGPS-DIAG}）—— 开发者用 adb logcat 看。</li>
 * </ol>
 *
 * <p>仅记录在「生命周期 / 重画触发」这类低频节点上；轨迹 tick 每秒重画绿线那一步不记，
 * 避免日志被刷爆。
 */
public final class DiagLog {

    private static final String TAG = "MockGPS-DIAG";
    private static final int MAX_LINES = 400;
    private static final ArrayDeque<String> buf = new ArrayDeque<>();
    private static final Object lock = new Object();
    private static Context appCtx;

    private DiagLog() {
    }

    /** 在 Application / Activity 最早时机调用一次（与 CrashLogger.install 同处）。 */
    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        d("DiagLog.init version=" + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")");
    }

    /** 普通诊断行。 */
    public static void d(String msg) {
        line(msg);
    }

    /** 带完整堆栈的错误行（异常被吞掉前用它把真相留下来）。 */
    public static void e(String msg, Throwable t) {
        line(msg + (t != null ? "\n" + Log.getStackTraceString(t) : ""));
    }

    private static void line(String s) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String full = ts + " " + s;
        Log.d(TAG, s);
        synchronized (lock) {
            buf.addLast(full);
            while (buf.size() > MAX_LINES) {
                buf.removeFirst();
            }
        }
        appendApp(full);
        flushPublic();
    }

    /** 追加到应用专属目录（按大小滚动，避免无限增长）。 */
    private static void appendApp(String line) {
        try {
            Context ctx = appCtx;
            if (ctx == null) return;
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            if (dir == null) return;
            if (!dir.exists() && !dir.mkdirs()) return;
            File f = new File(dir, "diag.txt");
            if (f.exists() && f.length() > 300 * 1024) {
                // noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            try (FileWriter fw = new FileWriter(f, true)) {
                fw.write(line + "\n");
            }
        } catch (Throwable ignored) {
            // 记录失败绝不能影响主流程
        }
    }

    /**
     * 把内存环形缓冲整份刷到公共下载目录下的 MockGPS-diag/diag.txt。
     *
     * <p>两档回退（逐档尝试，与 CrashLogger 同）：
     * <ul>
     *   <li>API 29+：走 MediaStore.Downloads；</li>
     *   <li>API 19-28：直接用 Environment.getExternalStoragePublicDirectory + File API；</li>
     *   <li>都不行：跳过（应用专属目录已经有副本，不阻断）。</li>
     * </ul>
     */
    private static void flushPublic() {
        final String text;
        synchronized (lock) {
            // 不用 String.join（Java 8 API，minSdk 21 下 API < 26 会 NoSuchMethodError）
            StringBuilder sb = new StringBuilder();
            for (String s : buf) {
                sb.append(s).append('\n');
            }
            text = sb.toString();
        }
        try {
            Context ctx = appCtx;
            if (ctx == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, "diag.txt");
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/MockGPS-diag");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri collection = MediaStore.Downloads
                        .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = ctx.getContentResolver().insert(collection, cv);
                if (item == null) return;

                try (OutputStream os = ctx.getContentResolver().openOutputStream(item, "w")) {
                    if (os != null) {
                        os.write(text.getBytes("UTF-8"));
                        os.flush();
                    }
                }
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                ctx.getContentResolver().update(item, cv, null, null);
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "MockGPS-diag");
                if (!dir.exists() && !dir.mkdirs()) return;
                writeFile(new File(dir, "diag.txt"), text);
            }
        } catch (Throwable t) {
            Log.w(TAG, "flushPublic failed: " + t.getMessage());
        }
    }

    private static void writeFile(File f, String text) {
        try (FileWriter fw = new FileWriter(f, false)) {
            fw.write(text);
        } catch (Exception ignored) {
        }
    }
}
