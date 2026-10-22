package com.kulwant.atvhome;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.BoringLayout;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;

/** Small helpers: dp, the Outfit variable font at a given weight, shared drawables, press feedback and toasts. */
final class Ui {
    private static final SparseArray<Typeface> FONTS = new SparseArray<>();

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static Typeface font(Context c, int weight) {
        Typeface t = FONTS.get(weight);
        if (t == null) {
            try {
                t = new Typeface.Builder(c.getAssets(), "fonts/Outfit.ttf")
                        .setFontVariationSettings("'wght' " + weight)
                        .build();
            } catch (Exception e) {
                t = Typeface.create("sans-serif-light", Typeface.NORMAL);
            }
            FONTS.put(weight, t);
        }
        return t;
    }

    static TextView text(Context c, int weight, float sp, int color) {
        TextView t = new Label(c);
        t.setTypeface(font(c, weight));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    /**
     * Lines up text exactly as when the launcher targeted Android 9. Since Android 13, single-line
     * text with an emoji or symbol from a fallback font (⏭ ✨ ►) gets that font's taller line height,
     * which moved menu rows down a few pixels; before, only text needing the full layout got it.
     */
    static final class Label extends TextView {
        Label(Context c) {
            super(c);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            setFallbackLineSpacing(text == null || BoringLayout.isBoring(text, getPaint()) == null);
        }
    }

    static GradientDrawable rounded(int fill, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) d.setStroke(strokePx, strokeColor);
        return d;
    }

    // ---------------------------------------------------------------- press feedback

    /** How far a tile or top-bar button sinks while OK is down; menu rows and dialog buttons sink less. */
    static final float PRESS_SCALE = 0.93f, PRESS_SCALE_ROW = 0.95f;
    /** How long the sink takes. A quicker press (the phone remote's taps) still shows it in full. */
    static final long PRESS_DOWN_MS = 90;
    private static final long PRESS_UP_MS = 280;

    /** OK went down on v: it sinks like a real key. Pair with release(v). */
    static void pressDown(View v, float scale) {
        Object pending = v.getTag(R.id.press_release);
        if (pending instanceof Runnable) v.removeCallbacks((Runnable) pending);
        v.setTag(R.id.press_at, SystemClock.uptimeMillis());
        scaleTo(v, scale, PRESS_DOWN_MS, new DecelerateInterpolator(2f));
    }

    /** OK came up: v springs back with a slight overshoot, once its sink has shown. */
    static void release(View v) {
        Object at = v.getTag(R.id.press_at);
        long sink = scaled(PRESS_DOWN_MS);
        long held = at instanceof Long ? SystemClock.uptimeMillis() - (Long) at : sink;
        Runnable up = () -> scaleTo(v, 1f, PRESS_UP_MS, new OvershootInterpolator(2.5f));
        v.setTag(R.id.press_release, up);
        if (held >= sink) up.run();
        else v.postDelayed(up, sink - held);
    }

    /** ms as animations run on this TV: the "animation scale" setting (0 = animations off) stretches them. */
    static long scaled(long ms) {
        return (long) (ms * ValueAnimator.getDurationScale());
    }

    /** Down and straight back up, for actions taken on the press itself (a menu row chosen). */
    static void press(View v, float scale) {
        pressDown(v, scale);
        release(v);
    }

    /** Back to rest at once: Home went behind an app with OK still down, or focus moved. */
    static void resetPress(View v) {
        Object pending = v.getTag(R.id.press_release);
        if (pending instanceof Runnable) v.removeCallbacks((Runnable) pending);
        Object running = v.getTag(R.id.press_anim);
        if (running instanceof Animator) ((Animator) running).cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
    }

    /** Its own animator rather than v.animate(): that one carries its settings over to v's other animations. */
    private static void scaleTo(View v, float s, long ms, TimeInterpolator interpolator) {
        Object running = v.getTag(R.id.press_anim);
        if (running instanceof Animator) ((Animator) running).cancel();
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, s), PropertyValuesHolder.ofFloat(View.SCALE_Y, s));
        a.setDuration(ms);
        a.setInterpolator(interpolator);
        v.setTag(R.id.press_anim, a);
        a.start();
    }

    // ---------------------------------------------------------------- toasts

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<View> OVERLAY_VIEWS = new ArrayDeque<>();
    private static final ArrayDeque<Integer> OVERLAY_DURATIONS = new ArrayDeque<>();
    private static boolean overlayShowing;

    /**
     * Toast.makeText(...).show() with the look it had when the launcher targeted Android 9: the app
     * draws the system's classic toast layout. For newer targets Android draws text toasts itself (a
     * pill with the app icon), so the launcher draws that layout: as a toast while Home is on screen,
     * and in its own overlay window otherwise, because Android hides app-drawn toasts from the background.
     */
    static void toast(Context c, CharSequence text, int duration) {
        View v = classicToastView(c, text);
        if (v != null && HomeActivity.resumed) {
            Toast t = new Toast(c);
            t.setView(v);
            t.setDuration(duration);
            t.show();
        } else if (v != null && Settings.canDrawOverlays(c)) {
            OVERLAY_VIEWS.add(v);
            OVERLAY_DURATIONS.add(duration);
            if (!overlayShowing) nextOverlayToast(c.getApplicationContext());
        } else {
            Toast.makeText(c, text, duration).show();
        }
    }

    /** The view Android 9-era Toast.makeText built: the framework's own toast layout. */
    private static View classicToastView(Context c, CharSequence text) {
        int layout = Resources.getSystem().getIdentifier("transient_notification", "layout", "android");
        if (layout == 0) return null;
        try {
            View v = LayoutInflater.from(c).inflate(layout, null);
            TextView message = v.findViewById(android.R.id.message);
            if (message == null) return null;
            message.setText(text);
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Shows queued toasts one after another, with the window settings Android uses for toasts. */
    private static void nextOverlayToast(Context app) {
        View v = OVERLAY_VIEWS.poll();
        Integer duration = OVERLAY_DURATIONS.poll();
        overlayShowing = v != null;
        if (v == null) return;

        WindowManager wm = app.getSystemService(WindowManager.class);
        Resources sys = Resources.getSystem();
        // not FLAG_NOT_TOUCHABLE like a real toast: Android caps an app overlay that touches pass
        // through at 80% opacity. Not focusable, so the remote still drives the app underneath.
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.windowAnimations = android.R.style.Animation_Toast;
        p.setTitle("Toast");
        p.setFitInsetsIgnoringVisibility(true);
        int gravityId = sys.getIdentifier("config_toastDefaultGravity", "integer", "android");
        int yId = sys.getIdentifier("toast_y_offset", "dimen", "android");
        int gravity = gravityId != 0 ? sys.getInteger(gravityId) : Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        p.gravity = Gravity.getAbsoluteGravity(gravity, sys.getConfiguration().getLayoutDirection());
        if ((p.gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.FILL_HORIZONTAL) p.horizontalWeight = 1f;
        if ((p.gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.FILL_VERTICAL) p.verticalWeight = 1f;
        p.y = yId != 0 ? sys.getDimensionPixelSize(yId) : 0;
        try {
            wm.addView(v, p);
        } catch (RuntimeException e) {
            nextOverlayToast(app);
            return;
        }

        // as Android does for toasts: tell screen readers, and stay up as long as the user needs
        AccessibilityManager am = app.getSystemService(AccessibilityManager.class);
        int ms = duration != null && duration == Toast.LENGTH_LONG ? 3500 : 2000;
        if (am != null && am.isEnabled()) {
            AccessibilityEvent e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
            e.setClassName(Toast.class.getName());
            e.setPackageName(app.getPackageName());
            v.dispatchPopulateAccessibilityEvent(e);
            am.sendAccessibilityEvent(e);
        }
        if (am != null) ms = am.getRecommendedTimeoutMillis(ms, AccessibilityManager.FLAG_CONTENT_TEXT);
        MAIN.postDelayed(() -> {
            try {
                wm.removeViewImmediate(v);
            } catch (RuntimeException ignored) {
            }
            nextOverlayToast(app);
        }, ms);
    }
}
