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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃记录器：把未捕获异常的完整堆栈写到磁盘，文件名固定为 crash_last.txt。
 *
 * <p><b>为什么需要它</b>：选点 / 轨迹 tab 闪退这类问题在开发机上无法复现，
 * 仅凭现象推测很容易猜错方向。装了这个后，只要用户复现一次，
 * 就能拿到精确到行号的堆栈，定位到真正的 NPE / IllegalStateException 来源。
 *
 * <p>三个写入位置（全部都写，最少一个能取到）：
 * <ol>
 *   <li>应用专属目录 <code>/sdcard/Android/data/com.example.mockgps/files/crash_last.txt</code>
 *       —— adb pull / 旧文件管理器可见；</li>
 *   <li>公共下载目录 <code>/sdcard/Download/MockGPS-crash/</code>
 *       —— <b>所有手机自带文件管理器都直接可见</b>，给用户看用这个；</li>
 *   <li>logcat（带时间戳 + 线程名 + 设备 + 完整堆栈）—— 开发者用 adb logcat 看。</li>
 * </ol>
 */
public final class CrashLogger {

    private static final String TAG = "MockGPS";
    private static final String FILE_LAST = "crash_last.txt";
    private static final int KEEP = 5;

    private CrashLogger() {
    }

    /** 在 Application / Activity 最早时机调用一次。重复调用无副作用。 */
    public static void install(final Context appContext) {
        final Context ctx = appContext.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                // 先把 BugTrace 队列中所有日志同步落盘，并把最近记录附加到崩溃报告
                BugTrace.flushSync();
                String text = buildReport(thread, throwable);
                Log.e(TAG, "uncaught exception:\n" + text);
                // 优先写应用专属目录
                writeAppFile(ctx, text);
                // 再尽力写公共下载目录（不同 API 等级走不同路径）
                writePublicDownload(ctx, text);
            } catch (Throwable ignored) {
                // 记录失败也绝不能影响原来的崩溃处理
            }
            if (prev != null) {
                prev.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(1);
            }
        });
    }

    private static String buildReport(Thread thread, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("time   : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.US).format(new Date()));
        pw.println("thread : " + thread.getName());
        pw.println("device : " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        pw.println("session : " + BugTrace.getSessionId());
        pw.println("---- recent trace (last 200 lines) ----");
        pw.println(BugTrace.recentJson(200));
        pw.println("---- stack ----");
        t.printStackTrace(pw);
        Throwable c = t.getCause();
        while (c != null) {
            pw.println("---- caused by ----");
            c.printStackTrace(pw);
            c = c.getCause();
        }
        pw.flush();
        return sw.toString();
    }

    private static void writeAppFile(Context ctx, String text) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        if (dir == null) return;
        if (!dir.exists() && !dir.mkdirs()) return;

        writeFile(new File(dir, FILE_LAST), text);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        writeFile(new File(dir, "crash_" + stamp + ".txt"), text);
        pruneHistory(dir);
    }

    /**
     * 把崩溃报告写一份到公共下载目录下的 MockGPS-crash/ 子文件夹。
     *
     * <p>两档回退（逐档尝试）：
     * <ul>
     *   <li>API 29+ (Android 10+)：走 MediaStore.Downloads，普通文件管理器都看得到；</li>
     *   <li>API 19-28：直接用 Environment.getExternalStoragePublicDirectory(DOWNLOADS)
     *       + File API 写入 —— Android 11+ 上会因为 scoped storage 被拒，但 9.0 之前可用；</li>
     *   <li>都不行：跳过（应用专属目录已经有副本，不阻断）。</li>
     * </ul>
     */
    private static void writePublicDownload(Context ctx, String text) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(ctx, text);
            } else {
                writeViaLegacyDir(text);
            }
        } catch (Throwable t) {
            Log.w(TAG, "writePublicDownload failed: " + t.getMessage());
        }
    }

    private static void writeViaMediaStore(Context ctx, String text) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, FILE_LAST);
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            // 放进 MockGPS-crash 子文件夹
            cv.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/MockGPS-crash");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = ctx.getContentResolver().insert(collection, cv);
            if (item == null) return;

            try (OutputStream os = ctx.getContentResolver().openOutputStream(item)) {
                if (os != null) {
                    os.write(text.getBytes("UTF-8"));
                    os.flush();
                }
            }
            // 取消 pending
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(item, cv, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "writeViaMediaStore: " + t.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private static void writeViaLegacyDir(String text) {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "MockGPS-crash");
        if (!dir.exists() && !dir.mkdirs()) return;
        writeFile(new File(dir, FILE_LAST), text);
    }

    private static void writeFile(File f, String text) {
        try (FileWriter fw = new FileWriter(f, false)) {
            fw.write(text);
        } catch (Exception ignored) {
        }
    }

    private static void pruneHistory(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("crash_")
                && !name.equals(FILE_LAST) && name.endsWith(".txt"));
        if (files == null || files.length <= KEEP) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - KEEP; i++) {
            if (!files[i].getName().equals(FILE_LAST)) {
                // noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        }
    }

    /**
     * 标记当前 crash_last.txt 已被用户处理（弹窗已点"分享/复制/忽略"中任一个），
     * 下次启动 MainActivity 时不再弹窗。
     * 写入 SharedPreferences 的一个时间戳，下次启动时只对 mtime > 该时间戳的新崩溃弹窗。
     */
    public static void markHandled(Context ctx) {
        try {
            long ts = System.currentTimeMillis();
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_HANDLED, ts)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 拿到上次标记为"已处理"的时间戳；0 表示从未标记过。 */
    public static long lastHandledTime(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_HANDLED, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static final String PREFS = "MockGPS-crash";
    private static final String KEY_LAST_HANDLED = "last_handled_ts";

    /** 崩溃文件路径（供 UI 展示 / 分享用）。不存在时返回预期路径。 */
    public static File lastCrashFile(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        return new File(dir, FILE_LAST);
    }
}
