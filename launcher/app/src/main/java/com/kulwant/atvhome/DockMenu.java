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

import java.util.ArrayList;
import java.util.List;

/**
 * Small glass menu for editing the dock from the remote: a short action list for the focused
 * tile, or a scrollable picker of apps. Same look and key model as InputsMenu.
 */
final class DockMenu {

    final FrameLayout panel;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final Context c;
    private final List<TextView> rows = new ArrayList<>();
    private int sel;
    private int gen;   // guards the close fade-out against a menu reopened in the meantime
    private boolean open;   // false as soon as close() starts: keys go back to the tiles during the fade
    private long pressedAt;

    DockMenu(Context c) {
        this.c = c;
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
        panel.addView(scroll);
    }

    boolean isOpen() {
        return open;
    }

    interface OnChoiceListener {
        void onChoice(int choice);
    }

    private OnChoiceListener listener;

    void setOnChoiceListener(OnChoiceListener l) {
        this.listener = l;
    }

    void open(int leftPx, int topPx, int widthDp, List<String> labels) {
        open(leftPx, topPx, widthDp, null, labels, 0);
    }

    void open(int leftPx, int topPx, int widthDp, String title, List<String> labels, int initialSel) {
        gen++;
        open = true;
        panel.animate().cancel();
        list.removeAllViews();
        rows.clear();

        int headerH = 0;
        if (title != null && !title.isEmpty()) {
            TextView header = Ui.text(c, 600, 13, 0x99FFFFFF);
            header.setText(title);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(Ui.dp(c, 14), Ui.dp(c, 6), Ui.dp(c, 14), Ui.dp(c, 2));
            header.setSingleLine(true);
            header.setEllipsize(android.text.TextUtils.TruncateAt.END);
            list.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 26)));

            View divider = new View(c);
            divider.setBackgroundColor(0x1FFFFFFF);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 1));
            dlp.setMargins(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 6));
            list.addView(divider, dlp);
            headerH = Ui.dp(c, 37);
        }

        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            String label = labels.get(i);
            TextView t = Ui.text(c, 400, 14, 0xFFFFFFFF);
            t.setText(label);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setOnClickListener(v -> {
                sel = idx;
                paint();
                if (listener != null) listener.onChoice(idx);
            });
            list.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 38)));
            rows.add(t);
        }
        int maxH = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.65f);
        int h = Math.min(labels.size() * Ui.dp(c, 38) + headerH + Ui.dp(c, 12), maxH);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(c, widthDp), h);
        lp.leftMargin = leftPx;
        lp.topMargin = topPx;
        panel.setLayoutParams(lp);
        sel = Math.max(0, Math.min(initialSel, labels.size() - 1));
        scroll.scrollTo(0, 0);
        paint();
        panel.setVisibility(View.VISIBLE);
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
        final int g = gen;
        panel.animate().alpha(0f).setStartDelay(pressShowing()).setDuration(110)
                .withEndAction(() -> {
                    if (g == gen) panel.setVisibility(View.GONE);
                }).start();
    }

    void move(int d) {
        int n = sel + d;
        if (n < 0 || n >= rows.size()) return;
        sel = n;
        paint();
        TextView row = rows.get(sel);
        scroll.post(() -> {
            int y = row.getTop(), bottom = y + row.getHeight();
            if (y < scroll.getScrollY()) scroll.smoothScrollTo(0, y);
            else if (bottom > scroll.getScrollY() + scroll.getHeight())
                scroll.smoothScrollTo(0, bottom - scroll.getHeight());
        });
    }

    int selectedIndex() {
        return sel;
    }

    private void paint() {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setBackground(i == sel
                    ? Ui.rounded(0x2EFFFFFF, Ui.dp(c, 11), Ui.dp(c, 1), 0x40FFFFFF) : null);
        }
    }
}
