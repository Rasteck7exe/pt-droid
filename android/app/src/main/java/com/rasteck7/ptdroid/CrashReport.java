package com.rasteck7.ptdroid;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What ended the game's last run (Android's own record of how the process exited) and the report the setup screen
 * shares: device, exit reason, the native crash's tombstone text and pt.log, whose crash lines the game writes itself
 * (src/engine/platform/android_support.cpp, InstallCrashHandler).
 */
final class CrashReport {
    private CrashReport() {}

    private static final String PREFS = "pt-droid";
    private static final String SEEN = "seen_exit";

    static File logFile(Context context) {
        return new File(context.getExternalFilesDir(null), "pt.log");
    }

    /** The last exit when it was a crash, out of memory or a hang the person has not seen yet; null otherwise. */
    static ApplicationExitInfo lastBadExit(Context context) {
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 1);
            if (exits.isEmpty()) {
                return null;
            }
            ApplicationExitInfo last = exits.get(0);
            long seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SEEN, 0);
            if (last.getTimestamp() <= seen) {
                return null;
            }
            switch (last.getReason()) {
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
            case ApplicationExitInfo.REASON_LOW_MEMORY:
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return last;
            default:
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    static void markSeen(Context context, ApplicationExitInfo exit) {
        if (exit == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putLong(SEEN, exit.getTimestamp()).apply();
    }

    static String reasonText(ApplicationExitInfo exit) {
        switch (exit.getReason()) {
        case ApplicationExitInfo.REASON_CRASH_NATIVE: return "se cerró por un error del juego (crash nativo)";
        case ApplicationExitInfo.REASON_CRASH: return "se cerró por un error de Java";
        case ApplicationExitInfo.REASON_ANR: return "dejó de responder";
        case ApplicationExitInfo.REASON_LOW_MEMORY: return "Android lo cerró por falta de memoria";
        case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "no pudo iniciar";
        default: return "se cerró (motivo " + exit.getReason() + ")";
        }
    }

    /** The last lines of pt.log, for the screen. */
    static String logTail(Context context, int maxLines) {
        String text = readTail(logFile(context), 64 * 1024);
        String[] lines = text.split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, lines.length - maxLines); i < lines.length; ++i) {
            out.append(lines[i]).append('\n');
        }
        return out.toString().trim();
    }

    private static String readTail(File file, int maxBytes) {
        if (!file.isFile()) {
            return "";
        }
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long length = in.length();
            long start = Math.max(0, length - maxBytes);
            byte[] bytes = new byte[(int) (length - start)];
            in.seek(start);
            in.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return start > 0 ? "[… principio del log recortado …]\n" + text.substring(Math.max(0, text.indexOf('\n') + 1)) : text;
        } catch (IOException e) {
            return "";
        }
    }

    /** The readable text of a tombstone (a protobuf on Android 12 and newer, text before): runs of printable bytes. */
    private static String tombstoneText(ApplicationExitInfo exit) {
        try (InputStream in = exit.getTraceInputStream()) {
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0 && raw.size() < 4 * 1024 * 1024;) {
                raw.write(buffer, 0, n);
            }
            byte[] bytes = raw.toByteArray();
            StringBuilder out = new StringBuilder();
            int start = -1;
            for (int i = 0; i <= bytes.length && out.length() < 96 * 1024; ++i) {
                boolean printable = i < bytes.length && ((bytes[i] >= 0x20 && bytes[i] < 0x7F) || bytes[i] == '\n' || bytes[i] == '\t');
                if (printable && start < 0) {
                    start = i;
                } else if (!printable && start >= 0) {
                    if (i - start >= 6) {
                        out.append(new String(bytes, start, i - start, StandardCharsets.US_ASCII)).append('\n');
                    }
                    start = -1;
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "(no se pudo leer: " + e + ")";
        }
    }

    /** Writes the report into cache/share/ and returns it, for LogProvider to hand out. */
    static File write(Context context, ApplicationExitInfo exit) throws IOException {
        File report = new File(LogProvider.shareDir(context), "pt-droid-log.txt");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(report), StandardCharsets.UTF_8)) {
            String version = "?";
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                version = info.versionName + " (" + info.getLongVersionCode() + ")";
            } catch (Exception ignored) {
            }
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
            out.write("PT Droid " + version + "\n");
            out.write("device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
            if (Build.VERSION.SDK_INT >= 31) {
                out.write(", SoC " + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
            }
            out.write("\nandroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), build " + Build.DISPLAY + "\n");
            out.write("report: " + format.format(new Date()) + "\n\n");
            if (exit != null) {
                out.write("== last exit\n");
                out.write("time: " + format.format(new Date(exit.getTimestamp())) + "\n");
                out.write("reason: " + exit.getReason() + " (" + reasonText(exit) + "), status " + exit.getStatus() + "\n");
                out.write("description: " + exit.getDescription() + "\n");
                out.write("memory: pss " + exit.getPss() / 1024 + " MB, rss " + exit.getRss() / 1024 + " MB\n\n");
                if (exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE || exit.getReason() == ApplicationExitInfo.REASON_ANR) {
                    out.write("== tombstone (readable parts)\n");
                    out.write(tombstoneText(exit));
                    out.write("\n");
                }
            }
            out.write("== pt.log\n");
            out.write(readTail(logFile(context), 512 * 1024));
            out.write("\n");
        }
        return report;
    }
}
