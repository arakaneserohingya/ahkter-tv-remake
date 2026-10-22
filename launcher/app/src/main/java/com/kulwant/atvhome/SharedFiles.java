package com.kulwant.atvhome;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * The TV's Download folder, shared with paired phones and computers on the phone page (Files tab):
 * files sent from them are saved here, and any file here can be saved to the phone, opened on the
 * TV or deleted. Needs "All files access" (Android 11+) or the storage permission (Android 9 and
 * 10), which build_and_install.sh grants.
 */
final class SharedFiles {

    static final String AUTHORITY = "com.kulwant.atvhome.files";   // see AndroidManifest.xml
    static final String NO_ACCESS = "File sharing is off on the TV: run build_and_install.sh again,"
            + " or allow All files access for Home in the TV's settings";

    private SharedFiles() {}

    static File dir() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    static boolean canAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** The files to show, newest first: plain files only, not hidden ones or uploads still arriving. */
    static JSONArray list() throws Exception {
        File[] fs = dir().listFiles(f -> f.isFile() && !f.getName().startsWith("."));
        JSONArray out = new JSONArray();
        if (fs == null) return out;
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : fs) {
            out.put(new JSONObject().put("name", f.getName()).put("size", f.length())
                    .put("modified", f.lastModified()).put("type", mime(f.getName())));
        }
        return out;
    }

    /** A file in the folder by its name; null for a missing file or anything that isn't a plain name. */
    static File get(String name) {
        if (!isPlainName(name)) return null;
        File f = new File(dir(), name);
        return f.isFile() ? f : null;
    }

    /** Where a received file goes: its own name made safe, numbered if a file already has it. */
    static File freeTarget(String requested) {
        String name = safeName(requested);
        File f = new File(dir(), name);
        for (int n = 2; f.exists(); n++) f = new File(dir(), numbered(name, n));
        return f;
    }

    /** A name inside the folder: no folders, not hidden, no control characters. */
    static boolean isPlainName(String name) {
        return name != null && !name.isEmpty() && !name.startsWith(".")
                && !name.contains("/") && !name.contains("\\") && !name.matches(".*[\\x00-\\x1f].*");
    }

    /** A sent file's name made plain: no folders, no characters storage rejects, not hidden, 120 at most. */
    static String safeName(String raw) {
        String s = raw == null ? "" : raw;
        s = s.substring(Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\')) + 1);
        s = s.replaceAll("[\\x00-\\x1f\\x7f:*?\"<>|]", "").trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 120) {   // keep the extension: it says what opens the file
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 10 ? s.substring(dot) : "";
            s = s.substring(0, 120 - ext.length()) + ext;
        }
        return s.isEmpty() ? "file" : s;
    }

    /** "photo.jpg", 2 -> "photo (2).jpg" */
    static String numbered(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + " (" + n + ")";
        return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
    }

    static String mime(String name) {
        String ext = extension(name);
        if (ext.equals("apk")) return "application/vnd.android.package-archive";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /** Tells the media library about a new or deleted file, so gallery and video apps see it. */
    static void scan(Context c, File f) {
        MediaScannerConnection.scanFile(c, new String[]{f.getPath()}, null, null);
    }

    /** Android TV's catch-all for intents no real app handles: it only says the action isn't supported. */
    private static final String TV_STUBS = "com.android.tv.frameworkpackagestubs";

    /** Opens the file in a TV app for its type (an APK opens the installer); null, or why not. */
    static String open(Context c, File f) {
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(c, AUTHORITY, f), mime(f.getName()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean realApp = false;
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(i, 0)) {
            if (!TV_STUBS.equals(ri.activityInfo.packageName)) realApp = true;
        }
        if (!realApp) {
            String ext = extension(f.getName());
            return "No app on the TV opens " + (ext.isEmpty() ? "this file" : "." + ext + " files");
        }
        try {
            c.startActivity(i);
            return null;
        } catch (RuntimeException e) {
            return "Couldn't open it on the TV";
        }
    }
}
