package com.kulwant.atvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages Islamic prayer times (Salat) and Hijri date.
 * Automatically discovers the TV's location (via system location or IP geolocation)
 * on startup and computes precise prayer times for that location.
 * Formats in English (Fajr, Dhuhr, Asr, Maghrib, Isha) and displays only the active/upcoming prayer.
 */
final class PrayerRepo {
    private static final String TAG = "PrayerRepo";
    private static final String PREFS_NAME = "prayer_times";
    private static final int NOW_WINDOW_MINUTES = 30; // Duration prayer is "now salat"
    private static final long RETRY_INTERVAL_MS = 15 * 60_000L; // 15-minute retry on failure
    private static final long REFRESH_INTERVAL_MS = 24 * 3600_000L; // Daily refresh

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean fetching = new AtomicBoolean(false);

    private static final String[] HIJRI_MONTHS = {
            "", "Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani",
            "Jumada al-Ula", "Jumada al-Thani", "Rajab", "Sha'ban",
            "Ramadan", "Shawwal", "Dhul-Qi'dah", "Dhul-Hijjah"
    };

    static final class PrayerData {
        final String hijri;
        final String formattedLine;
        final String statusText;
        final boolean isNow;
        final String fullSummary;

        PrayerData(String hijri, String formattedLine, String statusText, boolean isNow, String fullSummary) {
            this.hijri = hijri;
            this.formattedLine = formattedLine;
            this.statusText = statusText;
            this.isNow = isNow;
            this.fullSummary = fullSummary;
        }
    }

    interface Listener {
        void onPrayerUpdated(PrayerData data);
    }

    private PrayerRepo() {}

    /**
     * Called on startup or resume: returns current cached state immediately,
     * and refreshes in background if needed.
     */
    static void get(Context context, Listener listener) {
        final Context app = context.getApplicationContext();
        PrayerData cached = computeCurrent(app);
        if (listener != null && cached != null) {
            listener.onPrayerUpdated(cached);
        }
        checkRefresh(app, listener);
    }

    /**
     * Checks if a network fetch or retry is due. Called on clock tick and connectivity change.
     */
    static void checkRefresh(Context context, Listener listener) {
        final Context app = context.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String cachedDate = sp.getString("date", "");
        long lastAttempt = sp.getLong("last_attempt_at", 0);
        long now = System.currentTimeMillis();

        boolean isTodayCached = todayKey.equals(cachedDate) && !sp.getString("fajr", "").isEmpty();
        boolean needFetch = (!isTodayCached && (now - lastAttempt >= RETRY_INTERVAL_MS))
                || (isTodayCached && (now - lastAttempt >= REFRESH_INTERVAL_MS));

        if (needFetch) {
            if (fetching.compareAndSet(false, true)) {
                io.execute(() -> {
                    try {
                        detectLocationAndFetch(app, todayKey);
                    } finally {
                        fetching.set(false);
                    }
                    PrayerData fresh = computeCurrent(app);
                    if (listener != null && fresh != null) {
                        main.post(() -> listener.onPrayerUpdated(fresh));
                    }
                });
            }
        }
    }

    /**
     * Computes the single prayer line for the current time.
     * Output format example: "Fajr 5:49 in 8 h 59m" or "Isha 8:10 now salat".
     */
    static PrayerData computeCurrent(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String hijri = sp.getString("hijri", "");
        String fajr = sp.getString("fajr", "");
        String dhuhr = sp.getString("dhuhr", "");
        String asr = sp.getString("asr", "");
        String maghrib = sp.getString("maghrib", "");
        String isha = sp.getString("isha", "");
        String city = sp.getString("city", "");
        String zone = sp.getString("zone", "");

        if (fajr.isEmpty() || dhuhr.isEmpty() || asr.isEmpty() || maghrib.isEmpty() || isha.isEmpty()) {
            return hijri.isEmpty() ? null : new PrayerData(hijri, "", "", false, "");
        }

        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        int fMin = parseMinutes(fajr);
        int dMin = parseMinutes(dhuhr);
        int aMin = parseMinutes(asr);
        int mMin = parseMinutes(maghrib);
        int iMin = parseMinutes(isha);

        String[] names = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
        int[] mins = {fMin, dMin, aMin, mMin, iMin};

        // 1. Check if any prayer is currently "now salat"
        int activeIdx = -1;
        boolean isNow = false;
        for (int i = 0; i < mins.length; i++) {
            if (nowMin >= mins[i] && nowMin < mins[i] + NOW_WINDOW_MINUTES) {
                activeIdx = i;
                isNow = true;
                break;
            }
        }

        // 2. If not now, find the next upcoming prayer today
        int diffMin = 0;
        if (activeIdx < 0) {
            for (int i = 0; i < mins.length; i++) {
                if (mins[i] > nowMin) {
                    activeIdx = i;
                    diffMin = mins[i] - nowMin;
                    break;
                }
            }
        }

        // 3. After Isha, rolls over to tomorrow's Fajr
        if (activeIdx < 0) {
            activeIdx = 0; // Fajr
            diffMin = (24 * 60 - nowMin) + fMin;
        }

        // 4. Format single prayer line: "Fajr 5:49 in 8 h 59m"
        String timeStr = formatTime12(mins[activeIdx]);
        String formattedLine;
        if (isNow) {
            formattedLine = names[activeIdx] + " " + timeStr + " now salat";
        } else {
            int h = diffMin / 60;
            int m = diffMin % 60;
            String countdown;
            if (h > 0 && m > 0) {
                countdown = "in " + h + " h " + m + "m";
            } else if (h > 0) {
                countdown = "in " + h + " h";
            } else {
                countdown = "in " + m + "m";
            }
            formattedLine = names[activeIdx] + " " + timeStr + " " + countdown;
        }

        String locationLabel = !city.isEmpty() ? city : (!zone.isEmpty() ? zone : "Local");
        String fullSummary = String.format(Locale.US,
                "%s • Fajr %s • Dhuhr %s • Asr %s • Maghrib %s • Isha %s",
                locationLabel,
                formatTime12(fMin),
                formatTime12(dMin),
                formatTime12(aMin),
                formatTime12(mMin),
                formatTime12(iMin));

        return new PrayerData(hijri, formattedLine, formattedLine, isNow, fullSummary);
    }

    private static void detectLocationAndFetch(Context context, String todayKey) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putLong("last_attempt_at", System.currentTimeMillis()).apply();

        double lat = sp.getFloat("lat", 0f);
        double lon = sp.getFloat("lon", 0f);
        String city = sp.getString("city", "");
        String country = sp.getString("country", "");
        String region = sp.getString("region", "");

        // 1. Try system LocationManager
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                Location loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc != null) {
                    lat = loc.getLatitude();
                    lon = loc.getLongitude();
                }
            }
        } catch (SecurityException ignored) {}

        // 2. If no system GPS/network location (common on Android TV), use IP Geolocation
        if (lat == 0 && lon == 0) {
            try {
                String ipJson = httpGet("https://ipwho.is/", 7_000);
                if (ipJson != null) {
                    JSONObject obj = new JSONObject(ipJson);
                    if (obj.optBoolean("success", false)) {
                        lat = obj.optDouble("latitude", 0);
                        lon = obj.optDouble("longitude", 0);
                        city = obj.optString("city", city);
                        country = obj.optString("country", country);
                        region = obj.optString("region", region);
                        Log.i(TAG, "Location detected via ipwho.is: " + city + ", " + region + ", " + country + " (" + lat + ", " + lon + ")");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "ipwho.is error: " + e.getMessage());
            }

            if (lat == 0 && lon == 0) {
                try {
                    String ipJson = httpGet("http://ip-api.com/json", 7_000);
                    if (ipJson != null) {
                        JSONObject obj = new JSONObject(ipJson);
                        if ("success".equalsIgnoreCase(obj.optString("status"))) {
                            lat = obj.optDouble("lat", 0);
                            lon = obj.optDouble("lon", 0);
                            city = obj.optString("city", city);
                            country = obj.optString("country", country);
                            region = obj.optString("regionName", region);
                            Log.i(TAG, "Location detected via ip-api: " + city + ", " + region + ", " + country + " (" + lat + ", " + lon + ")");
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "ip-api error: " + e.getMessage());
                }
            }
        }

        // Save detected coordinates and location names
        sp.edit()
                .putFloat("lat", (float) lat)
                .putFloat("lon", (float) lon)
                .putString("city", city)
                .putString("country", country)
                .putString("region", region)
                .apply();

        // 3. Fetch prayer times for this location
        boolean fetched = false;

        // If in Malaysia, resolve the exact JAKIM zone and fetch from official e-Solat
        if ("Malaysia".equalsIgnoreCase(country) || lat != 0 && lon != 0 && (lon >= 99.0 && lon <= 120.0 && lat >= 0.8 && lat <= 7.5)) {
            String zone = detectJakimZone(lat, lon, region, city);
            if (!zone.isEmpty()) {
                fetched = fetchFromJakim(context, zone, city, todayKey);
            }
        }

        // Fallback or outside Malaysia: fetch precisely from Aladhan API using coordinates
        if (!fetched) {
            fetched = fetchFromAladhan(context, lat, lon, city, country, todayKey);
        }

        if (fetched) {
            Log.i(TAG, "Prayer times updated successfully for " + city + " (" + lat + ", " + lon + ")");
        } else {
            Log.w(TAG, "Failed to update prayer times, will retry in 15 minutes");
        }
    }

    private static String detectJakimZone(double lat, double lon, String region, String city) {
        // First try solat.my detect API if coordinates are valid
        if (lat != 0 && lon != 0) {
            try {
                String res = httpGet("https://solat.my/api/detect?latitude=" + lat + "&longitude=" + lon, 6_000);
                if (res != null) {
                    JSONObject obj = new JSONObject(res);
                    String z = obj.optString("zone", "").trim();
                    if (!z.isEmpty()) {
                        Log.i(TAG, "Detected JAKIM zone from coordinates: " + z);
                        return z;
                    }
                }
            } catch (Exception ignored) {}
        }

        // Region / State heuristics
        String r = (region + " " + city).toLowerCase(Locale.US);
        if (r.contains("melaka") || r.contains("malacca")) return "MLK01";
        if (r.contains("kuala lumpur") || r.contains("putrajaya")) return "WLY01";
        if (r.contains("selangor") || r.contains("petaling") || r.contains("gombak")) return "SGR01";
        if (r.contains("johor")) return "JHR02";
        if (r.contains("penang") || r.contains("pinang")) return "PNG01";
        if (r.contains("perak") || r.contains("ipoh")) return "PRK02";
        if (r.contains("kedah") || r.contains("alor setar")) return "KDH01";
        if (r.contains("negeri sembilan") || r.contains("seremban")) return "NSN01";
        if (r.contains("pahang") || r.contains("kuantan")) return "PHG02";
        if (r.contains("terengganu")) return "TRG01";
        if (r.contains("kelantan") || r.contains("kota bharu")) return "KTN01";
        if (r.contains("sabah") || r.contains("kota kinabalu")) return "SBH01";
        if (r.contains("sarawak") || r.contains("kuching")) return "SWK08";
        if (r.contains("perlis")) return "PLS01";
        if (r.contains("labuan")) return "WLY02";

        return "WLY01";
    }

    private static boolean fetchFromJakim(Context context, String zone, String city, String todayKey) {
        try {
            String url = "https://www.e-solat.gov.my/index.php?r=esolatApi/takwimsolat&period=today&zone=" + zone;
            String res = httpGet(url, 12_000);
            if (res == null) return false;

            JSONObject json = new JSONObject(res);
            JSONArray array = json.optJSONArray("prayerTime");
            if (array == null || array.length() == 0) return false;

            JSONObject pt = array.getJSONObject(0);
            String fajr = pt.getString("fajr");
            String dhuhr = pt.getString("dhuhr");
            String asr = pt.getString("asr");
            String maghrib = pt.getString("maghrib");
            String isha = pt.getString("isha");
            String hijriRaw = pt.optString("hijri", "");
            String hijriFormatted = formatHijri(hijriRaw);

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("date", todayKey)
                    .putString("zone", zone)
                    .putString("city", city)
                    .putString("hijri", hijriFormatted)
                    .putString("fajr", fajr)
                    .putString("dhuhr", dhuhr)
                    .putString("asr", asr)
                    .putString("maghrib", maghrib)
                    .putString("isha", isha)
                    .putLong("fetched_at", System.currentTimeMillis())
                    .apply();

            return true;
        } catch (Exception e) {
            Log.w(TAG, "JAKIM fetch error: " + e.getMessage());
            return false;
        }
    }

    private static boolean fetchFromAladhan(Context context, double lat, double lon, String city, String country, String todayKey) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
            String dateParam = sdf.format(new Date());

            String urlString;
            if (lat != 0 && lon != 0) {
                urlString = String.format(Locale.US,
                        "https://api.aladhan.com/v1/timings/%s?latitude=%f&longitude=%f&method=17",
                        dateParam, lat, lon);
            } else {
                String c = !city.isEmpty() ? city : "Kuala Lumpur";
                String co = !country.isEmpty() ? country : "Malaysia";
                urlString = String.format(Locale.US,
                        "https://api.aladhan.com/v1/timingsByCity/%s?city=%s&country=%s&method=17",
                        dateParam, c, co);
            }

            String res = httpGet(urlString, 12_000);
            if (res == null) return false;

            JSONObject json = new JSONObject(res);
            if (json.optInt("code", 0) != 200) return false;

            JSONObject data = json.getJSONObject("data");
            JSONObject timings = data.getJSONObject("timings");
            String fajr = timings.getString("Fajr");
            String dhuhr = timings.getString("Dhuhr");
            String asr = timings.getString("Asr");
            String maghrib = timings.getString("Maghrib");
            String isha = timings.getString("Isha");

            JSONObject dateObj = data.getJSONObject("date");
            JSONObject hijriObj = dateObj.getJSONObject("hijri");
            String hDay = hijriObj.optString("day", "");
            String hYear = hijriObj.optString("year", "");
            JSONObject monthObj = hijriObj.optJSONObject("month");
            int monthNum = monthObj != null ? monthObj.optInt("number", 0) : 0;
            String monthName = (monthNum >= 1 && monthNum < HIJRI_MONTHS.length)
                    ? HIJRI_MONTHS[monthNum]
                    : (monthObj != null ? monthObj.optString("en", "") : "");

            String hijriFormatted = hDay + " " + monthName + " " + hYear + " AH";

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("date", todayKey)
                    .putString("city", city)
                    .putString("hijri", hijriFormatted)
                    .putString("fajr", fajr)
                    .putString("dhuhr", dhuhr)
                    .putString("asr", asr)
                    .putString("maghrib", maghrib)
                    .putString("isha", isha)
                    .putLong("fetched_at", System.currentTimeMillis())
                    .apply();

            return true;
        } catch (Exception e) {
            Log.w(TAG, "Aladhan fetch error: " + e.getMessage());
            return false;
        }
    }

    private static String httpGet(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; ATVHome Launcher)");
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int parseMinutes(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return 0;
        try {
            String clean = timeStr.trim().split(" ")[0];
            String[] parts = clean.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatTime12(int totalMinutes) {
        int h24 = (totalMinutes / 60) % 24;
        int m = totalMinutes % 60;
        int h12 = h24 % 12;
        if (h12 == 0) h12 = 12;
        return String.format(Locale.US, "%d:%02d", h12, m);
    }

    private static String formatHijri(String hijriRaw) {
        if (hijriRaw == null || hijriRaw.isEmpty()) return "";
        try {
            String[] parts = hijriRaw.trim().split("-");
            if (parts.length == 3) {
                int y = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                int d = Integer.parseInt(parts[2]);
                String monthName = (m >= 1 && m < HIJRI_MONTHS.length) ? HIJRI_MONTHS[m] : ("Month " + m);
                return d + " " + monthName + " " + y + " AH";
            }
        } catch (Exception ignored) {}
        return hijriRaw;
    }
}
