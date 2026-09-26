package com.example.mockgps;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 一键导出 BugTrace 日志包：zip 内包含 bugtrace.jsonl、快照、设备信息。
 * 保存到应用目录 + 公共下载目录，并提供系统分享 Intent。
 */
public final class TraceExporter {

    private static final String TAG = "MockGPS-TRACE";
    private static final String PUBLIC_DIR = "MockGPS-trace";

    private TraceExporter() {
    }

    /** 导出并拉起系统分享面板。 */
    public static void share(Context ctx) {
        File zip = export(ctx);
        if (zip == null || !zip.exists()) {
            Log.w(TAG, "share failed: zip not created");
            return;
        }
        Uri uri;
        try {
            uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".fileprovider", zip);
        } catch (Throwable t) {
            Log.w(TAG, "FileProvider failed, fallback to file://");
            uri = Uri.fromFile(zip);
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, "MockGPS 详细日志 " + zip.getName());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent chooser = Intent.createChooser(intent, "分享日志包");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(chooser);
    }

    /** 导出日志包到应用目录，并复制一份到公共下载目录。返回 zip 文件路径。 */
    public static File export(Context ctx) {
        Context app = ctx.getApplicationContext();
        BugTrace.flushSync();

        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getFilesDir();
        if (dir == null) return null;

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File zipFile = new File(dir, "MockGPS-trace-" + stamp + ".zip");

        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            // 1) bugtrace.jsonl + 滚动历史
            addLogFiles(zos, dir);
            // 2) 快照
            addSnapshotFile(zos, dir);
            // 3) 设备信息
            addDeviceInfo(zos, app);

            zos.finish();
        } catch (IOException e) {
            Log.w(TAG, "export zip failed", e);
            return null;
        }

        // 复制到公共下载目录
        copyToPublicDownload(app, zipFile);
        return zipFile;
    }

    private static void addLogFiles(ZipOutputStream zos, File dir) throws IOException {
        String[] names = {"bugtrace.jsonl", "bugtrace-1.jsonl", "bugtrace-2.jsonl"};
        for (String name : names) {
            File f = new File(dir, name);
            if (f.exists()) {
                addFile(zos, f, name);
            }
        }
    }

    private static void addSnapshotFile(ZipOutputStream zos, File dir) throws IOException {
        File f = new File(dir, "bugtrace-snapshots.json");
        if (f.exists()) {
            addFile(zos, f, "state_snapshots.jsonl");
        } else {
            // 若文件尚未生成，从内存写一份
            zos.putNextEntry(new ZipEntry("state_snapshots.jsonl"));
            for (JSONObject jo : BugTrace.getSnapshots()) {
                zos.write((jo.toString() + "\n").getBytes("UTF-8"));
            }
            zos.closeEntry();
        }
    }

    private static void addDeviceInfo(ZipOutputStream zos, Context ctx) throws IOException {
        File tmp = new File(ctx.getCacheDir(), "device_info_tmp.json");
        try (FileWriter fw = new FileWriter(tmp, false)) {
            fw.write(buildDeviceInfo(ctx).toString(2));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        addFile(zos, tmp, "device_info.json");
        // noinspection ResultOfMethodCallIgnored
        tmp.delete();
    }

    private static JSONObject buildDeviceInfo(Context ctx) {
        JSONObject jo = new JSONObject();
        try {
            jo.put("manufacturer", Build.MANUFACTURER);
            jo.put("brand", Build.BRAND);
            jo.put("model", Build.MODEL);
            jo.put("device", Build.DEVICE);
            jo.put("product", Build.PRODUCT);
            jo.put("hardware", Build.HARDWARE);
            jo.put("androidRelease", Build.VERSION.RELEASE);
            jo.put("sdkInt", Build.VERSION.SDK_INT);
            jo.put("appVersion", BuildConfig.VERSION_NAME);
            jo.put("appVersionCode", BuildConfig.VERSION_CODE);
            jo.put("packageName", ctx.getPackageName());
            jo.put("sessionId", BugTrace.getSessionId());
            jo.put("locale", Locale.getDefault().toString());

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            jo.put("screenWidthPx", dm.widthPixels);
            jo.put("screenHeightPx", dm.heightPixels);
            jo.put("densityDpi", dm.densityDpi);
            jo.put("density", dm.density);

            Configuration cfg = ctx.getResources().getConfiguration();
            jo.put("uiModeNight", (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);

            JSONArray perms = new JSONArray();
            String[] checks = {
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    "android.permission.ACCESS_MOCK_LOCATION"
            };
            for (String p : checks) {
                perms.put(p + "=" + (ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED));
            }
            jo.put("permissions", perms);

            Runtime rt = Runtime.getRuntime();
            jo.put("memoryTotalMB", rt.totalMemory() / 1024 / 1024);
            jo.put("memoryFreeMB", rt.freeMemory() / 1024 / 1024);
            jo.put("memoryMaxMB", rt.maxMemory() / 1024 / 1024);
        } catch (JSONException ignored) {
        }
        return jo;
    }

    private static void addFile(ZipOutputStream zos, File f, String entryName) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
    }

    private static void copyToPublicDownload(Context ctx, File zipFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, zipFile.getName());
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_DIR);

                Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = ctx.getContentResolver().insert(collection, cv);
                if (item == null) return;
                try (OutputStream os = ctx.getContentResolver().openOutputStream(item, "w");
                     FileInputStream fis = new FileInputStream(zipFile)) {
                    if (os != null) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) != -1) os.write(buf, 0, n);
                        os.flush();
                    }
                }
            } else {
                @SuppressWarnings("deprecation")
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        PUBLIC_DIR);
                if (!dir.exists() && !dir.mkdirs()) return;
                try (FileInputStream fis = new FileInputStream(zipFile);
                     FileOutputStream fos = new FileOutputStream(new File(dir, zipFile.getName()))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "copyToPublicDownload failed: " + t.getMessage());
        }
    }
}
