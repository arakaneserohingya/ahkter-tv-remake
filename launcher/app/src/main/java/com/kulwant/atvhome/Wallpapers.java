package com.kulwant.atvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The home-screen photos. Supports both local user uploads and Unsplash wallpapers,
 * with options to cycle through local only, Unsplash only, or both.
 */
final class Wallpapers {

    interface Listener {
        /** Called on the main thread; show is a photo to put on screen now, or null after a delete. */
        void onWallpapersChanged(File added);
    }

    public static final String MODE_BOTH = "both";
    public static final String MODE_LOCAL = "local";
    public static final String MODE_UNSPLASH = "unsplash";

    static final int MAX_PHOTOS = 60;
    private static volatile Listener listener;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Wallpapers() {}

    static void setListener(Listener l) {
        listener = l;
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "wallpapers");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static String getMode(Context c) {
        SharedPreferences p = c.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        String def = "both";
        try {
            int id = c.getResources().getIdentifier("wallpaper_source", "string", c.getPackageName());
            if (id != 0) def = c.getString(id);
        } catch (Exception ignored) {}
        return p.getString("mode", def);
    }

    static void setMode(Context c, String mode) {
        if (mode == null) mode = MODE_BOTH;
        SharedPreferences p = c.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        p.edit().putString("mode", mode).apply();
        notifyChanged(null);
    }

    /** All user photos (oldest first). */
    static synchronized List<File> userPhotos(Context c) {
        seed(c);
        File[] fs = dir(c).listFiles((d, n) -> n.matches("((local|unsplash)_)?\\d+\\.jpg"));
        List<File> out = new ArrayList<>();
        if (fs == null) return out;
        Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        out.addAll(Arrays.asList(fs));
        return out;
    }

    /** Returns photos according to the active mode (local, unsplash, or both). */
    static synchronized List<File> activePhotos(Context c) {
        List<File> all = userPhotos(c);
        String mode = getMode(c);
        if (MODE_BOTH.equals(mode)) return all;

        List<File> filtered = new ArrayList<>();
        for (File f : all) {
            boolean isUnsplash = f.getName().startsWith("unsplash_");
            if (MODE_UNSPLASH.equals(mode) && isUnsplash) {
                filtered.add(f);
            } else if (MODE_LOCAL.equals(mode) && !isUnsplash) {
                filtered.add(f);
            }
        }
        // If no photos match the chosen filter yet, fallback to all so screen is never blank
        return filtered.isEmpty() ? all : filtered;
    }

    private static void seed(Context c) {
        SharedPreferences p = c.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        if (p.getBoolean("seeded", false)) return;
        long base = System.currentTimeMillis();
        for (int i = 1; i <= 20; i++) {
            int res = c.getResources().getIdentifier("wallpaper_" + i, "drawable", c.getPackageName());
            if (res == 0) continue;
            copy(c, res, new File(dir(c), (base + i) + ".jpg"));
            int orig = c.getResources().getIdentifier("wallpaper_" + i + "_o", "drawable", c.getPackageName());
            if (orig != 0) copy(c, orig, new File(dir(c), (base + i) + "_o.jpg"));
        }
        p.edit().putBoolean("seeded", true).apply();
    }

    private static void copy(Context c, int res, File to) {
        try (InputStream in = c.getResources().openRawResource(res);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ignored) {
        }
    }

    static File original(Context c, String id) {
        File f = photo(c, id);
        if (f == null) return null;
        String baseName = f.getName().replace(".jpg", "");
        File o = new File(dir(c), baseName + "_o.jpg");
        return o.isFile() ? o : f;
    }

    static File photo(Context c, String id) {
        if (id == null) return null;
        File f = new File(dir(c), id + ".jpg");
        if (f.isFile()) return f;
        f = new File(dir(c), "local_" + id + ".jpg");
        if (f.isFile()) return f;
        f = new File(dir(c), "unsplash_" + id + ".jpg");
        if (f.isFile()) return f;
        return null;
    }

    /** Saves a local JPEG. */
    static synchronized File add(Context c, byte[] jpeg) throws Exception {
        return add(c, jpeg, false);
    }

    /** Saves a JPEG with optional Unsplash tag. */
    static synchronized File add(Context c, byte[] jpeg, boolean isUnsplash) throws Exception {
        if (!isImage(jpeg) || userPhotos(c).size() >= MAX_PHOTOS) return null;
        String prefix = isUnsplash ? "unsplash_" : "local_";
        File f = new File(dir(c), prefix + System.currentTimeMillis() + ".jpg");
        write(f, jpeg);
        notifyChanged(f);
        return f;
    }

    /** Downloads an image from an HTTP/HTTPS URL and adds it as an Unsplash wallpaper. */
    static synchronized File downloadAndAdd(Context c, String imageUrl) throws Exception {
        URL u = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "AkhterRubyTV/1.0");
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            byte[] bytes = out.toByteArray();
            return add(c, bytes, true);
        }
    }

    /** New framing for an existing slot. */
    static synchronized File replace(Context c, String id, byte[] jpeg) throws Exception {
        File f = photo(c, id);
        if (f == null || !isImage(jpeg)) return null;
        write(f, jpeg);
        notifyChanged(f);
        return f;
    }

    static synchronized boolean setOriginal(Context c, String id, byte[] jpeg) throws Exception {
        File f = photo(c, id);
        if (f == null || !isImage(jpeg)) return false;
        String baseName = f.getName().replace(".jpg", "");
        write(new File(dir(c), baseName + "_o.jpg"), jpeg);
        return true;
    }

    static synchronized boolean delete(Context c, String id) {
        File f = photo(c, id);
        if (f == null || !f.delete()) return false;
        String baseName = f.getName().replace(".jpg", "");
        new File(dir(c), baseName + "_o.jpg").delete();
        notifyChanged(null);
        return true;
    }

    /** Puts a photo on screen now. */
    static void show(Context c, String id) {
        File f = photo(c, id);
        if (f != null) notifyChanged(f);
    }

    private static boolean isImage(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        return o.outWidth > 0 && o.outHeight > 0;
    }

    private static void write(File f, byte[] data) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed");
    }

    private static void notifyChanged(File added) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onWallpapersChanged(added);
        });
    }
}
