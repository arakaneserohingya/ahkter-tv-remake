package com.kulwant.atvhome;

import android.content.Context;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Glass dropdown menu for TV remote users to switch wallpaper modes (Mix Both, Local, Unsplash),
 * pick & download curated Unsplash 4K wallpapers directly on the TV, or skip to next photo.
 */
final class WallpaperMenu {

    static final class Action {
        final String title;
        final String subtitle;
        final Runnable run;

        Action(String title, String subtitle, Runnable run) {
            this.title = title;
            this.subtitle = subtitle;
            this.run = run;
        }
    }

    final FrameLayout panel;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final Context c;
    private final List<Action> actions = new ArrayList<>();
    private final List<View> rows = new ArrayList<>();
    private int sel;
    private boolean open;   // false as soon as close() starts: keys go back to the screen during the fade
    private long pressedAt;

    interface Callback {
        void onNext();
        void onDeleteCurrent();
    }

    private final Callback callback;

    WallpaperMenu(Context c, Callback callback) {
        this.c = c;
        this.callback = callback;
        panel = new FrameLayout(c);
        panel.setBackground(Ui.rounded(0xEB1C1C1E, Ui.dp(c, 16), Ui.dp(c, 1), 0x24FFFFFF));
        panel.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        panel.setElevation(Ui.dp(c, 12));
        panel.setVisibility(View.GONE);

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new FrameLayout.LayoutParams(
                Ui.dp(c, 240), Math.min(Ui.dp(c, 380), c.getResources().getDisplayMetrics().heightPixels - Ui.dp(c, 100))));
    }

    boolean isOpen() {
        return open;
    }

    void open(int leftPx, int topPx) {
        buildActions();
        list.removeAllViews();
        rows.clear();

        for (int i = 0; i < actions.size(); i++) {
            final int idx = i;
            Action a = actions.get(i);

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 14), Ui.dp(c, 7), Ui.dp(c, 14), Ui.dp(c, 7));

            TextView t = Ui.text(c, 500, 14, 0xFFFFFFFF);
            t.setText(a.title);
            row.addView(t);

            if (a.subtitle != null && !a.subtitle.isEmpty()) {
                TextView sub = Ui.text(c, 400, 11, 0x8AFFFFFF);
                sub.setText(a.subtitle);
                row.addView(sub);
            }

            row.setOnClickListener(v -> {
                sel = idx;
                paint();
                close();
                if (a.run != null) a.run.run();
            });

            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rows.add(row);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.max(Ui.dp(c, 20), leftPx - Ui.dp(c, 120));
        lp.topMargin = topPx;
        panel.setLayoutParams(lp);

        sel = 0;
        paint();

        panel.setVisibility(View.VISIBLE);
        open = true;
        panel.setAlpha(0f);
        panel.setPivotY(0);
        panel.setScaleY(0.94f);
        panel.animate().alpha(1f).scaleY(1f).setStartDelay(0).setDuration(160).start();
    }

    /** OK on the selected row: it sinks and springs back like a pressed key. */
    void pressSelected() {
        if (sel < 0 || sel >= rows.size()) return;
        pressedAt = SystemClock.uptimeMillis();
        Ui.press(rows.get(sel), Ui.PRESS_SCALE_ROW);
    }

    /** Lets a row just pressed show its press before the menu fades out. */
    private long pressShowing() {
        long since = SystemClock.uptimeMillis() - pressedAt;
        return since < Ui.PRESS_DOWN_MS ? Ui.PRESS_DOWN_MS - since : 0;
    }

    void close() {
        open = false;
        panel.animate().alpha(0f).setStartDelay(pressShowing()).setDuration(110).withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }

    void move(int d) {
        int n = sel + d;
        if (n < 0 || n >= actions.size()) return;
        sel = n;
        paint();
        // scroll into view
        if (sel >= 0 && sel < rows.size()) {
            View v = rows.get(sel);
            scroll.smoothScrollTo(0, v.getTop() - Ui.dp(c, 20));
        }
    }

    void executeSelected() {
        if (sel >= 0 && sel < actions.size()) {
            Action a = actions.get(sel);
            pressSelected();
            close();
            if (a.run != null) a.run.run();
        }
    }

    private void paint() {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setBackground(i == sel ? Ui.rounded(0x2EFFFFFF, Ui.dp(c, 10), Ui.dp(c, 1), 0x40FFFFFF) : null);
        }
    }

    private void buildActions() {
        actions.clear();
        String curMode = Wallpapers.getMode(c);

        // Rotation control
        actions.add(new Action("⏭ Next Wallpaper", "Switch to next wallpaper now", () -> {
            if (callback != null) callback.onNext();
        }));

        // Mode switchers
        boolean isBoth = Wallpapers.MODE_BOTH.equals(curMode);
        boolean isLocal = Wallpapers.MODE_LOCAL.equals(curMode);
        boolean isUnsplash = Wallpapers.MODE_UNSPLASH.equals(curMode);

        actions.add(new Action((isBoth ? "✓ " : "   ") + "✨ Mix Both", "Rotate local photos & Unsplash", () -> {
            Wallpapers.setMode(c, Wallpapers.MODE_BOTH);
            Ui.toast(c, "Wallpaper: Mix Both", Toast.LENGTH_SHORT);
        }));

        actions.add(new Action((isLocal ? "✓ " : "   ") + "📁 Local Only", "Rotate uploaded photos only", () -> {
            Wallpapers.setMode(c, Wallpapers.MODE_LOCAL);
            Ui.toast(c, "Wallpaper: Local Photos Only", Toast.LENGTH_SHORT);
        }));

        actions.add(new Action((isUnsplash ? "✓ " : "   ") + "🌄 Unsplash 4K Only", "Rotate Unsplash wallpapers only", () -> {
            Wallpapers.setMode(c, Wallpapers.MODE_UNSPLASH);
            Ui.toast(c, "Wallpaper: Unsplash 4K Only", Toast.LENGTH_SHORT);
        }));

        // Curated Unsplash 4K Downloads
        addUnsplash("🏔 Yosemite Valley", "Mountains & Forest 4K", "1506744038136-46273834b3fb");
        addUnsplash("🏔 Alpine Peaks Sunset", "Dramatic Snowy Peaks", "1464822759023-fed622ff2c3b");
        addUnsplash("🌌 Milky Way Galaxy", "Deep Starry Sky (OLED)", "1419242902214-272b3f66ee7a");
        addUnsplash("🌌 Earth From Space", "NASA Satellite View", "1446776811953-b23d57bd21aa");
        addUnsplash("🏙 Tokyo Rain Reflections", "Cyberpunk Neon City", "1509198397868-475647b2a1e5");
        addUnsplash("🌊 Maldives Turquoise", "Tropical Beach Paradise", "1507525428034-b723cf961d3e");
        addUnsplash("🌊 Emerald Wave Barrel", "Deep Ocean Wave", "1518837695005-2083093ee35b");
        addUnsplash("🕌 Sheikh Zayed Mosque", "Grand Marble Dome", "1564769625905-50e93615e769");
        addUnsplash("🕌 Sultan Qaboos Grand", "Islamic Architecture", "1584551246679-0daf3d275d0f");
        addUnsplash("🎨 Sahara Sand Dunes", "Minimalist Desert Shadows", "1507499739999-097706ad8914");
        addUnsplash("🎨 Ethereal Aurora", "Vibrant Northern Lights", "1534447677768-be436bb09401");

        // Delete current
        actions.add(new Action("🗑 Delete Current Wallpaper", "Remove photo from rotation", () -> {
            if (callback != null) callback.onDeleteCurrent();
        }));
    }

    private void addUnsplash(String title, String subtitle, String photoId) {
        actions.add(new Action("⬇ " + title, subtitle + " · Unsplash", () -> {
            Ui.toast(c, "Downloading 4K Wallpaper to TV...", Toast.LENGTH_SHORT);
            new Thread(() -> {
                try {
                    String url = "https://images.unsplash.com/photo-" + photoId + "?w=1920&q=85";
                    File f = Wallpapers.downloadAndAdd(c, url);
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    if (f != null) {
                        main.post(() -> Ui.toast(c, "✨ " + title + " set on TV!", Toast.LENGTH_SHORT));
                    } else {
                        main.post(() -> Ui.toast(c, "Album full (" + Wallpapers.MAX_PHOTOS
                                + " photos): delete one first", Toast.LENGTH_LONG));
                    }
                } catch (Exception e) {
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    main.post(() -> Ui.toast(c, "Download failed: Check Wi-Fi", Toast.LENGTH_SHORT));
                }
            }, "unsplash-tv-download").start();
        }));
    }
}
