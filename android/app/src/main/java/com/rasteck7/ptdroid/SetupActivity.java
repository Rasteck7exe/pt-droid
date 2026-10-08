package com.rasteck7.ptdroid;

import android.app.Activity;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * First screen of PT Droid (docs/android.md). If the game files are already in place it starts the game at once;
 * otherwise it lets the person pick the folder with the files (or the three files) through Android's own file picker
 * and copies them into the app's folder, which needs no special permission. Then it starts PTActivity.
 */
public class SetupActivity extends Activity {

    static final String[] GAME_FILES = { "chunk1.psarc", "texture.qar", "pathid_list_ps4.bin" };
    static final String SHARED_DIR = "/storage/emulated/0/PT/CUSA01127";

    private static final int PICK_FOLDER = 1;
    private static final int PICK_FILES = 2;

    private File targetDir;
    private TextView status;
    private ProgressBar progress;
    private LinearLayout buttons;
    private volatile boolean copying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        targetDir = new File(getExternalFilesDir(null), "CUSA01127");
        if (haveGame()) {
            startGame();
            return;
        }
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // back from the all files access screen, or the files were copied over USB meanwhile
        if (status != null && !copying && haveGame()) {
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
        startActivity(new Intent(this, PTActivity.class));
        finish();
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
        column.addView(text("Para jugar necesitas tus archivos de P.T. (CUSA01127):\n"
                + "chunk1.psarc, texture.qar y pathid_list_ps4.bin.\n\n"
                + "Elige la carpeta donde están (o los tres archivos) y la app los copia a su propia carpeta. "
                + "Es una sola vez y no necesita permisos especiales; después puedes borrar los originales.", 15, false));

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        column.addView(buttons);
        buttons.addView(button("Elegir la carpeta con los archivos", v -> pickFolder()));
        buttons.addView(button("Elegir los 3 archivos", v -> pickFiles()));
        buttons.addView(button("Usar " + SHARED_DIR + " (acceso a todos los archivos)", v -> requestAllFilesAccess()));
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

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, PICK_FOLDER);
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
    private void searchTree(Uri tree, String documentId, int depth, Map<String, Source> found) {
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
                searchTree(tree, folder, depth + 1, found);
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
        Map<String, Source> found = new HashMap<>();
        try {
            if (requestCode == PICK_FOLDER && data.getData() != null) {
                Uri tree = data.getData();
                searchTree(tree, DocumentsContract.getTreeDocumentId(tree), 0, found);
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
        if (!missing.isEmpty()) {
            status.setText("No encontré: " + String.join(", ", missing) + "\nElige la carpeta CUSA01127 que los tiene (o los tres archivos).");
            return;
        }
        copy(found);
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
        if (total > 0) {
            progress.setProgress((int) Math.min(1000, done * 1000 / total));
            status.setText(String.format(Locale.ROOT, "Copiando %s… %.0f%% (%.2f de %.2f GB)", name, done * 100.0 / total, done / 1e9, total / 1e9));
        } else {
            status.setText(String.format(Locale.ROOT, "Copiando %s… %.2f GB", name, done / 1e9));
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
