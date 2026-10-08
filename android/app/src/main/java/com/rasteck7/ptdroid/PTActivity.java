package com.rasteck7.ptdroid;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

/**
 * The P.T. port's activity (docs/android.md): SDL's activity running libmain.so, the CMake build of the game with SDL
 * linked in. The native side calls hasFilesAccess() and requestFilesAccess() (src/engine/platform/android_support.cpp)
 * while it looks for the game files.
 */
public class PTActivity extends SDLActivity {

    @Override
    protected String[] getLibraries() {
        return new String[] { "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** True when the app may read /storage/emulated/0/PT (all files access). */
    public boolean hasFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    /** Opens the system screen where the person grants all files access to this app. */
    public void requestFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }
}
