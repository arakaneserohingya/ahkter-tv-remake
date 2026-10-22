package com.kulwant.atvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen. All D-pad navigation is handled here (no Android focus search), so
 * movement is deterministic: zones TOP (pills) / DOCK / GRID, Up/Down move exactly one row.
 */
public class HomeActivity extends Activity implements Wallpapers.Listener {

    private enum Zone { TOP, DOCK, GRID }

    private static final int PILLS = 5;
    /** apps per row, dock and grid (config.xml: columns) */
    private int COLS;

    /** Home is the resumed activity: Android only shows the launcher's own toasts then (Ui.toast) */
    static volatile boolean resumed;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    // layout (px)
    private int W, H, side, bandTop, gridTop, rowH;

    private FrameLayout root, content;
    private TextView greeting, clock, date, dockTitle, wifiText, hijriText, prayerText;
    private String prayerSummary;
    private LinearLayout pillRow;
    // follower counters under the pills: any number of YouTube / Instagram / Facebook accounts
    private final List<String> socialKinds = new ArrayList<>();   // "yt", "ig" or "fb", one per pill
    private final List<String> socialIds = new ArrayList<>();
    private final List<View> socialPills = new ArrayList<>();
    private final List<TextView> socialTexts = new ArrayList<>();
    private boolean hasSocial;
    private static final long SUBS_REFRESH_MS = 5 * 60_000L;
    private final View[] pills = new View[PILLS];
    private GlassHighlight highlight;
    private View glow;
    private GradientDrawable glowBg;
    private RecyclerView dockRv, gridRv;
    private TileAdapter dockAdapter, gridAdapter;
    private InputsMenu inputs;
    private DockMenu dockMenu;
    private WallpaperMenu wallpaperMenu;

    // dock editing (long-press OK on a tile): the dock list itself lives in prefs, see AppRepo
    private static final int MENU_NONE = 0, MENU_DOCK = 1, MENU_GRID = 2, MENU_REORDER = 3, MENU_PICKER = 4;
    private int menuMode = MENU_NONE;
    private int menuDockIdx, menuGridIdx;
    private List<AppRepo.App> pickerApps;
    /** how long OK is held to open a tile's menu: the system's long-press time (400 ms on TVs) */
    private static final long LONG_PRESS_MS = ViewConfiguration.getLongPressTimeout();
    /** down time of the OK press on a tile being followed, or -1. Its repeats and release share it. */
    private long selectDownTime = -1;
    private boolean longFired;

    private Zone zone = Zone.DOCK;
    private int topIdx, dockIdx, gridIdx;
    private final RectF hl = new RectF();
    private float hlRadius, hlRadiusNow;
    private boolean hlPlaced;
    private ValueAnimator hlAnim, glowAnim;
    private int glowColor = 0;
    private int lastTitleApp = -1;
    private boolean titleChanging;

    // rotating wallpapers: Files from the photo store (see Wallpapers)
    private final List<Object> wallpapers = new ArrayList<>();
    private final ImageView[] wpViews = new ImageView[2];
    private int wpIdx, wpFront;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        // The multi-counter build caches counts under new keys, so don't let the previous
        // build's "last fetched" time delay the first fetch — it made the pills wait out
        // the old build's freshness window showing nothing.
        if (!prefs.getBoolean("counts_v2", false)) {
            prefs.edit().putLong("counts_at", 0).putBoolean("counts_v2", true).apply();
        }
        COLS = Math.max(3, Math.min(6, getResources().getInteger(R.integer.columns)));
        zone = Zone.values()[prefs.getInt("zone", Zone.DOCK.ordinal())];
        topIdx = prefs.getInt("top", 0);
        dockIdx = prefs.getInt("dock", 0);
        gridIdx = prefs.getInt("grid", 0);
        if (zone == Zone.TOP) zone = Zone.DOCK;   // come back to apps, not the buttons

        buildUi();
        setContentView(root);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        IntentFilter time = new IntentFilter();
        time.addAction(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        time.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        time.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(statusReceiver, time);

        IntentFilter pk = new IntentFilter();
        pk.addAction(Intent.ACTION_PACKAGE_ADDED);
        pk.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pk.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pk.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pk.addDataScheme("package");
        registerReceiver(packageReceiver, pk);

        loadApps();

        Wallpapers.setListener(this);
        startForegroundService(new Intent(this, PhoneService.class));
        LiveTvInstaller.installOnce(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updateClock();
        updateWifi();
        updatePrayerTimes();
        if (inputs.isOpen()) inputs.close();
        scheduleMinute();
        scheduleWallpaper();
        if (hasSocial && System.currentTimeMillis() - prefs.getLong("counts_at", 0) > SUBS_REFRESH_MS) {
            refreshSubs.run();
        } else if (hasSocial) {
            main.postDelayed(refreshSubs, SUBS_REFRESH_MS);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        // nothing ticks (and no wallpaper is decoded) while an app is in front
        main.removeCallbacks(minuteTick);
        main.removeCallbacks(nextWallpaper);
        main.removeCallbacks(refreshSubs);
        main.removeCallbacks(longPress);
        selectDownTime = -1;
        if (pressedView != null) {   // OK opened an app before it came up: come back unpressed
            Ui.resetPress(pressedView);
            Ui.resetPress(highlight);
            pressedView = null;
        }
        super.onPause();
    }

    /** Some TV firmwares don't deliver ACTION_TIME_TICK, so the clock runs on our own timer. */
    private final Runnable minuteTick = () -> {
        updateClock();
        scheduleMinute();
    };

    private void scheduleMinute() {
        main.removeCallbacks(minuteTick);
        long now = System.currentTimeMillis();
        main.postDelayed(minuteTick, 60_000 - now % 60_000 + 50);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close menus and return to the dock.
        if (inputs.isOpen()) inputs.close();
        if (wallpaperMenu != null && wallpaperMenu.isOpen()) wallpaperMenu.close();
        if (dockMenu.isOpen()) dockMenu.close();
        if (zone == Zone.GRID && hasWindowFocus()) {
            zone = Zone.DOCK;
            applyFocus(true);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(statusReceiver);
        unregisterReceiver(packageReceiver);
        Wallpapers.setListener(null);
        io.shutdownNow();
        super.onDestroy();
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(a)
                    || WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(a)) {
                updateWifi();
                updatePrayerTimes();
            } else updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                Uri data = i.getData();
                if (data != null) {
                    String pkg = data.getSchemeSpecificPart();
                    List<String> pkgs = dockPrefs();
                    if (pkgs.remove(pkg)) {
                        saveDock(pkgs);
                        return;
                    }
                }
            }
            loadApps();
        }
    };

    private void loadApps() {
        io.execute(() -> {
            AppRepo.Result r = AppRepo.load(getApplicationContext());
            main.post(() -> setApps(r));
        });
    }

    private void setApps(AppRepo.Result r) {
        dockAdapter.setApps(r.dock);
        gridAdapter.setApps(r.grid);
        int rows = (r.grid.size() + COLS - 1) / COLS;
        ViewGroup.LayoutParams glp = gridRv.getLayoutParams();
        glp.height = Math.max(rows, 1) * rowH;
        gridRv.setLayoutParams(glp);
        ViewGroup.LayoutParams clp = content.getLayoutParams();
        clp.height = gridTop + Math.max(rows, 1) * rowH + Ui.dp(this, 40);
        content.setLayoutParams(clp);
        lastTitleApp = -1;
        clampIndices();
        // Wait for the RecyclerViews to lay out before placing the highlight.
        content.post(() -> content.post(() -> applyFocus(hlPlaced)));
    }

    private void clampIndices() {
        int nd = dockAdapter.getItemCount(), ng = gridAdapter.getItemCount();
        dockIdx = Math.max(0, Math.min(dockIdx, nd - 1));
        gridIdx = Math.max(0, Math.min(gridIdx, ng - 1));
        if (zone == Zone.GRID && ng == 0) zone = Zone.DOCK;
        if (zone == Zone.DOCK && nd == 0) zone = ng > 0 ? Zone.GRID : Zone.TOP;
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void buildUi() {
        W = getResources().getDisplayMetrics().widthPixels;
        H = getResources().getDisplayMetrics().heightPixels;
        side = dp(40);
        // cells shrink to fit COLS per row; cards keep the 16:9 TV banner shape
        int gap = dp(24);
        int dockCell = (W - 2 * side - 2 * dp(16)) / COLS, dockCardW = dockCell - gap;
        int dockCardH = dockCardW * 9 / 16;
        int gridCell = (W - 2 * side + gap) / COLS, gridCardW = gridCell - gap;
        int gridCardH = gridCardW * 9 / 16;
        int bandH = dockCardH + dp(28), bandBottom = dp(26);
        bandTop = H - bandBottom - bandH;
        rowH = gridCardH + dp(42);
        gridTop = H + dp(56);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(false);

        boolean useWallpaper = getResources().getBoolean(R.bool.use_wallpaper);
        if (useWallpaper) {
            findWallpapers();
            for (int i = 0; i < 2; i++) {
                wpViews[i] = new ImageView(this);
                wpViews[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
                wpViews[i].setAlpha(i == 0 ? 1f : 0f);
                root.addView(wpViews[i], new FrameLayout.LayoutParams(-1, -1));
            }
            if (!wallpapers.isEmpty()) {
                wpIdx = prefs.getInt("wallpaper", 0) % wallpapers.size();
                wpViews[0].setImageBitmap(loadWallpaper(wallpapers.get(wpIdx)));
            }
            // darken: evenly, and more towards the bottom where the dock and grid sit
            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x59000000, 0x66000000, 0xD9000000}));
            root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        }

        content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, H * 2));

        // ambient glow behind the dock title
        glow = new View(this);
        glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        // circle fades out before the view edges; scaleY flattens it into an ellipse
        glowBg.setGradientRadius(dp(290));
        glowBg.setGradientCenter(0.4f, 0.5f);
        glowBg.setColors(new int[]{0, 0});
        glow.setBackground(glowBg);
        glow.setScaleY(0.45f);
        int glowCx = side + dp(110), glowCy = bandTop - dp(44);
        content.addView(glow, at(dp(900), dp(600), glowCx - dp(360), glowCy - dp(300)));

        // dock band
        View band = new View(this);
        band.setBackground(Ui.rounded(useWallpaper ? 0x8C141416 : 0xFF141416, dp(28), dp(1), 0x14FFFFFF));
        content.addView(band, at(W - 2 * side, bandH, side, bandTop));

        // the single glass highlight (below tiles and pills)
        highlight = new GlassHighlight(this);
        highlight.setAlpha(0f);
        content.addView(highlight, new FrameLayout.LayoutParams(-1, -1));

        // greeting, clock, date
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        tl.setClipChildren(false);
        tl.setClipToPadding(false);
        greeting = Ui.text(this, 400, 16, 0x99FFFFFF);
        clock = Ui.text(this, 150, 78, 0xFFFFFFFF);
        clock.setLetterSpacing(-0.02f);
        date = Ui.text(this, 300, 17, 0x99FFFFFF);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        tl.addView(greeting, glp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = dp(4);
        clp.leftMargin = -dp(3);
        tl.addView(clock, clp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = dp(6);
        tl.addView(date, dlp);
        hijriText = Ui.text(this, 300, 14, 0x80FFFFFF);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = dp(4);
        tl.addView(hijriText, hlp);
        prayerText = Ui.text(this, 400, 15, 0xFFFFFFFF);
        prayerText.setSingleLine(true);
        LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(-2, -2);
        prlp.topMargin = dp(4);
        tl.addView(prayerText, prlp);
        tl.setOnClickListener(v -> {
            if (prayerSummary != null && !prayerSummary.isEmpty()) {
                Ui.toast(this, prayerSummary, Toast.LENGTH_LONG);
            } else {
                zone = Zone.DOCK;
                applyFocus(true);
            }
        });
        content.addView(tl, at(-2, -2, side, dp(34)));

        // pills: Inputs, AirPlay, Wi-Fi, Settings
        pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pills[0] = pill(R.drawable.ic_input, "Inputs");
        pills[1] = pill(R.drawable.ic_airplay, "AirPlay");
        pills[2] = pill(R.drawable.ic_wallpaper, "Wallpaper");
        pills[3] = pill(R.drawable.ic_wifi, "Wi-Fi");
        wifiText = (TextView) ((ViewGroup) pills[3]).getChildAt(1);
        pills[4] = pill(R.drawable.ic_settings, null);
        for (int i = 0; i < PILLS; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            if (i > 0) lp.leftMargin = dp(10);
            pillRow.addView(pills[i], lp);
            final int pillIdx = i;
            pills[i].setOnClickListener(v -> {
                zone = Zone.TOP;
                topIdx = pillIdx;
                applyFocus(true);
                activate();
            });
        }
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        plp.topMargin = dp(36);
        plp.rightMargin = side;
        content.addView(pillRow, plp);

        // follower counters, right-aligned under the pills (display only, never focused)
        LinearLayout counters = new LinearLayout(this);
        counters.setOrientation(LinearLayout.VERTICAL);
        counters.setGravity(Gravity.END);
        addSocialPills(counters, "yt", getResources().getStringArray(R.array.youtube_channels), R.drawable.ic_youtube);
        addSocialPills(counters, "ig", getResources().getStringArray(R.array.instagram_users), R.drawable.ic_instagram);
        addSocialPills(counters, "fb", getResources().getStringArray(R.array.facebook_pages), R.drawable.ic_facebook);
        FrameLayout.LayoutParams clp2 = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp2.topMargin = dp(36 + 36 + 2);
        clp2.rightMargin = side;
        content.addView(counters, clp2);

        // focused dock app name
        dockTitle = Ui.text(this, 250, 44, 0xF2FFFFFF);
        dockTitle.setLetterSpacing(-0.01f);
        content.addView(dockTitle, at(W - 2 * side, -2, side + dp(28), bandTop - dp(72)));

        // dock
        dockAdapter = new TileAdapter(dockCell, dockCardW, dockCardH, 0, false);
        dockAdapter.setOnTileClickListener(new TileAdapter.OnTileClickListener() {
            @Override
            public void onTileClick(int pos) {
                if (pos < 0 || pos >= dockAdapter.getItemCount()) return;
                zone = Zone.DOCK;
                dockIdx = pos;
                applyFocus(true);
                activate();
            }

            @Override
            public void onTileLongClick(int pos) {
                if (pos < 0 || pos >= dockAdapter.getItemCount()) return;
                zone = Zone.DOCK;
                dockIdx = pos;
                applyFocus(true);
                openTileMenu();
            }
        });
        dockRv = recycler(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false), dockAdapter);
        content.addView(dockRv, at(dockCell * COLS, dockCardH, side + dp(28) - (dockCell - dockCardW) / 2,
                bandTop + (bandH - dockCardH) / 2));

        // grid
        TextView appsHeader = Ui.text(this, 500, 15, 0x80FFFFFF);
        appsHeader.setText("Apps");
        appsHeader.setLetterSpacing(0.04f);
        appsHeader.setOnClickListener(v -> {
            zone = Zone.GRID;
            if (gridAdapter.getItemCount() > 0) gridIdx = 0;
            applyFocus(true);
        });
        content.addView(appsHeader, at(-2, -2, side + dp(2), gridTop - dp(30)));
        gridAdapter = new TileAdapter(gridCell, gridCardW, gridCardH, rowH - gridCardH, true);
        gridAdapter.setOnTileClickListener(new TileAdapter.OnTileClickListener() {
            @Override
            public void onTileClick(int pos) {
                if (pos < 0 || pos >= gridAdapter.getItemCount()) return;
                zone = Zone.GRID;
                gridIdx = pos;
                applyFocus(true);
                activate();
            }

            @Override
            public void onTileLongClick(int pos) {
                if (pos < 0 || pos >= gridAdapter.getItemCount()) return;
                zone = Zone.GRID;
                gridIdx = pos;
                applyFocus(true);
                openTileMenu();
            }
        });
        gridRv = recycler(new GridLayoutManager(this, COLS), gridAdapter);
        content.addView(gridRv, at(gridCell * COLS, rowH, side - (gridCell - gridCardW) / 2, gridTop));

        inputs = new InputsMenu(this);
        inputs.setOnSelectListener(this::launch);
        root.addView(inputs.panel);
        dockMenu = new DockMenu(this);
        dockMenu.setOnChoiceListener(this::onDockMenuChoice);
        root.addView(dockMenu.panel);

        wallpaperMenu = new WallpaperMenu(this, new WallpaperMenu.Callback() {
            @Override
            public void onNext() {
                main.removeCallbacks(nextWallpaper);
                nextWallpaper.run();
            }

            @Override
            public void onDeleteCurrent() {
                if (wallpapers.isEmpty()) return;
                Object cur = wallpapers.get(Math.min(wpIdx, wallpapers.size() - 1));
                if (cur instanceof java.io.File) {
                    String name = ((java.io.File) cur).getName().replace(".jpg", "");
                    Wallpapers.delete(HomeActivity.this, name);
                    Ui.toast(HomeActivity.this, "Wallpaper removed", Toast.LENGTH_SHORT);
                }
            }
        });
        root.addView(wallpaperMenu.panel);

        root.setOnGenericMotionListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_SCROLL) {
                float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (vScroll < -0.2f) {
                    down();
                    return true;
                } else if (vScroll > 0.2f) {
                    up();
                    return true;
                }
            }
            return false;
        });

        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (inputs.isOpen()) { inputs.close(); return true; }
                if (wallpaperMenu.isOpen()) { wallpaperMenu.close(); return true; }
                if (dockMenu.isOpen()) { dockMenu.close(); return true; }
                return false;
            }
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dy = e2.getY() - e1.getY();
                if (dy < -60) {
                    down();
                    return true;
                } else if (dy > 60) {
                    up();
                    return true;
                }
                return false;
            }
        });
        root.setOnTouchListener((v, event) -> gd.onTouchEvent(event));
    }

    private FrameLayout.LayoutParams at(int w, int h, int x, int y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }

    private RecyclerView recycler(RecyclerView.LayoutManager lm, TileAdapter a) {
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(lm);
        rv.setAdapter(a);
        rv.setItemAnimator(null);
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setFocusable(false);
        rv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rv.setNestedScrollingEnabled(false);
        return rv;
    }

    private View pill(int icon, String label) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(Ui.rounded(0x1FFFFFFF, dp(18), dp(1), 0x26FFFFFF));
        int padH = label == null ? dp(9) : dp(14);
        p.setPadding(padH, 0, padH, 0);
        p.setAlpha(0.8f);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        p.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        if (label != null) {
            TextView t = Ui.text(this, 400, 14, 0xFFFFFFFF);
            t.setText(label);
            t.setMaxWidth(dp(170));
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(8);
            p.addView(t, lp);
        }
        return p;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        String part = h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 17 ? "Good afternoon" : "Good evening";
        String name = getString(R.string.greeting_name).trim();
        greeting.setText(name.isEmpty() ? part : part + ", " + name);
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(DateFormat.format(h24 ? "H:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE, d MMMM", now));
        updatePrayerDisplay();
    }

    private void updatePrayerTimes() {
        PrayerRepo.get(this, this::onPrayerUpdated);
    }

    private void updatePrayerDisplay() {
        PrayerRepo.PrayerData d = PrayerRepo.computeCurrent(this);
        if (d != null) onPrayerUpdated(d);
        PrayerRepo.checkRefresh(this, this::onPrayerUpdated);
    }

    private void onPrayerUpdated(PrayerRepo.PrayerData data) {
        if (data == null) return;
        prayerSummary = data.fullSummary;
        if (hijriText != null && data.hijri != null && !data.hijri.isEmpty()) {
            hijriText.setText(data.hijri);
            hijriText.setVisibility(View.VISIBLE);
        }
        if (prayerText != null && data.formattedLine != null && data.formattedLine.length() > 0) {
            prayerText.setText(data.formattedLine);
            if (data.isNow) {
                prayerText.setTextColor(0xFF4CD964); // Apple green when prayer is now
            } else {
                prayerText.setTextColor(0xFFFFFFFF); // Clean white for upcoming prayer
            }
            prayerText.setVisibility(View.VISIBLE);
        }
    }

    // ---------------------------------------------------------------- wallpapers

    private boolean findWallpapers() {
        // bundled wallpapers are copied into the photo store on first run (see Wallpapers)
        wallpapers.clear();
        wallpapers.addAll(Wallpapers.activePhotos(this));
        return !wallpapers.isEmpty();
    }

    /** Decoded at screen size in RGB_565: about 1.8 MB each on a 720p TV. */
    private android.graphics.Bitmap loadWallpaper(Object src) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        decode(src, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= W && o.outHeight / (sample * 2) >= H) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
        return decode(src, o);
    }

    private android.graphics.Bitmap decode(Object src, android.graphics.BitmapFactory.Options o) {
        if (src instanceof Integer) return android.graphics.BitmapFactory.decodeResource(getResources(), (Integer) src, o);
        return android.graphics.BitmapFactory.decodeFile(((java.io.File) src).getPath(), o);
    }

    /** The phone page added, replaced, deleted or asked to show a photo: update the rotation now. */
    @Override
    public void onWallpapersChanged(java.io.File added) {
        if (wpViews[0] == null) return;
        Object current = wallpapers.isEmpty() ? null : wallpapers.get(Math.min(wpIdx, wallpapers.size() - 1));
        findWallpapers();
        if (wallpapers.isEmpty()) {
            for (ImageView v : wpViews) v.setImageDrawable(null);
            return;
        }
        int at = added != null ? wallpapers.indexOf(added) : wallpapers.indexOf(current);
        if (at < 0) {
            // the photo on screen was deleted: move on to the next one
            wpIdx = Math.max(0, wpIdx - 1) % wallpapers.size();
            main.removeCallbacks(nextWallpaper);
            nextWallpaper.run();
        } else if (added != null) {
            wpIdx = (at - 1 + wallpapers.size()) % wallpapers.size();
            main.removeCallbacks(nextWallpaper);
            nextWallpaper.run();
        } else {
            wpIdx = at;
        }
    }

    // ---------------------------------------------------------------- follower counters

    private final Runnable refreshSubs = () -> {
        main.removeCallbacks(this.refreshSubs);
        List<String> kinds = new ArrayList<>(socialKinds);
        List<String> ids = new ArrayList<>(socialIds);
        io.execute(() -> {
            String[] counts = new String[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                switch (kinds.get(i)) {
                    case "yt": counts[i] = SocialCount.youtube(ids.get(i)); break;
                    case "ig": counts[i] = SocialCount.instagram(ids.get(i)); break;
                    default:   counts[i] = SocialCount.facebook(ids.get(i)); break;
                }
            }
            main.post(() -> {
                boolean allOk = true;
                SharedPreferences.Editor e = prefs.edit();
                for (int i = 0; i < ids.size(); i++) {
                    if (counts[i] == null) {
                        allOk = false;   // keep showing the last known number
                        continue;
                    }
                    e.putString("count_" + i, counts[i]);
                    showCount(i, counts[i]);
                }
                if (allOk) e.putLong("counts_at", System.currentTimeMillis());
                e.apply();
                // retry sooner after a failure (e.g. Wi-Fi still connecting after boot)
                if (hasWindowFocus()) main.postDelayed(this.refreshSubs, allOk ? SUBS_REFRESH_MS : 5 * 60_000L);
            });
        });
    };

    /** Builds one counter pill per non-empty id in the given config array. */
    private void addSocialPills(LinearLayout counters, String kind, String[] ids, int logoRes) {
        for (String raw : ids) {
            String id = raw.trim();
            if (id.isEmpty()) continue;
            hasSocial = true;
            int i = socialIds.size();
            socialKinds.add(kind);
            socialIds.add(id);
            LinearLayout sp = new LinearLayout(this);
            sp.setOrientation(LinearLayout.HORIZONTAL);
            sp.setGravity(Gravity.CENTER_VERTICAL);
            sp.setBackground(Ui.rounded(0x33000000, dp(16), dp(1), 0x1FFFFFFF));
            sp.setPadding(dp(12), 0, dp(14), 0);
            ImageView logo = new ImageView(this);
            logo.setImageResource(logoRes);
            sp.addView(logo, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView tv = Ui.text(this, 500, 14, 0xFFFFFFFF);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.leftMargin = dp(8);
            sp.addView(tv, tlp);
            LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(-2, dp(32));
            plp2.topMargin = dp(8);
            counters.addView(sp, plp2);
            sp.setOnClickListener(v -> {
                Uri uri;
                if ("yt".equals(kind)) uri = Uri.parse("https://youtube.com/@" + id);
                else if ("ig".equals(kind)) uri = Uri.parse("https://instagram.com/" + id);
                else uri = Uri.parse("https://facebook.com/" + id);
                try {
                    launch(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {}
            });
            socialPills.add(sp);
            socialTexts.add(tv);
            showCount(i, prefs.getString("count_" + i, null));
        }
    }

    private void showCount(int i, String count) {
        if (i >= socialPills.size()) return;
        socialPills.get(i).setVisibility(count == null ? View.GONE : View.VISIBLE);
        if (count != null) socialTexts.get(i).setText(count + ("yt".equals(socialKinds.get(i)) ? " subscribers" : " followers"));
    }

    private void scheduleWallpaper() {
        main.removeCallbacks(nextWallpaper);
        if (wallpapers.size() < 2 || wpViews[0] == null) return;
        int secs = Math.max(5, getResources().getInteger(R.integer.wallpaper_seconds));
        main.postDelayed(nextWallpaper, secs * 1000L);
    }

    /** Fades the next wallpaper in over the current one, then frees the old bitmap. */
    private final Runnable nextWallpaper = () -> {
        if (wallpapers.isEmpty()) return;
        wpIdx = (wpIdx + 1) % wallpapers.size();
        prefs.edit().putInt("wallpaper", wpIdx).apply();
        Object src = wallpapers.get(wpIdx);
        io.execute(() -> {
            android.graphics.Bitmap bmp = loadWallpaper(src);
            if (bmp == null) return;
            main.post(() -> {
                ImageView front = wpViews[wpFront], back = wpViews[1 - wpFront];
                back.setImageBitmap(bmp);
                back.animate().alpha(1f).setDuration(2000).start();
                front.animate().alpha(0f).setDuration(2000).withEndAction(() -> front.setImageDrawable(null)).start();
                wpFront = 1 - wpFront;
                if (hasWindowFocus()) scheduleWallpaper();
            });
        });
    };

    @SuppressWarnings("deprecation")
    private void updateWifi() {
        String text = "Offline";
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni != null && ni.isConnected()) {
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
                text = "Ethernet";
            } else if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                text = "Wi-Fi";
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                String ssid = wi == null ? null : unquote(wi.getSSID());
                if ((ssid == null || ssid.equals("<unknown ssid>")) && wi != null) {
                    try {
                        List<WifiConfiguration> cfgs = wm.getConfiguredNetworks();
                        if (cfgs != null) for (WifiConfiguration cfg : cfgs) {
                            if (cfg.networkId == wi.getNetworkId()) ssid = unquote(cfg.SSID);
                        }
                    } catch (SecurityException ignored) {
                    }
                }
                if (ssid != null && !ssid.isEmpty() && !ssid.equals("<unknown ssid>")) text = ssid;
            }
        }
        wifiText.setText(text);
    }

    private static String unquote(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK
                || k == KeyEvent.KEYCODE_MENU;
        if (!nav) return super.dispatchKeyEvent(e);

        if (k == KeyEvent.KEYCODE_MENU && (zone == Zone.DOCK || zone == Zone.GRID)
                && !inputs.isOpen() && !(wallpaperMenu != null && wallpaperMenu.isOpen()) && !dockMenu.isOpen()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                pressFocused();
                releasePress();
                openTileMenu();
            }
            return true;
        }

        boolean select = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER;

        // On a dock/grid tile, OK launches on release; holding OK opens the tile's edit menu. Only the
        // repeats and release of the press that began on the tile count (same down time): the end of
        // a press that chose a menu item must not reopen the menu or launch the tile behind it.
        // Held time decides, not FLAG_LONG_PRESS: remotes that repeat by themselves set that flag on
        // their first repeat, which can come after a normal-length press.
        if (select && !inputs.isOpen() && !(wallpaperMenu != null && wallpaperMenu.isOpen()) && !dockMenu.isOpen()
                && (zone == Zone.DOCK || zone == Zone.GRID)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    selectDownTime = e.getDownTime();
                    longFired = false;
                    pressFocused();
                    main.removeCallbacks(longPress);
                    main.postDelayed(longPress, LONG_PRESS_MS);
                } else if (e.getDownTime() == selectDownTime && e.getEventTime() - e.getDownTime() >= LONG_PRESS_MS) {
                    main.removeCallbacks(longPress);   // a busy main thread can delay the timer
                    longPress.run();
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP && e.getDownTime() == selectDownTime) {
                selectDownTime = -1;
                main.removeCallbacks(longPress);
                releasePress();
                if (!longFired) activate();
            }
            return true;
        }

        if (select && e.getAction() == KeyEvent.ACTION_UP) releasePress();   // a top-bar button
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;

        // in these two menus, repeats of a held OK (the press that opened the menu, or one choosing
        // a row) are ignored like in the dock menu: they used to count as "close", so a slightly
        // long press opened the menu and shut it again
        if (inputs.isOpen()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) inputs.move(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) inputs.move(1);
            else if (select) {
                if (e.getRepeatCount() == 0) {
                    inputs.pressSelected();
                    Intent i = inputs.selected();
                    inputs.close();
                    if (i != null) launch(i);
                    else if (inputs.isLiveTvSelected()) {
                        LiveTvInstaller.checkAndInstall(this);
                        Ui.toast(this, "Installing Live TV...", Toast.LENGTH_SHORT);
                    }
                }
            } else inputs.close();   // back, left, right
            return true;
        }
        if (wallpaperMenu.isOpen()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) wallpaperMenu.move(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) wallpaperMenu.move(1);
            else if (select) {
                if (e.getRepeatCount() == 0) wallpaperMenu.executeSelected();
            } else wallpaperMenu.close();
            return true;
        }
        if (dockMenu.isOpen()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) dockMenu.move(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) dockMenu.move(1);
            else if (menuMode == MENU_REORDER && k == KeyEvent.KEYCODE_DPAD_LEFT) {
                reorderMove(-1);
            } else if (menuMode == MENU_REORDER && k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                reorderMove(1);
            } else if (select) {
                // repeats of the held OK that opened this menu must not close it again
                if (e.getRepeatCount() == 0) {
                    int choice = dockMenu.selectedIndex();
                    dockMenu.pressSelected();
                    onDockMenuChoice(choice);
                }
            } else if (k == KeyEvent.KEYCODE_BACK) {
                if (menuMode == MENU_REORDER || menuMode == MENU_PICKER) {
                    openTileMenu();
                } else {
                    dockMenu.close();
                    menuMode = MENU_NONE;
                }
            } else {
                dockMenu.close();
                menuMode = MENU_NONE;
            }
            return true;
        }

        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); break;
            case KeyEvent.KEYCODE_BACK:
                if (zone != Zone.DOCK && dockAdapter.getItemCount() > 0) {
                    zone = Zone.DOCK;
                    applyFocus(true);
                }
                break;
            default:
                if (e.getRepeatCount() == 0) {
                    if (select) pressFocused();
                    activate();
                }
        }
        return true;
    }

    private void up() {
        if (zone == Zone.DOCK) {
            zone = Zone.TOP;
        } else if (zone == Zone.GRID) {
            if (gridIdx >= COLS) gridIdx -= COLS;
            else if (dockAdapter.getItemCount() > 0) {
                zone = Zone.DOCK;
                dockIdx = Math.min(gridIdx, dockAdapter.getItemCount() - 1);
            } else zone = Zone.TOP;
        } else return;
        applyFocus(true);
    }

    private void down() {
        int ng = gridAdapter.getItemCount();
        if (zone == Zone.TOP) {
            if (dockAdapter.getItemCount() > 0) zone = Zone.DOCK;
            else if (ng > 0) zone = Zone.GRID;
            else return;
        } else if (zone == Zone.DOCK) {
            if (ng == 0) return;
            zone = Zone.GRID;
            gridIdx = Math.min(dockIdx, ng - 1);
        } else {
            if (gridIdx + COLS < ng) gridIdx += COLS;
            else if (gridIdx / COLS < (ng - 1) / COLS) gridIdx = ng - 1;
            else return;
        }
        applyFocus(true);
    }

    private void left() {
        if (zone == Zone.TOP && topIdx > 0) topIdx--;
        else if (zone == Zone.DOCK && dockIdx > 0) dockIdx--;
        else if (zone == Zone.GRID && gridIdx % COLS > 0) gridIdx--;
        else return;
        applyFocus(true);
    }

    private void right() {
        if (zone == Zone.TOP && topIdx < PILLS - 1) topIdx++;
        else if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount() - 1) dockIdx++;
        else if (zone == Zone.GRID && gridIdx % COLS < COLS - 1 && gridIdx + 1 < gridAdapter.getItemCount()) gridIdx++;
        else return;
        applyFocus(true);
    }

    private void activate() {
        if (zone == Zone.TOP) {
            if (topIdx == 0) {
                int[] loc = new int[2];
                pills[0].getLocationInWindow(loc);
                inputs.open(loc[0], loc[1] + pills[0].getHeight() + dp(8));
            } else if (topIdx == 1) {
                launchAirPlay();
            } else if (topIdx == 2) {
                int[] loc = new int[2];
                pills[2].getLocationInWindow(loc);
                wallpaperMenu.open(loc[0], loc[1] + pills[2].getHeight() + dp(8));
            } else if (topIdx == 3) {
                launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } else {
                launch(new Intent(Settings.ACTION_SETTINGS));
            }
        } else if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount()) {
            launch(AppRepo.launchIntent(getPackageManager(), dockAdapter.apps.get(dockIdx)));
        } else if (zone == Zone.GRID && gridIdx < gridAdapter.getItemCount()) {
            launch(AppRepo.launchIntent(getPackageManager(), gridAdapter.apps.get(gridIdx)));
        }
    }

    private void launchAirPlay() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("io.github.jqssun.airplay");
        if (intent != null) {
            launch(intent);
        } else {
            Ui.toast(this, "AirPlay receiver is not installed", Toast.LENGTH_SHORT);
        }
    }

    // ---------------------------------------------------------------- dock editing

    private final Runnable longPress = () -> {
        if (selectDownTime < 0 || longFired) return;
        longFired = true;   // the coming key release must not launch the app
        releasePress();     // the tile comes back up as its menu opens
        openTileMenu();
    };

    // ---------------------------------------------------------------- press feedback

    /** The tile or top-bar button OK is holding down; it sinks with the glass highlight around it. */
    private View pressedView;

    private void pressFocused() {
        releasePress();
        View v = focusedPressable();
        if (v == null) return;
        pressedView = v;
        highlight.setPivotX(hl.centerX());
        highlight.setPivotY(hl.centerY());
        Ui.pressDown(v, Ui.PRESS_SCALE);
        Ui.pressDown(highlight, Ui.PRESS_SCALE);
    }

    private void releasePress() {
        if (pressedView == null) return;
        Ui.release(pressedView);
        Ui.release(highlight);
        pressedView = null;
    }

    /** What sinks for the focused element: a top-bar button, or a whole tile around its banner's centre. */
    private View focusedPressable() {
        if (zone == Zone.TOP) return pills[topIdx];
        RecyclerView rv = zone == Zone.DOCK ? dockRv : gridRv;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(zone == Zone.DOCK ? dockIdx : gridIdx);
        if (vh == null) return null;
        View card = vh.itemView.findViewById(R.id.card);
        vh.itemView.setPivotX(vh.itemView.getWidth() / 2f);
        vh.itemView.setPivotY(card.getTop() + card.getHeight() / 2f);
        return vh.itemView;
    }

    /** The user's dock list: prefs once edited on the TV, otherwise the config.xml seed. */
    private List<String> dockPrefs() {
        List<String> out = new ArrayList<>();
        if (prefs.contains("dock_pkgs")) {
            String s = prefs.getString("dock_pkgs", "");
            if (s != null && !s.isEmpty()) out.addAll(Arrays.asList(s.split(",")));
        } else {
            out.addAll(Arrays.asList(getResources().getStringArray(R.array.dock_packages)));
        }
        return out;
    }

    private void saveDock(List<String> pkgs) {
        saveDock(pkgs, true);
    }

    private void saveDock(List<String> pkgs, boolean reloadApps) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        prefs.edit().putString("dock_pkgs", sb.toString()).apply();
        if (reloadApps) loadApps();
    }

    private void openTileMenu() {
        if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount()) {
            menuMode = MENU_DOCK;
            menuDockIdx = dockIdx;
            AppRepo.App app = dockAdapter.apps.get(dockIdx);
            int[] an = tileAnchor(280, 5, true);
            dockMenu.open(an[0], an[1], 280, app.label, Arrays.asList(
                    "▶  Open",
                    "📌  Remove from dock",
                    "↔  Reorder…",
                    "🔄  Replace app…",
                    "🗑  Uninstall"
            ), 0);
        } else if (zone == Zone.GRID && gridIdx < gridAdapter.getItemCount()) {
            menuMode = MENU_GRID;
            menuGridIdx = gridIdx;
            AppRepo.App app = gridAdapter.apps.get(gridIdx);
            int[] an = tileAnchor(280, 3, true);
            dockMenu.open(an[0], an[1], 280, app.label, Arrays.asList(
                    "▶  Open",
                    "📌  Add to dock",
                    "🗑  Uninstall"
            ), 0);
        }
    }

    private void openReorderMenu() {
        if (menuDockIdx < 0 || menuDockIdx >= dockAdapter.getItemCount()) return;
        menuMode = MENU_REORDER;
        AppRepo.App app = dockAdapter.apps.get(menuDockIdx);
        int[] an = tileAnchor(280, 5, true);
        dockMenu.open(an[0], an[1], 280, "Reorder • " + app.label, Arrays.asList(
                "←  Move Left",
                "→  Move Right",
                "⇤  Move to First",
                "⇥  Move to Last",
                "✓  Done"
        ), 0);
    }

    private void reorderMove(int delta) {
        reorderMoveTo(menuDockIdx + delta);
    }

    /** Moves the dock tile being reordered to another position in the dock as shown. */
    private void reorderMoveTo(int target) {
        int n = dockAdapter.apps.size();
        if (menuDockIdx < 0 || menuDockIdx >= n || target < 0 || target >= n || target == menuDockIdx) return;
        AppRepo.App a = dockAdapter.apps.remove(menuDockIdx);
        dockAdapter.apps.add(target, a);
        dockAdapter.notifyItemMoved(menuDockIdx, target);
        dockIdx = target;
        menuDockIdx = target;
        saveDockOrder();
        dockRv.post(() -> {
            applyFocus(false);
            openReorderMenu();
        });
    }

    /**
     * Saves the dock in the order shown. The saved list can also name apps that aren't on this TV
     * (config.xml's first-run list, an app Play Protect blocked), which the dock leaves out: they
     * keep their places, and the apps shown fill the other places in their new order. Positions in
     * the dock as shown are not positions in the saved list.
     */
    private void saveDockOrder() {
        List<String> pkgs = dockPrefs();
        List<String> shown = new ArrayList<>();
        List<Integer> places = new ArrayList<>();
        for (AppRepo.App a : dockAdapter.apps) {
            int at = pkgs.indexOf(a.pkg);
            if (at < 0) continue;
            shown.add(a.pkg);
            places.add(at);
        }
        Collections.sort(places);
        for (int i = 0; i < shown.size(); i++) pkgs.set(places.get(i), shown.get(i));
        saveDock(pkgs, false);
    }

    private void requestUninstall(AppRepo.App app) {
        if (app == null) return;
        if (getPackageName().equals(app.pkg)) {
            Ui.toast(this, "Cannot uninstall home launcher", Toast.LENGTH_SHORT);
            return;
        }
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(app.pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                Ui.toast(this, app.label + " is a system app and cannot be uninstalled", Toast.LENGTH_SHORT);
                return;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE);
            intent.setData(Uri.parse("package:" + app.pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Ui.toast(this, "Failed to uninstall " + app.label, Toast.LENGTH_SHORT);
        }
    }

    /** Where to pop the menu: under the focused tile, or above it when the screen edge is near. */
    private int[] tileAnchor(int widthDp, int rows, boolean hasTitle) {
        RecyclerView rv = zone == Zone.DOCK ? dockRv : gridRv;
        int idx = zone == Zone.DOCK ? dockIdx : gridIdx;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(idx);
        int pw = dp(widthDp);
        int headerH = hasTitle ? dp(37) : 0;
        int ph = Math.min(rows * dp(38) + headerH + dp(12), (int) (H * 0.65f));
        int x = side, y = dp(120);
        if (vh != null) {
            // where the tile sits at rest: a long press opens the menu while the tile is still sunk
            View item = vh.itemView;
            int[] loc = new int[2];
            rv.getLocationInWindow(loc);
            int left = loc[0] + Math.round(item.getLeft() + item.getTranslationX());
            int top = loc[1] + Math.round(item.getTop() + item.getTranslationY());
            x = left;
            y = top + item.getHeight() + dp(8);
            if (y + ph > H - dp(12)) y = top - ph - dp(8);
        }
        x = Math.max(dp(12), Math.min(x, W - pw - dp(12)));
        y = Math.max(dp(12), y);
        return new int[]{x, y};
    }

    private void onDockMenuChoice(int i) {
        List<String> pkgs = dockPrefs();
        if (menuMode == MENU_DOCK) {
            if (menuDockIdx < 0 || menuDockIdx >= dockAdapter.getItemCount()) {
                dockMenu.close();
                menuMode = MENU_NONE;
                return;
            }
            AppRepo.App app = dockAdapter.apps.get(menuDockIdx);
            switch (i) {
                case 0: // Open
                    dockMenu.close();
                    menuMode = MENU_NONE;
                    launch(AppRepo.launchIntent(getPackageManager(), app));
                    break;
                case 1: // Remove from dock
                    dockMenu.close();
                    menuMode = MENU_NONE;
                    if (pkgs.remove(app.pkg)) {
                        saveDock(pkgs);
                        dockIdx = Math.max(0, Math.min(menuDockIdx, dockAdapter.getItemCount() - 2));
                        applyFocus(true);
                        Ui.toast(this, app.label + " removed from dock", Toast.LENGTH_SHORT);
                    }
                    break;
                case 2: // Reorder
                    openReorderMenu();
                    break;
                case 3: // Replace app…
                    pickerApps = new ArrayList<>(gridAdapter.apps);
                    if (!pickerApps.isEmpty()) {
                        List<String> labels = new ArrayList<>();
                        for (AppRepo.App a : pickerApps) labels.add(a.label);
                        menuMode = MENU_PICKER;
                        int[] an = tileAnchor(300, labels.size(), true);
                        dockMenu.open(an[0], an[1], 300, "Replace " + app.label, labels, 0);
                    } else {
                        Ui.toast(this, "No apps available to replace", Toast.LENGTH_SHORT);
                        dockMenu.close();
                        menuMode = MENU_NONE;
                    }
                    break;
                case 4: // Uninstall
                    dockMenu.close();
                    menuMode = MENU_NONE;
                    requestUninstall(app);
                    break;
                default:
                    dockMenu.close();
                    menuMode = MENU_NONE;
                    break;
            }
        } else if (menuMode == MENU_GRID) {
            if (menuGridIdx < 0 || menuGridIdx >= gridAdapter.getItemCount()) {
                dockMenu.close();
                menuMode = MENU_NONE;
                return;
            }
            AppRepo.App app = gridAdapter.apps.get(menuGridIdx);
            switch (i) {
                case 0: // Open
                    dockMenu.close();
                    menuMode = MENU_NONE;
                    launch(AppRepo.launchIntent(getPackageManager(), app));
                    break;
                case 1: // Add to dock
                    dockMenu.close();
                    menuMode = MENU_NONE;
                    if (!pkgs.contains(app.pkg)) {
                        pkgs.add(app.pkg);
                        dockIdx = dockAdapter.getItemCount();   // the new last tile
                        zone = Zone.DOCK;
                        saveDock(pkgs);
                        Ui.toast(this, app.label + " added to dock", Toast.LENGTH_SHORT);
                    }
                    break;
                case 2: // Uninstall
                    dockMenu.close();
                    menuMode = MENU_NONE;
                    requestUninstall(app);
                    break;
                default:
                    dockMenu.close();
                    menuMode = MENU_NONE;
                    break;
            }
        } else if (menuMode == MENU_REORDER) {
            switch (i) {
                case 0: // Move Left
                    reorderMove(-1);
                    break;
                case 1: // Move Right
                    reorderMove(1);
                    break;
                case 2: // Move to First
                    reorderMoveTo(0);
                    break;
                case 3: // Move to Last
                    reorderMoveTo(dockAdapter.apps.size() - 1);
                    break;
                case 4: // Done
                default:
                    dockMenu.close();
                    menuMode = MENU_NONE;
                    break;
            }
        } else if (menuMode == MENU_PICKER) {
            dockMenu.close();
            int at = menuDockIdx >= 0 && menuDockIdx < dockAdapter.apps.size()
                    ? pkgs.indexOf(dockAdapter.apps.get(menuDockIdx).pkg) : -1;
            if (pickerApps != null && i >= 0 && i < pickerApps.size() && at >= 0) {
                pkgs.set(at, pickerApps.get(i).pkg);
                saveDock(pkgs);
            }
            menuMode = MENU_NONE;
        }
    }

    private void launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- focus rendering

    private void applyFocus(boolean animate) {
        releasePress();
        prefs.edit().putInt("zone", zone.ordinal()).putInt("top", topIdx)
                .putInt("dock", dockIdx).putInt("grid", gridIdx).apply();

        dockAdapter.setFocused(dockRv, zone == Zone.DOCK ? dockIdx : -1);
        if (zone == Zone.DOCK && dockIdx >= 0 && dockIdx < dockAdapter.getItemCount()) {
            dockRv.smoothScrollToPosition(dockIdx);
        }
        gridAdapter.setFocused(gridRv, zone == Zone.GRID ? gridIdx : -1);
        for (int i = 0; i < PILLS; i++) {
            float a = zone == Zone.TOP && i == topIdx ? 1f : 0.8f;
            pills[i].animate().alpha(a).setDuration(150).start();
        }

        // scroll: dock/top at rest, grid rows bring the grid up one row at a time
        float shift = 0;
        if (zone == Zone.GRID) {
            int row = gridIdx / COLS;
            shift = gridTop - dp(78) + Math.max(0, row - 1) * rowH;
        }
        if (animate) content.animate().translationY(-shift).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        else content.setTranslationY(-shift);

        // dock title + glow follow the dock selection
        if (dockAdapter.getItemCount() > 0 && dockIdx >= 0 && dockIdx < dockAdapter.apps.size() && dockIdx != lastTitleApp) {
            lastTitleApp = dockIdx;
            AppRepo.App a = dockAdapter.apps.get(dockIdx);
            if (animate) {
                titleChanging = true;
                dockTitle.animate().alpha(0f).setDuration(90).withEndAction(() -> {
                    dockTitle.setText(a.label);
                    titleChanging = false;
                    dockTitle.animate().alpha(titleAlpha()).setDuration(200).start();
                }).start();
            } else {
                dockTitle.setText(a.label);
                dockTitle.setAlpha(titleAlpha());
            }
            setGlow(a.glow, animate);
        } else if (!titleChanging) {
            dockTitle.animate().alpha(titleAlpha()).setDuration(200).start();
        }
        glow.animate().alpha(zone == Zone.GRID ? 0f : zone == Zone.TOP ? 0.6f : 1f).setDuration(300).start();

        RectF target = targetRect();
        if (target != null) moveHighlight(target, animate && hlPlaced);
    }

    private float titleAlpha() {
        return zone == Zone.TOP ? 0.55f : 1f;
    }

    private RectF targetRect() {
        if (zone == Zone.TOP) {
            View p = pills[topIdx];
            float x = pillRow.getLeft() + p.getLeft(), y = pillRow.getTop() + p.getTop();
            if (p.getWidth() == 0) return null;
            float o = dp(3);
            hlRadius = p.getHeight() / 2f + o;
            return new RectF(x - o, y - o, x + p.getWidth() + o, y + p.getHeight() + o);
        }
        RecyclerView rv = zone == Zone.DOCK ? dockRv : gridRv;
        int pos = zone == Zone.DOCK ? dockIdx : gridIdx;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh == null) return null;
        View card = vh.itemView.findViewById(R.id.card);
        float cx = rv.getLeft() + vh.itemView.getLeft() + card.getLeft() + card.getWidth() / 2f;
        float cy = rv.getTop() + vh.itemView.getTop() + card.getTop() + card.getHeight() / 2f;
        float hw = card.getWidth() * TileAdapter.FOCUS_SCALE / 2f + dp(5);
        float hh = card.getHeight() * TileAdapter.FOCUS_SCALE / 2f + dp(5);
        hlRadius = dp(15);
        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    private void moveHighlight(RectF to, boolean animate) {
        if (hlAnim != null) hlAnim.cancel();
        final RectF from = new RectF(hl);
        final float r0 = hlRadiusNow, r1 = hlRadius;
        if (!animate) {
            hl.set(to);
            placeHighlight(hl, r1);
            highlight.animate().alpha(1f).setDuration(200).start();
            hlPlaced = true;
            return;
        }
        hlAnim = ValueAnimator.ofFloat(0f, 1f);
        hlAnim.setDuration(340);
        hlAnim.setInterpolator(new OvershootInterpolator(1.15f));
        hlAnim.addUpdateListener(va -> {
            float t = (float) va.getAnimatedValue();
            hl.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t);
            placeHighlight(hl, r0 + (r1 - r0) * Math.min(1f, t));
        });
        hlAnim.start();
    }

    private void placeHighlight(RectF r, float radius) {
        hlRadiusNow = radius;
        highlight.set(r, radius);
    }

    private void setGlow(int color, boolean animate) {
        if (glowAnim != null) glowAnim.cancel();
        int from = glowColor == 0 ? (color & 0x00FFFFFF) : glowColor;
        glowColor = color;
        if (!animate) {
            paintGlow(color);
            return;
        }
        glowAnim = ValueAnimator.ofArgb(from, color);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(va -> paintGlow((int) va.getAnimatedValue()));
        glowAnim.start();
    }

    private void paintGlow(int c) {
        int rgb = c & 0x00FFFFFF;
        int a = (c >>> 24) * 0x5C / 0xFF;
        glowBg.setColors(new int[]{(a << 24) | rgb, ((a / 3) << 24) | rgb, rgb});
    }
}
