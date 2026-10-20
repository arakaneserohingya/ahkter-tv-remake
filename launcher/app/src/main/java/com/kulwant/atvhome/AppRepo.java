package com.kulwant.atvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads launchable apps, their banners and a glow colour sampled from each banner. */
final class AppRepo {

    static final class App {
        String pkg;
        String label;
        ComponentName component;
        boolean leanback;
        Bitmap banner;
        int glow;
    }

    static final class Result {
        final List<App> dock = new ArrayList<>();
        final List<App> grid = new ArrayList<>();
    }

    private static final int BANNER_W = 320, BANNER_H = 180;

    static Result load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        collect(pm, byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(pm, byPkg, Intent.CATEGORY_LAUNCHER, false);
        byPkg.remove(c.getPackageName());
        Set<String> hidden = new HashSet<>(Arrays.asList(
                c.getResources().getStringArray(R.array.hidden_packages)));

        Result r = new Result();
        for (String pkg : dockPackages(c)) {
            App a = byPkg.remove(pkg);
            if (a != null) r.dock.add(a);
        }
        for (App a : byPkg.values()) if (!hidden.contains(a.pkg)) r.grid.add(a);
        Collator col = Collator.getInstance();
        Collections.sort(r.grid, (x, y) -> col.compare(x.label, y.label));

        for (App a : r.dock) render(c, pm, a);
        for (App a : r.grid) render(c, pm, a);
        return r;
    }

    /**
     * Dock order: the user's own list once they have edited the dock on the TV (long-press a
     * tile), stored in the "home" prefs as a comma-joined package list; the config.xml array
     * is only the first-run seed. An emptied dock is a valid saved state, not a missing pref.
     */
    private static List<String> dockPackages(Context c) {
        SharedPreferences sp = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        if (sp.contains("dock_pkgs")) {
            List<String> out = new ArrayList<>();
            String s = sp.getString("dock_pkgs", "");
            if (s != null && !s.isEmpty()) out.addAll(Arrays.asList(s.split(",")));
            return out;
        }
        return Arrays.asList(c.getResources().getStringArray(R.array.dock_packages));
    }

    private static void collect(PackageManager pm, Map<String, App> out, String category, boolean leanback) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (out.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.component = new ComponentName(ai.packageName, ai.name);
            if ("com.mediatek.miracast".equals(ai.packageName)) {
                a.label = "Miracast (Smart View)";
            } else {
                a.label = String.valueOf(ri.loadLabel(pm)).trim();
            }
            a.leanback = leanback;
            out.put(a.pkg, a);
        }
    }

    private static void render(Context c, PackageManager pm, App a) {
        if ("com.mediatek.miracast".equals(a.pkg)) {
            a.banner = renderMiracastBanner(c);
            a.glow = 0xFF2563EB; // vibrant Samsung royal blue glow
            return;
        }
        Bitmap b = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Drawable banner = null;
        try {
            ActivityInfo ai = pm.getActivityInfo(a.component, 0);
            banner = ai.loadBanner(pm);
            if (banner == null) banner = pm.getApplicationBanner(a.pkg);
            if (banner != null) {
                cv.drawColor(0xFF1E1E21);   // surface for banners with transparent areas
                banner.setBounds(0, 0, BANNER_W, BANNER_H);
                banner.draw(cv);
            } else {
                Drawable icon = ai.loadIcon(pm);
                int tint = sample(drawableBitmap(icon, 24, 24));
                float[] hsv = new float[3];
                Color.colorToHSV(tint, hsv);
                hsv[1] = Math.min(hsv[1], 0.6f);
                hsv[2] = Math.max(0.22f, Math.min(hsv[2], 0.32f));   // never vanish into the black page
                cv.drawColor(Color.HSVToColor(hsv));
                int s = BANNER_H * 56 / 100;
                int l = (BANNER_W - s) / 2, t = (BANNER_H - s) / 2;
                icon.setBounds(l, t, l + s, t + s);
                icon.draw(cv);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            cv.drawColor(0xFF2C2C2E);
        }
        a.banner = b;
        a.glow = vivid(sample(Bitmap.createScaledBitmap(b, 32, 18, true)));
    }

    private static Bitmap renderMiracastBanner(Context c) {
        Bitmap b = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);

        // Samsung Smart View style gradient: deep navy into obsidian
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setShader(new LinearGradient(
                0, 0, BANNER_W, BANNER_H,
                new int[]{ 0xFF101E3E, 0xFF0B1428, 0xFF060A14 },
                new float[]{ 0f, 0.55f, 1f },
                Shader.TileMode.CLAMP
        ));
        cv.drawRect(0, 0, BANNER_W, BANNER_H, bgPaint);

        // Ambient radial glow behind the cast icon
        Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setShader(new RadialGradient(
                BANNER_W / 2f, 62f, 75f,
                new int[]{ 0x662563EB, 0x1A1D4ED8, 0x00000000 },
                new float[]{ 0f, 0.55f, 1f },
                Shader.TileMode.CLAMP
        ));
        cv.drawCircle(BANNER_W / 2f, 62f, 75f, glowPaint);

        // Cast Icon (ic_screencast)
        Drawable icon = c.getDrawable(R.drawable.ic_screencast);
        if (icon != null) {
            int iconSize = 60;
            int l = (BANNER_W - iconSize) / 2;
            int t = 26;
            icon.setBounds(l, t, l + iconSize, t + iconSize);
            icon.setTint(0xFFFFFFFF);
            icon.draw(cv);
        }

        // Title: "Smart View"
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(0xFFFFFFFF);
        titlePaint.setTextSize(21f);
        titlePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        cv.drawText("Smart View", BANNER_W / 2f, 126f, titlePaint);

        // Subtitle: "MIRACAST"
        Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subPaint.setColor(0xFF93C5FD);
        subPaint.setTextSize(11f);
        subPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subPaint.setTextAlign(Paint.Align.CENTER);
        subPaint.setLetterSpacing(0.18f);
        cv.drawText("MIRACAST", BANNER_W / 2f, 148f, subPaint);

        return b;
    }

    private static Bitmap drawableBitmap(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        return b;
    }

    /** Average colour weighted towards saturated, bright pixels. */
    private static int sample(Bitmap b) {
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, wsum = 0;
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double w = 0.02 + hsv[1] * hsv[1] * hsv[2];
                r += Color.red(p) * w;
                g += Color.green(p) * w;
                bl += Color.blue(p) * w;
                wsum += w;
            }
        }
        if (wsum == 0) return 0xFF8AA4FF;
        return Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum));
    }

    private static int vivid(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.15f) {           // near-grey banner: soft cool white glow
            return 0xFFB8C4E0;
        }
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.max(hsv[2], 0.9f);
        return Color.HSVToColor(hsv);
    }

    static Intent launchIntent(PackageManager pm, App a) {
        Intent i = a.leanback ? pm.getLeanbackLaunchIntentForPackage(a.pkg) : null;
        if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(a.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
}
