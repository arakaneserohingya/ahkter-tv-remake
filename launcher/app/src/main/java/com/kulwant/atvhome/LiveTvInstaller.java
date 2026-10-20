package com.kulwant.atvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dadb.AdbKeyPair;
import dadb.Dadb;

/**
 * Ensures that the Indian Live TV companion app (com.nk.live_tv) is installed
 * whenever the launcher is installed or opened.
 */
public final class LiveTvInstaller {
    private static final String TAG = "LiveTvInstaller";
    public static final String LIVE_TV_PACKAGE = "com.nk.live_tv";
    private static final String ASSET_APK = "live-tv.apk";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static volatile boolean isInstalling = false;

    private LiveTvInstaller() {}

    /** Checks whether Live TV is currently installed on the device. */
    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(LIVE_TV_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Checks if Live TV is installed; if not, extracts the bundled APK from assets
     * and installs it in the background via local Dadb or system PackageInstaller.
     */
    public static void checkAndInstall(Context context) {
        if (isInstalled(context) || isInstalling) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            if (isInstalled(appContext) || isInstalling) return;
            isInstalling = true;

            File tempApk = null;
            try {
                // 1. Check if the asset exists
                boolean assetExists = false;
                try (InputStream test = appContext.getAssets().open(ASSET_APK)) {
                    assetExists = true;
                } catch (Exception ignored) {
                }

                if (!assetExists) {
                    Log.w(TAG, "Bundled " + ASSET_APK + " not found in assets.");
                    isInstalling = false;
                    return;
                }

                Log.i(TAG, "Live TV is not installed. Extracting bundled APK from assets...");
                tempApk = new File(appContext.getCacheDir(), "livetv_install.apk");
                try (InputStream in = appContext.getAssets().open(ASSET_APK);
                     OutputStream out = new FileOutputStream(tempApk)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }

                // 2. Try installation via Dadb (local network ADB)
                boolean installedViaDadb = false;
                try {
                    File priv = new File(appContext.getFilesDir(), "adbkey");
                    File pub = new File(appContext.getFilesDir(), "adbkey.pub");
                    if (!priv.isFile() || !pub.isFile()) {
                        AdbKeyPair.Companion.generate(priv, pub);
                    }
                    AdbKeyPair keys = AdbKeyPair.Companion.read(priv, pub);
                    try (Dadb dadb = Dadb.Companion.create("127.0.0.1", 5555, keys, 5000, 0)) {
                        dadb.install(tempApk, "-r", "-t");
                        installedViaDadb = true;
                        Log.i(TAG, "Live TV installed successfully via local Dadb!");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Dadb install failed or 127.0.0.1:5555 not ready: " + e.getMessage());
                }

                // 3. Fallback to Android PackageInstaller if Dadb could not complete
                if (!installedViaDadb && !isInstalled(appContext)) {
                    Log.i(TAG, "Attempting install via system PackageInstaller...");
                    PackageInstaller packageInstaller = appContext.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    int sessionId = packageInstaller.createSession(params);
                    try (PackageInstaller.Session session = packageInstaller.openSession(sessionId);
                         OutputStream out = session.openWrite("livetv_session", 0, tempApk.length());
                         InputStream in = new FileInputStream(tempApk)) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        session.fsync(out);
                        Intent callbackIntent = new Intent(appContext, HomeActivity.class);
                        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                        if (Build.VERSION.SDK_INT >= 23) {
                            flags |= PendingIntent.FLAG_IMMUTABLE;
                        }
                        PendingIntent pi = PendingIntent.getActivity(appContext, 0, callbackIntent, flags);
                        session.commit(pi.getIntentSender());
                    }
                }

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (isInstalled(appContext)) {
                        Toast.makeText(appContext, "Live TV installed", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error installing Live TV: " + e.getMessage(), e);
            } finally {
                if (tempApk != null && tempApk.exists()) {
                    tempApk.delete();
                }
                isInstalling = false;
            }
        });
    }
}
