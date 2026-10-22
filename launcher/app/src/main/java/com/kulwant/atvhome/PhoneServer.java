package com.kulwant.atvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone control page: a tiny HTTP server on the home network (port 8080).
 *
 * A phone pairs once: it asks, the TV shows "Allow this phone to control the TV?" and someone
 * answers with the remote; the phone then gets a token it keeps. Every other call needs that token
 * (header X-Token or ?t=), so guests on the Wi-Fi can't use it. GET works for every action so Siri
 * Shortcuts can use plain URLs.
 */
final class PhoneServer {

    static final int PORT = 8080;
    private static final int MAX_BODY = 12 * 1024 * 1024;

    private final Context c;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    // file transfers can hold a thread for minutes, so there are enough left for the remote itself
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final SecureRandom rnd = new SecureRandom();
    private volatile ServerSocket server;
    private final KeyInjector keys;

    // pairing: a phone asks, the TV shows "Allow this phone…?" over whatever is on screen, and
    // someone in the room answers with the remote. Nothing to guess from the network. One request
    // at a time, at most one prompt every 10 s, and a phone that was refused waits a minute.
    private static final class Request {
        String id, ip;
        long until;
        int state;   // 0 waiting, 1 allowed, 2 refused
        String token;
    }
    private Request request;
    private long lastPromptAt;
    private final Map<String, Long> refusedUntil = new HashMap<>();
    private PairingPrompt prompt;

    // apps (with banners) are cached briefly: rendering them is the slow part
    private AppRepo.Result apps;
    private long appsAt;
    private final Map<String, byte[]> bannerPng = new HashMap<>();

    PhoneServer(Context c) {
        this.c = c.getApplicationContext();
        prefs = this.c.getSharedPreferences("phone", Context.MODE_PRIVATE);
        keys = new KeyInjector(this.c);
    }

    void start() {
        Thread t = new Thread(this::acceptLoop, "phone-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (true) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress((InetAddress) null, PORT));
                server = ss;
                while (!ss.isClosed()) {
                    Socket s = ss.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (Exception e) {
                if (pool.isShutdown()) return;
                try {
                    Thread.sleep(5000);   // port busy or network changed: try again
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static final class Req {
        String method, path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
        long length;
        String ip = "";
    }

    /** Files sent from the phone page: any size, streamed to the TV's Download folder (SharedFiles). */
    private static final String FILE_UPLOAD = "/api/files/upload";

    /** Routes anyone on the network may call (pairing, the page itself, PWA assets); their bodies are tiny. */
    private static final Set<String> OPEN = new HashSet<>(java.util.Arrays.asList(
            "/", "/index.html", "/manifest.json", "/manifest.webmanifest", "/sw.js",
            "/icon.png", "/icon-192.png", "/icon-512.png", "/favicon.ico",
            "/api/pair", "/api/pair/status"));
    private static final int MAX_OPEN_BODY = 4096;

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            Req r = read(in);
            if (r == null) return;
            r.ip = sock.getInetAddress().getHostAddress();
            if (FILE_UPLOAD.equals(r.path)) {
                if (!hostIsIp(r)) send(out, 403, "text/plain", "use the TV's IP address".getBytes(StandardCharsets.UTF_8), null);
                else if (!authorised(r)) json(out, 401, err("not paired"));
                else receiveFile(r, in, out);
                return;
            }
            // decide whether this caller may send a body before allocating it: only paired phones
            // get the large limit (photo uploads), so nobody else can make the TV run out of memory
            int max = OPEN.contains(r.path) ? MAX_OPEN_BODY : authorised(r) ? MAX_BODY : 0;
            if (r.length > max) {
                json(out, OPEN.contains(r.path) || authorised(r) ? 413 : 401,
                        err(authorised(r) || OPEN.contains(r.path) ? "too large" : "not paired"));
                return;
            }
            if (!readBody(in, r)) return;
            route(r, out);
        } catch (Exception ignored) {
        }
    }

    private static Req read(InputStream in) throws Exception {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        Req r = new Req();
        r.method = parts[0];
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) {
            for (String kv : target.substring(q + 1).split("&")) {
                int e = kv.indexOf('=');
                if (e > 0) r.query.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
                else if (!kv.isEmpty()) r.query.put(dec(kv), "");
            }
        }
        String h;
        while ((h = readLine(in)) != null && !h.isEmpty()) {
            int colon = h.indexOf(':');
            if (colon > 0) r.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.US), h.substring(colon + 1).trim());
        }
        try {
            r.length = Long.parseLong(r.headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException e) {
            return null;
        }
        return r.length < 0 ? null : r;
    }

    private static boolean readBody(InputStream in, Req r) throws Exception {
        r.body = new byte[(int) r.length];   // at most MAX_BODY: checked by handle()
        int off = 0;
        while (off < r.body.length) {
            int n = in.read(r.body, off, r.body.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 8192) return null;
        }
        return b < 0 && sb.length() == 0 ? null : sb.toString();
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String extra) throws Exception {
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found"
                : code == 413 ? "Payload Too Large" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: " + (extra != null ? extra : "no-store") + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void json(OutputStream out, int code, JSONObject o) throws Exception {
        send(out, code, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("ok", true);
    }

    private static JSONObject err(String msg) throws Exception {
        return new JSONObject().put("ok", false).put("error", msg);
    }

    // ---------------------------------------------------------------- routes

    /**
     * Only answer requests addressed to an IP, never to a hostname: stops a web page on some other
     * site from reaching this server through the phone's browser (DNS rebinding).
     */
    private static boolean hostIsIp(Req r) {
        return r.headers.getOrDefault("host", "").matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?");
    }

    private void route(Req r, OutputStream out) throws Exception {
        if (!hostIsIp(r)) {
            send(out, 403, "text/plain", "use the TV's IP address".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        switch (r.path) {
            case "/":
            case "/index.html":
                send(out, 200, "text/html; charset=utf-8", asset("phone.html"), null);
                return;
            case "/manifest.json":
            case "/manifest.webmanifest":
                send(out, 200, "application/manifest+json; charset=utf-8", asset("manifest.json"), "max-age=3600");
                return;
            case "/sw.js":
                send(out, 200, "application/javascript; charset=utf-8", asset("sw.js"), "no-cache");
                return;
            case "/icon.png":
            case "/favicon.ico":
                send(out, 200, "image/png", icon(180), "max-age=86400");
                return;
            case "/icon-192.png":
                send(out, 200, "image/png", icon(192), "max-age=86400");
                return;
            case "/icon-512.png":
                send(out, 200, "image/png", icon(512), "max-age=86400");
                return;
            case "/api/pair":
                json(out, 200, startPairing(r));
                return;
            case "/api/pair/status":
                json(out, 200, pairStatus(param(r, "id"), r.ip));
                return;
        }
        if (!authorised(r)) {
            json(out, 401, err("not paired"));
            return;
        }
        switch (r.path) {
            case "/api/unpair":
                unpair(r);
                json(out, 200, ok());
                return;
            case "/api/state":
                json(out, 200, state());
                return;
            case "/api/open":
                json(out, 200, open(param(r, "app")));
                return;
            case "/api/home":
                runOnMain(() -> c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(c.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                json(out, 200, ok());
                return;
            case "/api/input":
                json(out, 200, input(param(r, "name")));
                return;
            case "/api/remote":
                json(out, 200, remoteStatus());
                return;
            case "/api/key":
                try {
                    int n = 1;
                    try {
                        n = Integer.parseInt(param(r, "n"));
                    } catch (NumberFormatException ignored) {
                    }
                    keys.press(param(r, "k"), n);
                    json(out, 200, ok());
                } catch (Exception e) {
                    json(out, 200, err(e.getMessage()).put("remote", remoteStatus()));
                }
                return;
            case "/api/text":
                try {
                    String text = r.query.containsKey("text") ? r.query.get("text") : param(r, "text");
                    keys.type(text);
                    json(out, 200, ok());
                } catch (Exception e) {
                    json(out, 200, err(e.getMessage()).put("remote", remoteStatus()));
                }
                return;
            case "/api/youtube":
                json(out, 200, youtube(param(r, "url")));
                return;
            case "/api/banner":
                byte[] png = banner(param(r, "pkg"));
                if (png == null) send(out, 404, "text/plain", new byte[0], null);
                else send(out, 200, "image/png", png, "max-age=86400");
                return;
            case "/api/photo":
                if ("POST".equals(r.method)) {
                    boolean isUnsplash = "1".equals(param(r, "unsplash"));
                    File f = Wallpapers.add(c, r.body, isUnsplash);
                    json(out, 200, f == null ? err("not an image, or the album is full")
                            : ok().put("id", f.getName().replace(".jpg", "")));
                } else {
                    photo(r, out);
                }
                return;
            case "/api/photo/replace":
                File rf = Wallpapers.replace(c, param(r, "id"), r.body);
                json(out, 200, rf == null ? err("photo not found, or not an image") : ok());
                return;
            case "/api/photo/original":
                json(out, 200, Wallpapers.setOriginal(c, param(r, "id"), r.body) ? ok() : err("photo not found"));
                return;
            case "/api/photo/show":
                Wallpapers.show(c, param(r, "id"));
                json(out, 200, ok());
                return;
            case "/api/photo/delete":
                json(out, 200, Wallpapers.delete(c, param(r, "id")) ? ok() : err("not found"));
                return;
            case "/api/wallpaper/mode":
                if ("POST".equals(r.method)) {
                    String mode = param(r, "mode");
                    if (Wallpapers.MODE_LOCAL.equals(mode) || Wallpapers.MODE_UNSPLASH.equals(mode) || Wallpapers.MODE_BOTH.equals(mode)) {
                        Wallpapers.setMode(c, mode);
                        json(out, 200, ok().put("mode", mode));
                    } else {
                        json(out, 200, err("invalid mode: " + mode));
                    }
                } else {
                    json(out, 200, ok().put("mode", Wallpapers.getMode(c)));
                }
                return;
            case "/api/photo/download":
                if ("POST".equals(r.method)) {
                    String url = param(r, "url");
                    if (url == null || url.isEmpty()) {
                        json(out, 200, err("missing url"));
                        return;
                    }
                    try {
                        File f = Wallpapers.downloadAndAdd(c, url);
                        json(out, 200, f == null ? err("album full or invalid image")
                                : ok().put("id", f.getName().replace(".jpg", "")));
                    } catch (Exception ex) {
                        json(out, 200, err("download failed: " + ex.getMessage()));
                    }
                } else {
                    json(out, 404, err("POST required"));
                }
                return;
            case "/api/files":
                json(out, 200, SharedFiles.canAccess(c)
                        ? ok().put("folder", "Download").put("free", SharedFiles.dir().getUsableSpace())
                                .put("files", SharedFiles.list())
                        : err(SharedFiles.NO_ACCESS));
                return;
            case "/api/files/get":
                sendFile(out, SharedFiles.canAccess(c) ? SharedFiles.get(param(r, "name")) : null);
                return;
            case "/api/files/delete":
                if (!"POST".equals(r.method)) {
                    json(out, 404, err("POST required"));
                    return;
                }
                File gone = SharedFiles.canAccess(c) ? SharedFiles.get(param(r, "name")) : null;
                if (gone != null && gone.delete()) {
                    SharedFiles.scan(c, gone);
                    json(out, 200, ok());
                } else {
                    json(out, 200, err("not found"));
                }
                return;
            case "/api/files/open":
                if (!"POST".equals(r.method)) {
                    json(out, 404, err("POST required"));
                    return;
                }
                File toOpen = SharedFiles.canAccess(c) ? SharedFiles.get(param(r, "name")) : null;
                String why = toOpen == null ? "not found" : SharedFiles.open(c, toOpen);
                json(out, 200, why == null ? ok() : err(why));
                return;
            default:
                send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), null);
        }
    }

    // ---------------------------------------------------------------- file sharing

    /**
     * A file sent from the phone page (?name=), any size: streamed into the Download folder under
     * a hidden part name, which becomes the real name only once every byte has arrived.
     */
    private void receiveFile(Req r, InputStream in, OutputStream out) throws Exception {
        if (!"POST".equals(r.method) || r.length <= 0) {
            json(out, 400, err("send the file as the POST body, with its size"));
            return;
        }
        if (!SharedFiles.canAccess(c)) {
            json(out, 200, err(SharedFiles.NO_ACCESS));
            return;
        }
        File dir = SharedFiles.dir();
        dir.mkdirs();
        long room = dir.getUsableSpace() - 100L * 1024 * 1024;   // leave the TV room to work
        if (r.length > room) {
            json(out, 200, err("not enough space on the TV: " + Math.max(0, room) / (1024 * 1024) + " MB free"));
            return;
        }
        if ("100-continue".equalsIgnoreCase(r.headers.getOrDefault("expect", ""))) {   // curl, for big files
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        File part = new File(dir, ".upload-" + randomHex(8) + ".part");
        try (OutputStream fo = new java.io.FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long left = r.length;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new java.io.IOException("upload cut off");
                fo.write(buf, 0, n);
                left -= n;
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        }
        File target;
        synchronized (SharedFiles.class) {   // two files with the same name at once get different names
            target = SharedFiles.freeTarget(param(r, "name"));
            if (!part.renameTo(target)) {
                part.delete();
                json(out, 200, err("couldn't save the file on the TV"));
                return;
            }
        }
        SharedFiles.scan(c, target);
        String name = target.getName();
        runOnMain(() -> Ui.toast(c, "Received " + name, Toast.LENGTH_SHORT));
        json(out, 200, ok().put("name", name).put("size", target.length()));
    }

    /** Streams a file to the phone or computer, which saves it under its own name. */
    private static void sendFile(OutputStream out, File f) throws Exception {
        if (f == null) {
            send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        String name = f.getName();
        String plain = name.replaceAll("[^\\x20-\\x7e]|[\"\\\\]", "_");   // for browsers without filename*
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + SharedFiles.mime(name) + "\r\n"
                + "Content-Length: " + f.length() + "\r\n"
                + "Content-Disposition: attachment; filename=\"" + plain + "\"; filename*=UTF-8''"
                + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20") + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.flush();
    }

    private static String param(Req r, String name) {
        String v = r.query.get(name);
        if (v == null && r.body.length > 0 && r.body.length < 4096) {
            try {
                JSONObject o = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
                v = o.optString(name, null);
            } catch (Exception ignored) {
            }
        }
        return v == null ? "" : v.trim();
    }

    // ---------------------------------------------------------------- pairing

    private boolean authorised(Req r) {
        String t = r.headers.get("x-token");
        if (t == null) t = r.query.get("t");
        return t != null && !t.isEmpty() && prefs.getStringSet("tokens", new HashSet<>()).contains(t);
    }

    /** Revokes the calling phone's token, so "Unpair this phone" really cuts it off. */
    private synchronized void unpair(Req r) {
        String t = r.headers.get("x-token");
        if (t == null) t = r.query.get("t");
        Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
        if (tokens.remove(t)) prefs.edit().putStringSet("tokens", tokens).apply();
    }

    private synchronized JSONObject startPairing(Req r) throws Exception {
        long now = System.currentTimeMillis();
        Long refused = refusedUntil.get(r.ip);
        if (refused != null && now < refused) return err("Refused on the TV. Try again in a minute.");
        if (request != null && request.state == 0 && now < request.until) {
            if (request.ip.equals(r.ip)) return ok().put("id", request.id);   // same phone, same prompt
            return err("Another phone is asking right now. Try again in a minute.");
        }
        if (now - lastPromptAt < 10_000) return err("Wait a few seconds and try again.");
        if (!PairingPrompt.canShow(c)) {
            return err("The TV can't show the pairing prompt yet: allow \"Display over other apps\" for the launcher (see the README).");
        }
        Request q = new Request();
        q.id = randomHex(16);
        q.ip = r.ip;
        q.until = now + 60_000;
        request = q;
        lastPromptAt = now;
        String device = deviceName(r.headers.getOrDefault("user-agent", "")) + "  ·  " + r.ip;
        runOnMain(() -> {
            if (prompt == null) prompt = new PairingPrompt(c);
            prompt.show(device, allowed -> answer(q, allowed));
            // nobody answered in time: same as "Don't allow"
            main.postDelayed(() -> {
                if (q.state == 0 && prompt.isShowing()) prompt.dismiss(false);
            }, 60_000);
        });
        return ok().put("id", q.id);
    }

    private synchronized void answer(Request q, boolean allowed) {
        if (q.state != 0) return;
        if (!allowed) {
            q.state = 2;
            if (refusedUntil.size() > 256) refusedUntil.values().removeIf(t -> t < System.currentTimeMillis());
            refusedUntil.put(q.ip, System.currentTimeMillis() + 60_000);
            return;
        }
        q.token = randomHex(16);
        Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
        tokens.add(q.token);
        prefs.edit().putStringSet("tokens", tokens).apply();
        q.state = 1;
        Ui.toast(c, "Phone paired", Toast.LENGTH_SHORT);
    }

    /** The phone polls this with the id it got; only that phone (same IP) can collect the token. */
    private synchronized JSONObject pairStatus(String id, String ip) throws Exception {
        Request q = request;
        if (q == null || !q.id.equals(id) || !q.ip.equals(ip)) return err("No pairing request. Start again.");
        if (q.state == 1) {
            request = null;   // the token is handed out once
            return ok().put("token", q.token);
        }
        if (q.state == 2) return err("Refused on the TV.");
        if (System.currentTimeMillis() > q.until + 5_000) return err("Nobody answered on the TV. Start again.");
        return ok().put("waiting", true);
    }

    private static String deviceName(String ua) {
        if (ua.contains("iPhone")) return "iPhone";
        if (ua.contains("iPad")) return "iPad";
        if (ua.contains("Android")) return "Android phone";
        if (ua.contains("Macintosh")) return "Mac";
        if (ua.contains("Windows")) return "Windows PC";
        return "A device";
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    // ---------------------------------------------------------------- remote (trackpad, keyboard)

    /** Ready, or starts connecting in the background (the first time the TV asks to allow it). Never waits. */
    private JSONObject remoteStatus() throws Exception {
        if (keys.isConnected()) return ok().put("ready", true).put("layout", keys.describe());
        keys.connectInBackground();
        String e = keys.lastError();
        return ok().put("ready", false)
                .put("message", e != null ? e : "Connecting… if the TV asks \"Allow USB debugging?\", tick Always allow and press OK");
    }

    // ---------------------------------------------------------------- actions

    private synchronized AppRepo.Result apps() {
        if (apps == null || System.currentTimeMillis() - appsAt > 60_000) {
            apps = AppRepo.load(c);
            appsAt = System.currentTimeMillis();
            bannerPng.clear();
        }
        return apps;
    }

    private JSONObject state() throws Exception {
        JSONObject o = ok();
        AppRepo.Result r = apps();
        JSONArray a = new JSONArray();
        for (AppRepo.App app : r.dock) a.put(new JSONObject().put("pkg", app.pkg).put("label", app.label).put("dock", true));
        for (AppRepo.App app : r.grid) a.put(new JSONObject().put("pkg", app.pkg).put("label", app.label));
        o.put("apps", a);
        JSONArray in = new JSONArray();
        for (InputsMenu.Item it : InputsMenu.list(c)) in.put(new JSONObject().put("label", it.label).put("connected", it.connected));
        o.put("inputs", in);
        JSONArray ph = new JSONArray();
        for (File f : Wallpapers.userPhotos(c)) {
            String name = f.getName().replace(".jpg", "");
            boolean isUnsplash = name.startsWith("unsplash_");
            String type = isUnsplash ? "unsplash" : "local";
            ph.put(new JSONObject().put("id", name).put("type", type));
        }
        o.put("photos", ph);
        o.put("maxPhotos", Wallpapers.MAX_PHOTOS);
        o.put("wallpaperMode", Wallpapers.getMode(c));
        return o;
    }

    /** Matches a package name, or any part of an app's name ("netflix", "prime", "live tv"). */
    private AppRepo.App findApp(String q) {
        String needle = q.toLowerCase(Locale.US).replace(" ", "");
        if (needle.isEmpty()) return null;
        AppRepo.Result r = apps();
        AppRepo.App best = null;
        for (List<AppRepo.App> list : java.util.Arrays.asList(r.dock, r.grid)) {
            for (AppRepo.App a : list) {
                if (a.pkg.equalsIgnoreCase(q)) return a;
                String label = a.label.toLowerCase(Locale.US).replace(" ", "");
                if (label.equals(needle)) return a;
                if (best == null && (label.contains(needle) || a.pkg.toLowerCase(Locale.US).contains(needle))) best = a;
            }
        }
        return best;
    }

    private JSONObject open(String q) throws Exception {
        AppRepo.App a = findApp(q);
        if (a == null) return err("no app called " + q);
        Intent i = AppRepo.launchIntent(c.getPackageManager(), a);
        runOnMain(() -> c.startActivity(i));
        return ok().put("opened", a.label);
    }

    private JSONObject input(String q) throws Exception {
        String needle = q.toLowerCase(Locale.US).replace(" ", "");
        for (InputsMenu.Item it : InputsMenu.list(c)) {
            if (it.label.toLowerCase(Locale.US).replace(" ", "").equals(needle) && it.intent != null) {
                runOnMain(() -> c.startActivity(it.intent));
                return ok().put("opened", it.label);
            }
        }
        return err("no input called " + q);
    }

    /** Plays a YouTube link (watch, youtu.be, shorts) in the TV's YouTube app. */
    private JSONObject youtube(String url) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})").matcher(url);
        if (!m.find()) return err("not a YouTube link");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + m.group(1)))
                .setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        runOnMain(() -> c.startActivity(i));
        return ok();
    }

    private void photo(Req r, OutputStream out) throws Exception {
        String id = param(r, "id");
        File f = "1".equals(param(r, "orig")) ? Wallpapers.original(c, id) : Wallpapers.photo(c, id);
        if (f == null) {
            send(out, 404, "text/plain", new byte[0], null);
            return;
        }
        byte[] data;
        try (InputStream in = new FileInputStream(f)) {
            data = readAll(in);
        }
        // ids stay the same when a photo is re-framed, so don't let the phone cache it
        send(out, 200, "image/jpeg", data, null);
    }

    private synchronized byte[] banner(String pkg) {
        byte[] cached = bannerPng.get(pkg);
        if (cached != null) return cached;
        AppRepo.Result r = apps();
        for (List<AppRepo.App> list : java.util.Arrays.asList(r.dock, r.grid)) {
            for (AppRepo.App a : list) {
                if (a.pkg.equals(pkg) && a.banner != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    a.banner.compress(Bitmap.CompressFormat.PNG, 100, bo);
                    bannerPng.put(pkg, bo.toByteArray());
                    return bo.toByteArray();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private void runOnMain(Runnable r) {
        main.post(() -> {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        });
    }

    private byte[] asset(String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static final Map<Integer, byte[]> ICON_CACHE = new HashMap<>();

    /** Home-screen & PWA app icon: modern TV silhouette with Apple red status indicator on dark gradient. */
    private static synchronized byte[] icon(int s) {
        byte[] cached = ICON_CACHE.get(s);
        if (cached != null) return cached;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        float sc = s / 180f;

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, 0, s, 0xFF1C1C22, 0xFF0A0A0D, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, s, s, bg);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(10 * sc);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        cv.drawRoundRect(new RectF(34 * sc, 44 * sc, 146 * sc, 118 * sc), 14 * sc, 14 * sc, p);

        // TV Stand
        cv.drawLine(70 * sc, 140 * sc, 110 * sc, 140 * sc, p);
        p.setStrokeWidth(8 * sc);
        cv.drawLine(90 * sc, 118 * sc, 90 * sc, 140 * sc, p);

        // Apple Red accent status indicator dot
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFF3B30);
        cv.drawCircle(90 * sc, 81 * sc, 12 * sc, p);

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        byte[] res = bo.toByteArray();
        ICON_CACHE.put(s, res);
        return res;
    }
}
