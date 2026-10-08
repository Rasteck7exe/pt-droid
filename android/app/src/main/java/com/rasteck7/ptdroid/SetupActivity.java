package com.rasteck7.ptdroid;

import android.app.Activity;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONObject;

import com.rasteck7.ptdroid.pkg.PkgExtractor;

/**
 * First screen of PT Droid (docs/android.md). If the game files are already in place it starts the game at once;
 * otherwise it lets the person pick P.T.'s package (a fake PKG, which it extracts as the PC installer does, with
 * pkg/PkgExtractor), the folder with the files, or the three files, through Android's own file picker, and puts the
 * files into the app's folder, which needs no special permission. Then it starts PTActivity. A store PKG cannot be
 * opened; the screen then asks for the three files.
 */
public class SetupActivity extends Activity {

    static final String[] GAME_FILES = { "chunk1.psarc", "texture.qar", "pathid_list_ps4.bin" };
    static final String SHARED_DIR = "/storage/emulated/0/PT/CUSA01127";

    private static final int PICK_FOLDER = 1;
    private static final int PICK_FILES = 2;
    private static final int PICK_PKG = 3;
    private static final int PICK_DRIVER = 4;

    private File targetDir;
    private TextView status;
    private ProgressBar progress;
    private LinearLayout buttons;
    private volatile boolean copying = false;
    private ApplicationExitInfo lastExit;
    // opened from the "GPU driver" shortcut: stay on this screen instead of starting the game
    private boolean driverScreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        targetDir = new File(getExternalFilesDir(null), "CUSA01127");
        // the last run crashed, ran out of memory or hung: say so and offer the log before starting again
        lastExit = CrashReport.lastBadExit(this);
        driverScreen = getIntent().hasExtra("gpu_driver");
        if (haveGame() && lastExit == null && !driverScreen) {
            startGame();
            return;
        }
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // back from the all files access screen, or the files were copied over USB meanwhile
        if (status != null && !copying && lastExit == null && !driverScreen && haveGame()) {
            startGame();
        }
    }

    private static boolean complete(File dir) {
        for (String name : GAME_FILES) {
            File file = new File(dir, name);
            if (!file.isFile() || file.length() == 0) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    private boolean haveGame() {
        return complete(targetDir) || (hasAllFilesAccess() && complete(new File(SHARED_DIR)));
    }

    private void startGame() {
        CrashReport.markSeen(this, lastExit);
        startActivity(new Intent(this, PTActivity.class));
        finish();
    }

    private void shareLog() {
        try {
            File report = CrashReport.write(this, lastExit);
            Uri uri = LogProvider.uriFor(report);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "PT Droid log");
            send.setClipData(ClipData.newRawUri("pt-droid-log", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Compartir log"));
        } catch (Exception e) {
            status.setText("No pude preparar el log: " + e.getMessage());
        }
    }

    private void deleteGameFiles() {
        for (String name : GAME_FILES) {
            new File(targetDir, name).delete();
        }
        new File(targetDir, "source.txt").delete();
    }

    // ---- the screen ------------------------------------------------------------------------------------------------

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private TextView text(String value, float size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(bold ? Color.WHITE : Color.rgb(200, 200, 200));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private Button button(String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(action);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(12, 12, 12));
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(20), dp(24), dp(24));
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(column);

        column.addView(text("PT Droid", 26, true));
        final boolean installed = haveGame();
        if (lastExit != null) {
            TextView crash = text("La última vez el juego " + CrashReport.reasonText(lastExit) + ".", 17, true);
            crash.setTextColor(Color.rgb(255, 140, 120));
            column.addView(crash);
            String tail = CrashReport.logTail(this, 16);
            if (!tail.isEmpty()) {
                TextView log = text(tail, 11, false);
                log.setTypeface(Typeface.MONOSPACE);
                log.setTextIsSelectable(true);
                log.setBackgroundColor(Color.rgb(28, 28, 28));
                log.setPadding(dp(10), dp(8), dp(10), dp(8));
                column.addView(log);
            }
            column.addView(button("Compartir log", v -> shareLog()));
            if (installed) {
                column.addView(button("Jugar otra vez", v -> startGame()));
                column.addView(button("Borrar los archivos del juego y volver a importarlos", v -> {
                    deleteGameFiles();
                    CrashReport.markSeen(this, lastExit);
                    lastExit = null;
                    buildUi();
                }));
            }
        }
        if (installed && lastExit == null) {
            column.addView(button("Jugar", v -> startGame()));
        }
        addDriverSection(column);
        if (installed) {
            status = text("", 14, false);
            column.addView(status);
            setContentView(scroll);
            return;
        }
        column.addView(text("Para jugar necesitas tu copia de P.T. (CUSA01127).\n\n"
                + "Elige el PKG del juego (fake PKG) y la app saca de él chunk1.psarc, texture.qar y "
                + "pathid_list_ps4.bin, como el instalador de PC. Si ya los tienes extraídos, elige la carpeta o los "
                + "tres archivos. Es una sola vez y no necesita permisos especiales; después puedes borrar los originales.", 15, false));

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        column.addView(buttons);
        buttons.addView(button("Elegir el PKG del juego", v -> pickPkg()));
        buttons.addView(button("Elegir la carpeta (con el PKG o los archivos)", v -> pickFolder()));
        buttons.addView(button("Elegir los 3 archivos", v -> pickFiles()));
        buttons.addView(button("Usar " + SHARED_DIR + " (acceso a todos los archivos)", v -> requestAllFilesAccess()));
        if (lastExit == null && CrashReport.logFile(this).isFile()) {
            buttons.addView(button("Compartir log", v -> shareLog()));
        }
        buttons.addView(button("Reintentar", v -> {
            if (haveGame()) {
                startGame();
            } else {
                status.setText("Todavía no encuentro los tres archivos.");
            }
        }));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(16);
        progress.setLayoutParams(barParams);
        column.addView(progress);

        status = text("", 14, false);
        column.addView(status);
        column.addView(text("Carpeta de la app: " + targetDir.getAbsolutePath(), 12, false));

        setContentView(scroll);
    }

    // ---- custom GPU driver ------------------------------------------------------------------------------------------

    // Adrenotools only loads drivers from the app's private storage; the game reads them from here when it starts
    // (src/engine/platform/android_support.cpp): the package's .so files and meta.json, and main.txt with the main library.
    private File driverDir() {
        return new File(getFilesDir(), "gpu_driver");
    }

    private String driverName() {
        File dir = driverDir();
        File main = new File(dir, "main.txt");
        if (!main.isFile()) {
            return null;
        }
        try {
            String library = new String(java.nio.file.Files.readAllBytes(main.toPath())).trim();
            return new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(dir, "meta.json").toPath())))
                    .optString("name", library);
        } catch (Exception e) {
            try {
                return new String(java.nio.file.Files.readAllBytes(main.toPath())).trim();
            } catch (IOException io) {
                return null;
            }
        }
    }

    private void addDriverSection(LinearLayout column) {
        String name = driverName();
        column.addView(text("Driver de GPU", 17, true));
        column.addView(text(name == null
                ? "Usando el del teléfono. Puedes instalar uno propio, por ejemplo un Turnip (Mesa) para Adreno, desde un .zip "
                        + "con su meta.json y su .so. Solo funciona en GPU Adreno. Si el juego no arranca con él, la siguiente "
                        + "vez vuelve solo al del teléfono."
                : "Instalado: " + name, 14, false));
        column.addView(button(name == null ? "Instalar un driver (.zip)" : "Cambiar el driver (.zip)", v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_DRIVER);
        }));
        if (name != null) {
            column.addView(button("Volver al driver del teléfono", v -> {
                deleteRecursively(driverDir());
                buildUi();
            }));
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    /** Unpacks the picked driver package into the private folder, replacing the one installed, on a worker thread. */
    private void installDriver(Uri uri) {
        copying = true;
        new Thread(() -> {
            File staging = new File(getFilesDir(), "gpu_driver.new");
            String error = null;
            try {
                deleteRecursively(staging);
                staging.mkdirs();
                String[] libraries;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    libraries = DriverZip.extract(in, staging);
                }
                if (libraries.length == 0) {
                    error = "Eso no es un driver: el .zip no trae ningún .so.";
                } else {
                    String libraryName = "";
                    try {
                        libraryName = new JSONObject(new String(java.nio.file.Files.readAllBytes(
                                new File(staging, "meta.json").toPath()))).optString("libraryName", "");
                    } catch (Exception ignored) {
                        // no meta.json, or not readable: the library is picked by its name
                    }
                    try (FileOutputStream out = new FileOutputStream(new File(staging, "main.txt"))) {
                        out.write((DriverZip.mainLibrary(libraries, libraryName) + "\n").getBytes());
                    }
                    deleteRecursively(driverDir());
                    if (!staging.renameTo(driverDir())) {
                        error = "No pude instalar el driver en el almacenamiento de la app.";
                    }
                }
            } catch (Exception e) {
                error = "No pude leer ese archivo: " + e.getMessage();
            }
            deleteRecursively(staging);
            final String message = error;
            runOnUiThread(() -> {
                copying = false;
                buildUi();
                if (message != null && status != null) {
                    status.setText(message);
                }
            });
        }).start();
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, PICK_FOLDER);
    }

    private void pickPkg() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_PKG);
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_FILES);
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ---- finding the files in what was picked ----------------------------------------------------------------------

    private static class Source {
        Uri uri;
        long size;
    }

    private static boolean isGameFile(String name) {
        for (String wanted : GAME_FILES) {
            if (wanted.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Looks through a picked folder tree (and up to three levels of subfolders) for the game files. */
    private static boolean isPkgName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".pkg");
    }

    private void searchTree(Uri tree, String documentId, int depth, Map<String, Source> found, List<Source> packages) {
        ContentResolver resolver = getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        String[] columns = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE };
        List<String> folders = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    folders.add(id);
                } else if (isPkgName(name)) {
                    Source source = new Source();
                    source.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    source.size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                    packages.add(source);
                } else if (name != null && isGameFile(name)) {
                    String key = name.toLowerCase(Locale.ROOT);
                    if (!found.containsKey(key)) {
                        Source source = new Source();
                        source.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        source.size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                        found.put(key, source);
                    }
                }
            }
        }
        if (found.size() < GAME_FILES.length && depth < 3) {
            for (String folder : folders) {
                searchTree(tree, folder, depth + 1, found, packages);
                if (found.size() == GAME_FILES.length) {
                    break;
                }
            }
        }
    }

    private Source describe(Uri uri, String[] name) {
        Source source = new Source();
        source.uri = uri;
        source.size = -1;
        try (Cursor cursor = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name[0] = cursor.getString(0);
                source.size = cursor.isNull(1) ? -1 : cursor.getLong(1);
            }
        }
        return source;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == PICK_PKG && data.getData() != null) {
            importPkg(data.getData());
            return;
        }
        if (requestCode == PICK_DRIVER && data.getData() != null) {
            installDriver(data.getData());
            return;
        }
        Map<String, Source> found = new HashMap<>();
        List<Source> packages = new ArrayList<>();
        try {
            if (requestCode == PICK_FOLDER && data.getData() != null) {
                Uri tree = data.getData();
                searchTree(tree, DocumentsContract.getTreeDocumentId(tree), 0, found, packages);
            } else if (requestCode == PICK_FILES) {
                List<Uri> uris = new ArrayList<>();
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); ++i) {
                        uris.add(clip.getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                for (Uri uri : uris) {
                    String[] name = { null };
                    Source source = describe(uri, name);
                    if (name[0] != null && isGameFile(name[0])) {
                        found.put(name[0].toLowerCase(Locale.ROOT), source);
                    } else if (isPkgName(name[0])) {
                        packages.add(source);
                    }
                }
            }
        } catch (Exception e) {
            status.setText("No pude leer lo que elegiste: " + e.getMessage());
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String name : GAME_FILES) {
            if (!found.containsKey(name.toLowerCase(Locale.ROOT))) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty() && !packages.isEmpty()) {
            // no extracted files, but a package: take them out of it (the largest .pkg is the game, not an update)
            Source largest = packages.get(0);
            for (Source p : packages) {
                if (p.size > largest.size) largest = p;
            }
            importPkg(largest.uri);
            return;
        }
        if (!missing.isEmpty()) {
            status.setText("No encontré: " + String.join(", ", missing) + "\nElige el PKG del juego, la carpeta que los tiene o los tres archivos.");
            return;
        }
        copy(found);
    }

    // ---- extracting from a PKG --------------------------------------------------------------------------------------

    /** Random access to a picked document through its file descriptor (a file on the phone; a cloud stream is not). */
    private static final class ChannelSource implements PkgExtractor.Source {
        private final FileChannel channel;
        private final long size;

        ChannelSource(FileChannel channel, long size) {
            this.channel = channel;
            this.size = size;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public void read(long pos, byte[] buffer, int offset, int len) throws IOException {
            ByteBuffer target = ByteBuffer.wrap(buffer, offset, len);
            while (target.hasRemaining()) {
                int n = channel.read(target, pos + (target.position() - offset));
                if (n < 0) {
                    throw new IOException("the PKG ended early (incomplete download?)");
                }
            }
        }
    }

    private void importPkg(Uri uri) {
        copying = true;
        setButtonsEnabled(false);
        progress.setProgress(0);
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        status.setText("Abriendo el PKG…");
        new Thread(() -> {
            String failure = null;
            boolean retail = false;
            try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r");
                 FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
                 FileChannel channel = stream.getChannel()) {
                long size = descriptor.getStatSize() > 0 ? descriptor.getStatSize() : channel.size();
                ChannelSource source = new ChannelSource(channel, size);
                if (!PkgExtractor.isPkg(source)) {
                    throw new IOException("no es un PKG de PS4");
                }
                PkgExtractor.Contents contents = PkgExtractor.open(source);
                long needed = contents.totalSize();
                targetDir.mkdirs();
                long free = new StatFs(targetDir.getAbsolutePath()).getAvailableBytes();
                if (free < needed + 64L * 1024 * 1024) {
                    failure = String.format(Locale.ROOT, "No hay espacio: se necesitan %.2f GB y hay %.2f GB libres.", needed / 1e9, free / 1e9);
                } else {
                    runOnUiThread(() -> progress.setIndeterminate(false));
                    final long[] lastShown = { 0 };
                    PkgExtractor.extract(contents, targetDir, (name, done, total) -> {
                        if (done - lastShown[0] >= (16L << 20) || done == total) {
                            lastShown[0] = done;
                            runOnUiThread(() -> showProgress("Extrayendo", name, done, total));
                        }
                    });
                }
            } catch (PkgExtractor.RetailException e) {
                retail = true;
            } catch (PkgExtractor.NotPtException e) {
                failure = "Ese PKG no trae los archivos de P.T. Elige el PKG del juego (CUSA01127).";
            } catch (Exception e) {
                failure = "No pude leer el PKG (" + e.getMessage() + "). Si está en la nube, cópialo primero al teléfono.";
            }
            final String message = failure;
            final boolean store = retail;
            runOnUiThread(() -> finishPkg(message, store));
        }, "pt-pkg").start();
    }

    private void finishPkg(String failure, boolean retail) {
        copying = false;
        progress.setIndeterminate(false);
        progress.setVisibility(View.GONE);
        setButtonsEnabled(true);
        if (retail) {
            status.setText("Este PKG es el original de la tienda: viene encriptado para la consola que tiene la licencia, "
                    + "así que no se puede abrir (el instalador de PC tampoco puede).\n\n"
                    + "Necesitas los 3 archivos ya extraídos (de un dump de una consola con P.T., o de un fake PKG): "
                    + "toca \"Elegir los 3 archivos\" o \"Elegir la carpeta\".");
            return;
        }
        if (failure != null) {
            status.setText(failure);
            return;
        }
        if (complete(targetDir)) {
            status.setText("Listo, abriendo el juego…");
            startGame();
        } else {
            status.setText("La extracción terminó pero falta algún archivo; vuelve a intentar.");
        }
    }

    // ---- copying ----------------------------------------------------------------------------------------------------

    private void copy(Map<String, Source> sources) {
        long total = 0;
        for (Source source : sources.values()) {
            total += Math.max(0, source.size);
        }
        targetDir.mkdirs();
        long free = new StatFs(targetDir.getAbsolutePath()).getAvailableBytes();
        if (total > 0 && free < total + 64L * 1024 * 1024) {
            status.setText(String.format(Locale.ROOT, "No hay espacio: se necesitan %.2f GB y hay %.2f GB libres.",
                    total / 1e9, free / 1e9));
            return;
        }
        copying = true;
        setButtonsEnabled(false);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        final long totalBytes = total;
        new Thread(() -> {
            String error = null;
            long done = 0;
            byte[] buffer = new byte[4 << 20];
            for (String name : GAME_FILES) {
                Source source = sources.get(name.toLowerCase(Locale.ROOT));
                File part = new File(targetDir, name + ".part");
                File target = new File(targetDir, name);
                try (InputStream in = getContentResolver().openInputStream(source.uri);
                     OutputStream out = new FileOutputStream(part)) {
                    if (in == null) {
                        throw new java.io.IOException("no se pudo abrir " + name);
                    }
                    long lastShown = 0;
                    for (int n; (n = in.read(buffer)) > 0;) {
                        out.write(buffer, 0, n);
                        done += n;
                        if (done - lastShown >= (16L << 20)) {
                            lastShown = done;
                            final long shown = done;
                            runOnUiThread(() -> showProgress(name, shown, totalBytes));
                        }
                    }
                } catch (Exception e) {
                    error = name + ": " + e.getMessage();
                    part.delete();
                    break;
                }
                target.delete();
                if (!part.renameTo(target)) {
                    error = "no se pudo guardar " + name;
                    break;
                }
            }
            final String failure = error;
            runOnUiThread(() -> finishCopy(failure));
        }, "pt-import").start();
    }

    private void showProgress(String name, long done, long total) {
        showProgress("Copiando", name, done, total);
    }

    private void showProgress(String verb, String name, long done, long total) {
        if (total > 0) {
            progress.setProgress((int) Math.min(1000, done * 1000 / total));
            status.setText(String.format(Locale.ROOT, "%s %s… %.0f%% (%.2f de %.2f GB)", verb, name, done * 100.0 / total, done / 1e9, total / 1e9));
        } else {
            status.setText(String.format(Locale.ROOT, "%s %s… %.2f GB", verb, name, done / 1e9));
        }
    }

    private void finishCopy(String error) {
        copying = false;
        progress.setVisibility(View.GONE);
        setButtonsEnabled(true);
        if (error != null) {
            status.setText("La copia falló (" + error + "). Revisa el espacio libre y vuelve a intentar.");
            return;
        }
        if (complete(targetDir)) {
            status.setText("Listo, abriendo el juego…");
            startGame();
        } else {
            status.setText("La copia terminó pero falta algún archivo; vuelve a intentar.");
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        for (int i = 0; i < buttons.getChildCount(); ++i) {
            buttons.getChildAt(i).setEnabled(enabled);
        }
    }

    @Override
    public void onBackPressed() {
        if (!copying) {
            super.onBackPressed();
        }
    }
}
