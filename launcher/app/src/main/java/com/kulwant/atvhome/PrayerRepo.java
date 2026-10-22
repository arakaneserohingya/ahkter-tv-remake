package com.kulwant.atvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages Islamic prayer times (Salat) and Hijri date, for wherever the TV is: its own location
 * (from nearby Wi-Fi on most TVs) or else its internet address. In Malaysia, JAKIM's official
 * e-Solat times for the zone it is in; elsewhere, the local authority's method via Aladhan.
 * A zone in config.xml (prayer_zone) overrides the location. Refreshed daily.
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
        // prayer_zone in config.xml changed since the last fetch (a zone typed in, or cleared to
        // follow the TV's location again), or the times came from a build that didn't record it:
        // don't wait for the daily refresh
        boolean zoneChanged = !sp.contains("zone_setting")
                || !configuredZone(app).equals(sp.getString("zone_setting", ""));
        boolean needFetch = ((!isTodayCached || zoneChanged) && (now - lastAttempt >= RETRY_INTERVAL_MS))
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

    /** The prayer_zone from config.xml (e.g. WLY01), or "" when it is empty or not a JAKIM zone code. */
    static String configuredZone(Context context) {
        return normaliseZone(context.getString(R.string.prayer_zone));
    }

    /** "wly01 " -> "WLY01"; anything that isn't three letters and two digits -> "". */
    static String normaliseZone(String raw) {
        String z = raw == null ? "" : raw.trim().toUpperCase(Locale.US);
        return z.matches("[A-Z]{3}\\d{2}") ? z : "";
    }

    /** Where the TV is, as far as its prayer times go. */
    private static final class Place {
        double lat, lon;   // rounded to about 1 km before anything is sent: plenty for prayer times
        String city = "", region = "", country = "", countryCode = "";
        String source = "";
    }

    private static void detectLocationAndFetch(Context context, String todayKey) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putLong("last_attempt_at", System.currentTimeMillis()).apply();

        // 1. A JAKIM zone typed into config.xml: only for a TV that can't tell where it is (a VPN,
        // say). If e-Solat is down, keep the cached times and retry.
        String configured = configuredZone(context);
        if (!configured.isEmpty()) {
            if (fetchFromJakim(context, configured, "", todayKey)) {
                sp.edit().putString("zone_setting", configured).apply();
                Log.i(TAG, "Prayer times updated for configured zone " + configured);
            } else {
                Log.w(TAG, "e-Solat fetch for " + configured + " failed, will retry in 15 minutes");
            }
            return;
        }
        if (!context.getString(R.string.prayer_zone).trim().isEmpty()) {
            Log.w(TAG, "prayer_zone in config.xml is not a JAKIM zone code (like WLY01); using the TV's location");
        }

        // 2. Where the TV is, found again on every refresh, so a TV that moves follows along
        Place p = locate(context);
        if (p == null) {
            Log.w(TAG, "Couldn't tell where the TV is, will retry in 15 minutes");
            return;   // the cached times stay up meanwhile
        }
        sp.edit().putFloat("lat", (float) p.lat).putFloat("lon", (float) p.lon)
                .putString("country", p.country).putString("region", p.region).apply();

        // 3. In Malaysia: JAKIM's official times for the zone the TV is in. Should e-Solat be down,
        // the same coordinates calculated with JAKIM's method, never another country's.
        boolean fetched;
        String[] zone = jakimZone(p);
        if (zone != null) {
            String label = zone[1] + " (" + zone[0] + ")";
            fetched = fetchFromJakim(context, zone[0], label, todayKey)
                    || fetchFromAladhan(context, p.lat, p.lon, label, ALADHAN_JAKIM, todayKey);
            Log.i(TAG, "JAKIM zone " + zone[0] + " " + zone[1] + ", from " + p.source);
        } else {
            // 4. Elsewhere: the local authority's method for these coordinates (Aladhan picks it:
            // MUIS in Singapore, Umm al-Qura in Saudi Arabia, the Muslim World League in Europe...)
            fetched = fetchFromAladhan(context, p.lat, p.lon, p.city, 0, todayKey);
            Log.i(TAG, "Outside Malaysia: " + p.city + ", " + p.country + ", from " + p.source);
        }
        if (fetched) {
            sp.edit().putString("zone_setting", "").apply();
        } else {
            Log.w(TAG, "Failed to update prayer times, will retry in 15 minutes");
        }
    }

    /** Aladhan's number for JAKIM's calculation method. */
    private static final int ALADHAN_JAKIM = 17;

    /**
     * Where the TV is. Its own location first: Android TVs work it out from the Wi-Fi networks
     * around them, to within a street or so. Without that (location off, or no permission), the
     * internet address, which gets the city right but can be a state off.
     */
    private static Place locate(Context c) {
        Location loc = deviceLocation(c);
        Place p;
        if (loc != null) {
            p = placeNames(c, loc.getLatitude(), loc.getLongitude());
            p.lat = loc.getLatitude();
            p.lon = loc.getLongitude();
            p.source = "the TV's location";
        } else {
            p = ipPlace();
            if (p == null) return null;
            p.source = "its internet address";
        }
        p.lat = Math.round(p.lat * 100) / 100.0;
        p.lon = Math.round(p.lon * 100) / 100.0;
        return p;
    }

    /** The newest fix Android has, or a new one when that is over six hours old. */
    @SuppressWarnings("MissingPermission")   // in the manifest, granted by build_and_install.sh
    private static Location deviceLocation(Context c) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) return null;
        Location best = null;
        for (String provider : new String[]{"fused", LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (RuntimeException ignored) {   // no such provider here, or no permission
            }
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 6 * 3600_000L) return best;
        Location fresh = currentLocation(lm);
        return fresh != null ? fresh : best;   // even an old fix beats a guess from the address
    }

    /** A new fix from the network (Wi-Fi) provider, waiting up to 15 s; this runs on the io thread. */
    @SuppressWarnings("MissingPermission")
    private static Location currentLocation(LocationManager lm) {
        if (Build.VERSION.SDK_INT < 30) return null;
        CountDownLatch done = new CountDownLatch(1);
        Location[] got = new Location[1];
        CancellationSignal cancel = new CancellationSignal();
        try {
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, cancel, Runnable::run, l -> {
                got[0] = l;
                done.countDown();
            });
            if (!done.await(15, TimeUnit.SECONDS)) cancel.cancel();
        } catch (Exception ignored) {   // provider off or missing, or no permission
        }
        return got[0];
    }

    /** City and country for a location: the TV's geocoder if it has one, else the address lookup. */
    private static Place placeNames(Context c, double lat, double lon) {
        if (Geocoder.isPresent()) {
            try {
                @SuppressWarnings("deprecation")   // the callback version needs Android 13
                List<Address> found = new Geocoder(c, Locale.US).getFromLocation(lat, lon, 1);
                if (found != null && !found.isEmpty()) {
                    Address a = found.get(0);
                    Place p = new Place();
                    p.city = firstOf(a.getLocality(), a.getSubAdminArea(), a.getAdminArea());
                    p.region = firstOf(a.getAdminArea());
                    p.country = firstOf(a.getCountryName());
                    p.countryCode = firstOf(a.getCountryCode());
                    return p;
                }
            } catch (Exception ignored) {   // no geocoding service reachable
            }
        }
        Place ip = ipPlace();
        return ip != null ? ip : new Place();
    }

    private static String firstOf(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    /** City, country and rough coordinates from the TV's internet address. */
    private static Place ipPlace() {
        try {
            String res = httpGet("https://ipwho.is/", 7_000);
            JSONObject o = res == null ? null : new JSONObject(res);
            if (o != null && o.optBoolean("success", false)) {
                Place p = new Place();
                p.lat = o.optDouble("latitude", 0);
                p.lon = o.optDouble("longitude", 0);
                p.city = o.optString("city", "");
                p.region = o.optString("region", "");
                p.country = o.optString("country", "");
                p.countryCode = o.optString("country_code", "");
                return p;
            }
        } catch (Exception e) {
            Log.w(TAG, "ipwho.is error: " + e.getMessage());
        }
        try {
            String res = httpGet("http://ip-api.com/json", 7_000);
            JSONObject o = res == null ? null : new JSONObject(res);
            if (o != null && "success".equalsIgnoreCase(o.optString("status"))) {
                Place p = new Place();
                p.lat = o.optDouble("lat", 0);
                p.lon = o.optDouble("lon", 0);
                p.city = o.optString("city", "");
                p.region = o.optString("regionName", "");
                p.country = o.optString("country", "");
                p.countryCode = o.optString("countryCode", "");
                return p;
            }
        } catch (Exception e) {
            Log.w(TAG, "ip-api error: " + e.getMessage());
        }
        return null;
    }

    /**
     * The JAKIM zone the TV is in, {code, name}, or null outside Malaysia. solat.my maps coordinates
     * to zones; for places just across the border (Singapore, Batam, Brunei) it answers with the
     * nearest zone and its distance, so only a match within 3 km counts. If solat.my can't be
     * reached, a TV whose names say Malaysia gets its state's main zone.
     */
    private static String[] jakimZone(Place p) {
        try {
            String res = httpGet(String.format(Locale.US,
                    "https://solat.my/api/detect?latitude=%.2f&longitude=%.2f", p.lat, p.lon), 8_000);
            if (res != null) {
                JSONObject o = new JSONObject(res);
                String code = normaliseZone(o.optString("zone", ""));
                if (code.isEmpty()) return null;   // "Location is outside Malaysia"
                if (o.optDouble("distanceKm", 0) > 3) return null;
                return new String[]{code, o.optString("name", code)};
            }
        } catch (Exception e) {
            Log.w(TAG, "solat.my error: " + e.getMessage());
        }
        if ("MY".equalsIgnoreCase(p.countryCode) || "Malaysia".equalsIgnoreCase(p.country)) {
            String code = zoneFromPlaceName(p.region, p.city);
            return new String[]{code, p.city.isEmpty() ? code : p.city};
        }
        return null;
    }

    /** A state's main zone from place names, for when solat.my can't be reached. */
    private static String zoneFromPlaceName(String region, String city) {
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

    /** Times calculated for the coordinates. method 0: Aladhan picks the local authority's method. */
    private static boolean fetchFromAladhan(Context context, double lat, double lon, String city, int method, String todayKey) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
            String dateParam = sdf.format(new Date());

            String urlString = String.format(Locale.US,
                    "https://api.aladhan.com/v1/timings/%s?latitude=%.2f&longitude=%.2f", dateParam, lat, lon)
                    + (method > 0 ? "&method=" + method : "");

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
                    .putString("zone", "")   // not a JAKIM zone's times
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

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "GET " + conn.getURL().getHost() + " answered HTTP " + code);
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // the innermost cause says why (e.g. which certificate check failed); the outer one rarely does
            Throwable why = e;
            while (why.getCause() != null) why = why.getCause();
            Log.w(TAG, "GET " + urlStr.replaceFirst("\\?.*", "") + " failed: " + e + (why != e ? " <- " + why : ""));
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
