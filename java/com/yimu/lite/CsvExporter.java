package com.yimu.lite;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CSV 导出：Android 10+ 写公共「下载」目录（MediaStore，无需权限）；
 * Android 9 及以下退回 App 私有目录并在提示里给出路径。
 * 文件为 UTF-8 带 BOM，Excel 直接双击不乱码。
 */
public final class CsvExporter {

    private CsvExporter() { }

    /** 导出结果：成功与否 + 展示给用户的位置描述 */
    public static final class Result {
        public final boolean ok;
        public final int rows;
        public final String message;
        Result(boolean ok, int rows, String message) {
            this.ok = ok;
            this.rows = rows;
            this.message = message;
        }
    }

    public static Result export(Activity a, Db db) {
        JSONArray rows = db.exportRows();
        String name = "jiyibi-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA)
                .format(new Date()) + ".csv";
        byte[] bytes = buildCsv(rows).getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return writeDownloads(a, name, bytes, rows.length());
            }
            return writeAppDir(a, name, bytes, rows.length());
        } catch (Exception e) {
            return new Result(false, rows.length(), "导出失败：" + e.getMessage());
        }
    }

    private static Result writeDownloads(Activity a, String name, byte[] bytes, int count)
            throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
        Uri uri = a.getContentResolver()
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return new Result(false, count, "导出失败：无法创建文件");
        try (OutputStream os = a.getContentResolver().openOutputStream(uri)) {
            if (os == null) return new Result(false, count, "导出失败：无法写入文件");
            os.write(bytes);
            os.flush();
        }
        return new Result(true, count, count + " 笔已导出到「下载」：" + name);
    }

    private static Result writeAppDir(Activity a, String name, byte[] bytes, int count)
            throws IOException {
        File dir = a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = a.getFilesDir();
        if (!dir.exists() && !dir.mkdirs()) {
            return new Result(false, count, "导出失败：无法创建目录");
        }
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(bytes);
        }
        return new Result(true, count, count + " 笔已导出：" + f.getAbsolutePath());
    }

    /** 生成 CSV 文本（首行表头 + UTF-8 BOM，字段含逗号/引号/换行时按 RFC 4180 转义） */
    static String buildCsv(JSONArray rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');                       // BOM：Excel 识别 UTF-8
        sb.append("日期,类型,金额,分类,子分类,备注\r\n");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            boolean expense = "expense".equals(r.optString("type"));
            sb.append(cell(r.optString("date"))).append(',')
              .append(expense ? "支出" : "收入").append(',')
              .append(String.format(Locale.CHINA, "%.2f", r.optDouble("amount"))).append(',')
              .append(cell(r.optString("parent_category"))).append(',')
              .append(cell(r.optString("category"))).append(',')
              .append(cell(r.optString("note"))).append("\r\n");
        }
        return sb.toString();
    }

    private static String cell(String s) {
        String v = s == null ? "" : s;
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
