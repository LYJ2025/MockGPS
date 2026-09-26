package com.example.mockgps;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极详细追踪日志：用于复现/定位切页、渲染、闪退类 Bug。
 *
 * <p>特性：
 * <ul>
 *   <li>JSONL 结构化输出，每行一个 JSON 对象；</li>
 *   <li>7 级日志：TRACE/DEBUG/INFO/WARN/ERROR/FATAL/EVENT；</li>
 *   <li>自动注入 session_id、trace_id、thread、page、ISO 时间戳；</li>
 *   <li>异步单线程写入，不阻塞 UI；崩溃时 {@link #flushSync()} 强制同步落盘；</li>
 *   <li>内存环形缓冲 800 行，{@link CrashLogger} 崩溃报告可整份带走；</li>
 *   <li>自动按 512KB 滚动应用目录日志文件，保留最近 2 份；</li>
 *   <li>三处输出：应用专属目录、公共下载目录（API 29+ MediaStore）、logcat。</li>
 * </ul>
 */
public final class BugTrace {

    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR, FATAL, EVENT
    }

    private static final String TAG = "MockGPS-TRACE";
    private static final String FILE_NAME = "bugtrace.jsonl";
    private static final String FILE_NAME_OLD = "bugtrace-1.jsonl";
    private static final String SNAPSHOT_FILE = "bugtrace-snapshots.json";
    private static final String PUBLIC_DIR = "MockGPS-trace";
    private static final int RING_SIZE = 800;
    private static final int MAX_FILE_SIZE = 512 * 1024;
    private static final int MAX_SNAPSHOTS = 50;
    private static final int KEEP_LOG_FILES = 2;

    private static Context appCtx;
    private static String sessionId;
    private static final ThreadLocal<String> traceId = new ThreadLocal<>();
    private static volatile String currentPage = "";

    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static final List<JSONObject> snapshots = Collections.synchronizedList(new ArrayList<JSONObject>());

    private static final BlockingQueue<LogItem> queue = new LinkedBlockingQueue<>();
    private static ThreadPoolExecutor writer;
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final Object flushLock = new Object();
    private static volatile boolean flushRequested = false;

    private BugTrace() {
    }

    private static final class LogItem {
        final Level level;
        final String tag;
        final String msg;
        final Throwable t;
        final long time;

        LogItem(Level level, String tag, String msg, Throwable t, long time) {
            this.level = level;
            this.tag = tag;
            this.msg = msg;
            this.t = t;
            this.time = time;
        }
    }

    /** 在 Application / Activity 最早时机调用一次。 */
    public static void init(Context ctx) {
        if (initialized.getAndSet(true)) return;
        appCtx = ctx.getApplicationContext();
        sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        writer.execute(BugTrace::writerLoop);
        event("BugTrace", "init session=" + sessionId
                + " version=" + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")");
    }

    /** 设置当前页面名，之后所有日志自动带上 page 字段。 */
    public static void setPage(String page) {
        currentPage = page == null ? "" : page;
    }

    public static String getPage() {
        return currentPage;
    }

    /** 手动设置 trace_id；不设置时会自动生成一个随机短串。 */
    public static void setTraceId(String id) {
        traceId.set(id);
    }

    /** 生成并设置一个新的 trace_id，返回该 id。 */
    public static String beginTrace() {
        String id = "t" + System.nanoTime();
        traceId.set(id);
        return id;
    }

    /** 清除当前线程 trace_id。 */
    public static void endTrace() {
        traceId.remove();
    }

    /** 标记复现开始；日志中会写入一个特殊 EVENT。 */
    public static void markReplayStart() {
        event("REPLAY", "=== REPLAY START ===");
    }

    /** 标记复现结束。 */
    public static void markReplayEnd() {
        event("REPLAY", "=== REPLAY END ===");
    }

    public static void trace(String tag, String msg) {
        log(Level.TRACE, tag, msg, null);
    }

    public static void debug(String tag, String msg) {
        log(Level.DEBUG, tag, msg, null);
    }

    public static void info(String tag, String msg) {
        log(Level.INFO, tag, msg, null);
    }

    public static void warn(String tag, String msg) {
        log(Level.WARN, tag, msg, null);
    }

    public static void warn(String tag, String msg, Throwable t) {
        log(Level.WARN, tag, msg, t);
    }

    public static void error(String tag, String msg) {
        log(Level.ERROR, tag, msg, null);
    }

    public static void error(String tag, String msg, Throwable t) {
        log(Level.ERROR, tag, msg, t);
    }

    public static void fatal(String tag, String msg, Throwable t) {
        log(Level.FATAL, tag, msg, t);
    }

    public static void event(String tag, String msg) {
        log(Level.EVENT, tag, msg, null);
    }

    /** 最通用入口：带结构化 extra。extra 直接作为 JSON 字段附加。 */
    public static void log(Level level, String tag, String msg, Throwable t) {
        if (!initialized.get()) {
            // 未初始化时只打 logcat，避免初始化前日志丢失。
            Log.w(TAG, "[not init] " + level + " " + tag + " " + msg);
            return;
        }
        enqueue(new LogItem(level, tag, msg, t, System.currentTimeMillis()));
    }

    /**
     * 记录状态快照。name 为快照名（如 "engineState" / "trackRender"），state 为任意 JSON。
     * 快照会同时写入内存列表和专用快照文件，供导出时打包。
     */
    public static void snapshot(String name, JSONObject state) {
        if (!initialized.get()) return;
        JSONObject snap = new JSONObject();
        try {
            snap.put("ts", isoTime(System.currentTimeMillis()));
            snap.put("name", name);
            snap.put("page", currentPage);
            if (state != null) snap.put("state", state);
        } catch (JSONException ignored) {
        }
        synchronized (snapshots) {
            snapshots.add(snap);
            while (snapshots.size() > MAX_SNAPSHOTS) {
                snapshots.remove(0);
            }
        }
        event("SNAPSHOT", name);
        writeSnapshotFile();
    }

    /** 崩溃前调用：强制把队列中所有日志同步落盘。 */
    public static void flushSync() {
        if (!initialized.get()) return;
        flushRequested = true;
        queue.offer(new LogItem(Level.EVENT, "BugTrace", "flushSync", null, System.currentTimeMillis()));
        synchronized (flushLock) {
            while (flushRequested) {
                try {
                    flushLock.wait(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /** 返回最近 N 行 JSONL 字符串（用于崩溃报告附加）。 */
    public static String recentJson(int lines) {
        synchronized (ring) {
            StringBuilder sb = new StringBuilder();
            int skip = Math.max(0, ring.size() - lines);
            int i = 0;
            for (String line : ring) {
                if (i++ >= skip) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }
    }

    /** 应用目录下的主日志文件路径。 */
    public static File getLogFile() {
        return new File(logDir(), FILE_NAME);
    }

    /** 应用目录下的快照文件路径。 */
    public static File getSnapshotFile() {
        return new File(logDir(), SNAPSHOT_FILE);
    }

    /** 当前内存中的快照列表副本。 */
    public static List<JSONObject> getSnapshots() {
        synchronized (snapshots) {
            return new ArrayList<>(snapshots);
        }
    }

    public static String getSessionId() {
        return sessionId;
    }

    private static File logDir() {
        File dir = appCtx.getExternalFilesDir(null);
        if (dir == null) dir = appCtx.getFilesDir();
        if (dir == null) throw new IllegalStateException("no files dir");
        return dir;
    }

    private static void enqueue(LogItem item) {
        try {
            queue.offer(item);
        } catch (Throwable t) {
            Log.w(TAG, "enqueue failed", t);
        }
    }

    private static void writerLoop() {
        while (!writer.isShutdown()) {
            try {
                LogItem item = queue.poll(1, TimeUnit.SECONDS);
                if (item != null) {
                    process(item);
                }
                if (flushRequested) {
                    drainRemaining();
                    synchronized (flushLock) {
                        flushRequested = false;
                        flushLock.notifyAll();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                Log.w(TAG, "writerLoop error", t);
            }
        }
    }

    private static void drainRemaining() {
        LogItem item;
        while ((item = queue.poll()) != null) {
            process(item);
        }
    }

    private static void process(LogItem item) {
        String line = format(item);
        addToRing(line);
        Log.println(levelToAndroid(item.level), TAG, item.level + "/" + item.tag + " " + item.msg);
        appendApp(line);
        flushPublicIfFatal(item.level);
    }

    private static String format(LogItem item) {
        JSONObject jo = new JSONObject();
        try {
            jo.put("ts", isoTime(item.time));
            jo.put("level", item.level.name());
            jo.put("tag", item.tag);
            jo.put("page", currentPage);
            jo.put("thread", Thread.currentThread().getName());
            jo.put("session", sessionId);
            String tid = traceId.get();
            jo.put("trace", tid == null ? "none" : tid);
            jo.put("msg", item.msg);
            if (item.t != null) {
                jo.put("exception", Log.getStackTraceString(item.t));
            }
        } catch (JSONException ignored) {
        }
        return jo.toString();
    }

    private static String isoTime(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(millis));
    }

    private static int levelToAndroid(Level level) {
        switch (level) {
            case TRACE:
            case DEBUG:
                return Log.DEBUG;
            case INFO:
            case EVENT:
                return Log.INFO;
            case WARN:
                return Log.WARN;
            case ERROR:
            case FATAL:
                return Log.ERROR;
            default:
                return Log.DEBUG;
        }
    }

    private static void addToRing(String line) {
        synchronized (ring) {
            ring.addLast(line);
            while (ring.size() > RING_SIZE) {
                ring.removeFirst();
            }
        }
    }

    private static void appendApp(String line) {
        try {
            File dir = logDir();
            if (!dir.exists() && !dir.mkdirs()) return;
            File f = new File(dir, FILE_NAME);
            rollIfNeeded(f);
            try (FileWriter fw = new FileWriter(f, true)) {
                fw.write(line);
                fw.write('\n');
            }
        } catch (Throwable t) {
            Log.w(TAG, "appendApp failed: " + t.getMessage());
        }
    }

    private static void rollIfNeeded(File current) {
        if (!current.exists() || current.length() < MAX_FILE_SIZE) return;
        try {
            File dir = current.getParentFile();
            // 保留最近 KEEP_LOG_FILES 份：先把旧的按数字后移，再重命名 current。
            for (int i = KEEP_LOG_FILES - 1; i >= 1; i--) {
                File src = new File(dir, (i == 1 ? FILE_NAME_OLD : "bugtrace-" + i + ".jsonl"));
                File dst = new File(dir, "bugtrace-" + (i + 1) + ".jsonl");
                if (src.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    dst.delete();
                    // noinspection ResultOfMethodCallIgnored
                    src.renameTo(dst);
                }
            }
            File first = new File(dir, FILE_NAME_OLD);
            // noinspection ResultOfMethodCallIgnored
            first.delete();
            // noinspection ResultOfMethodCallIgnored
            current.renameTo(first);
        } catch (Throwable t) {
            Log.w(TAG, "roll failed: " + t.getMessage());
        }
    }

    private static void flushPublicIfFatal(Level level) {
        if (level == Level.FATAL || level == Level.ERROR) {
            copyToPublic();
        }
    }

    /** 把当前主日志文件复制到公共下载目录（供文件管理器直接可见）。 */
    public static void copyToPublic() {
        try {
            File src = getLogFile();
            if (!src.exists()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                copyViaMediaStore(src);
            } else {
                copyViaLegacy(src);
            }
        } catch (Throwable t) {
            Log.w(TAG, "copyToPublic failed: " + t.getMessage());
        }
    }

    private static void copyViaMediaStore(File src) {
        Context ctx = appCtx;
        if (ctx == null) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            cv.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_DIR);

            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            // 先尝试删除同名文件
            String sel = MediaStore.Downloads.DISPLAY_NAME + "=?";
            ctx.getContentResolver().delete(collection, sel, new String[]{FILE_NAME});

            Uri item = ctx.getContentResolver().insert(collection, cv);
            if (item == null) return;
            try (OutputStream os = ctx.getContentResolver().openOutputStream(item, "w");
                 FileInputStream fis = new FileInputStream(src)) {
                if (os != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                    os.flush();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "copyViaMediaStore: " + t.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private static void copyViaLegacy(File src) {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                PUBLIC_DIR);
        if (!dir.exists() && !dir.mkdirs()) return;
        File dst = new File(dir, FILE_NAME);
        try (FileChannel in = new FileInputStream(src).getChannel();
             FileChannel out = new FileOutputStream(dst).getChannel()) {
            in.transferTo(0, in.size(), out);
        } catch (Throwable t) {
            Log.w(TAG, "copyViaLegacy: " + t.getMessage());
        }
    }

    private static void writeSnapshotFile() {
        try {
            File dir = logDir();
            if (!dir.exists() && !dir.mkdirs()) return;
            File f = new File(dir, SNAPSHOT_FILE);
            List<JSONObject> copy;
            synchronized (snapshots) {
                copy = new ArrayList<>(snapshots);
            }
            try (FileWriter fw = new FileWriter(f, false)) {
                for (JSONObject jo : copy) {
                    fw.write(jo.toString());
                    fw.write('\n');
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "writeSnapshotFile failed: " + t.getMessage());
        }
    }
}
