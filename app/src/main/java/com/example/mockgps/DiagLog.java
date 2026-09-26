package com.example.mockgps;

import android.content.Context;

/**
 * 兼容旧调用入口：现在内部全部转发到 {@link BugTrace}。
 *
 * <p>保留这个类是为了不改动旧代码里大量 `DiagLog.d(...)` / `DiagLog.e(...)` 调用点。
 * 它现在是 BugTrace 的一层薄包装，输出格式升级为 JSONL，但调用方无感知。
 */
public final class DiagLog {

    private static final String TAG = "DIAG";

    private DiagLog() {
    }

    /** 初始化：实际交给 BugTrace。 */
    public static void init(Context ctx) {
        BugTrace.init(ctx);
        BugTrace.debug(TAG, "DiagLog.init forwarded to BugTrace");
    }

    /** 普通诊断行。 */
    public static void d(String msg) {
        BugTrace.debug(TAG, msg);
    }

    /** 带完整堆栈的错误行。 */
    public static void e(String msg, Throwable t) {
        BugTrace.error(TAG, msg, t);
    }
}
