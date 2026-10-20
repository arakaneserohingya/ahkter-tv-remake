package com.kulwant.atvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Intercepts Google TV's stock launcher window state changes
 * and seamlessly redirects back to our custom TV launcher.
 */
public class HomeInterceptService extends AccessibilityService {
    private static final String TAG = "HomeIntercept";
    private static final long DEBOUNCE_MS = 80;
    private long lastLaunchTime = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Set<String> targetLaunchers = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        initLaunchers();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        initLaunchers();
        Log.i(TAG, "HomeInterceptService connected and active");
    }

    private void initLaunchers() {
        targetLaunchers.clear();
        // Google TV & Android TV stock launchers only
        targetLaunchers.add("com.google.android.apps.tv.launcherx");
        targetLaunchers.add("com.google.android.tvlauncher");
        targetLaunchers.add("com.android.launcher");
        targetLaunchers.add("com.android.launcher3");
        Log.i(TAG, "Target stock launchers: " + targetLaunchers);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgName = event.getPackageName();
            if (pkgName == null) return;
            String pkg = pkgName.toString();

            // Do not intercept ourselves, settings, or setup/recovery
            if (pkg.equals(getPackageName())
                    || pkg.contains("settings")
                    || pkg.contains("setupwraith")) {
                return;
            }

            if (targetLaunchers.contains(pkg)) {
                long now = SystemClock.uptimeMillis();
                if (now - lastLaunchTime < DEBOUNCE_MS) {
                    return;
                }
                lastLaunchTime = now;
                Log.i(TAG, "Intercepted stock launcher [" + pkg + "], redirecting to HomeActivity");
                launchHome();

                handler.removeCallbacks(delayedLaunch);
                handler.postDelayed(delayedLaunch, 250);
            }
        }
    }

    private final Runnable delayedLaunch = this::launchHome;

    private void launchHome() {
        try {
            Intent intent = new Intent(this, HomeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start HomeActivity", t);
        }
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "HomeInterceptService interrupted");
    }
}
