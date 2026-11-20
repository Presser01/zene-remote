package hu.peresztegi.zeneremote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * YouTube Music keresés a webes kliens belső (InnerTube) API-jával, ugyanúgy,
 * ahogy a music.youtube.com oldal is keres. Nem kell hozzá API kulcs vagy bejelentkezés.
 * Csak plain Java + org.json (Androidon beépített), így asztali JVM-en is tesztelhető.
 */
final class YtmSearch {

    private static final String ENDPOINT =
            "https://music.youtube.com/youtubei/v1/search?prettyPrint=false";
    /** "Dalok" szűrő, ugyanaz, mint a YouTube Music kereső "Dalok" fülén. */
    private static final String SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF";
    private static final String BULLET = " • ";

    private YtmSearch() {
    }

    static JSONArray search(String query) throws IOException, JSONException {
        JSONObject client = new JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", clientVersion())
                .put("hl", "hu")
                .put("gl", "HU");
        JSONObject body = new JSONObject()
                .put("context", new JSONObject().put("client", client))
                .put("query", query)
                .put("params", SONGS_FILTER);

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            c.setRequestProperty("Origin", "https://music.youtube.com");
            c.setRequestProperty("Referer", "https://music.youtube.com/");
            c.setRequestProperty("Cookie", "SOCS=CAI"); // EU-s cookie/consent oldal átugrása

            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("YouTube Music HTTP " + code);

            JSONObject resp = new JSONObject(readAll(c.getInputStream()));
            JSONArray out = new JSONArray();
            collect(resp, out, new HashSet<>());
            return out;
        } finally {
            c.disconnect();
        }
    }

    /** Végigjárja a választ, és minden találat-sort (musicResponsiveListItemRenderer) kigyűjt. */
    private static void collect(Object node, JSONArray out, Set<String> seen) throws JSONException {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject item = o.optJSONObject("musicResponsiveListItemRenderer");
            if (item != null) {
                JSONObject song = parse(item);
                if (song != null && seen.add(song.getString("id"))) out.put(song);
                return;
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) collect(o.opt(keys.next()), out, seen);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out, seen);
        }
    }

    private static JSONObject parse(JSONObject item) throws JSONException {
        String id = null;
        JSONObject pid = item.optJSONObject("playlistItemData");
        if (pid != null) id = pid.optString("videoId", null);
        if (id == null) id = findString(item, "videoId");
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return null; // pl. előadó vagy album sor

        JSONArray cols = item.optJSONArray("flexColumns");
        String title = colText(cols, 0);
        String details = colText(cols, 1);

        // details pl.: "Előadó • Album • 3:21"
        String duration = "";
        String subtitle = details;
        int last = details.lastIndexOf(BULLET);
        if (last >= 0) {
            String tail = details.substring(last + BULLET.length()).trim();
            if (tail.matches("\\d{1,2}(:\\d{2}){1,2}")) {
                duration = tail;
                subtitle = details.substring(0, last);
            }
        }

        return new JSONObject()
                .put("id", id)
                .put("title", title)
                .put("subtitle", subtitle)
                .put("duration", duration)
                .put("thumb", thumbnail(item));
    }

    private static String colText(JSONArray cols, int i) {
        if (cols == null || i >= cols.length()) return "";
        JSONObject col = cols.optJSONObject(i);
        if (col == null) return "";
        JSONObject r = col.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        if (r == null) return "";
        JSONObject text = r.optJSONObject("text");
        if (text == null) return "";
        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < runs.length(); k++) {
            JSONObject run = runs.optJSONObject(k);
            if (run != null) b.append(run.optString("text", ""));
        }
        return b.toString();
    }

    private static String thumbnail(JSONObject item) {
        JSONObject t = item.optJSONObject("thumbnail");
        if (t != null) t = t.optJSONObject("musicThumbnailRenderer");
        if (t != null) t = t.optJSONObject("thumbnail");
        JSONArray list = t != null ? t.optJSONArray("thumbnails") : null;
        if (list == null || list.length() == 0) return "";
        JSONObject first = list.optJSONObject(0); // a legkisebb is elég egy listához
        return first != null ? first.optString("url", "") : "";
    }

    private static String findString(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Object v = o.opt(key);
            if (v instanceof String) return (String) v;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String r = findString(o.opt(keys.next()), key);
                if (r != null) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = findString(a.opt(i), key);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** "1.YYYYMMDD.01.00" pár nappal ezelőtti dátummal, ahogy a webes kliens is küldi. */
    private static String clientVersion() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "1." + f.format(new Date(System.currentTimeMillis() - 2L * 24 * 3600 * 1000)) + ".01.00";
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = s.read(buf)) != -1) out.write(buf, 0, r);
            return out.toString("UTF-8");
        }
    }
}
