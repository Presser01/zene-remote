package hu.peresztegi.zeneremote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Felhasználók, adminok és a közös várólista. Android-független (csak org.json),
 * így asztali JVM-en is tesztelhető. Minden metódus szálbiztos.
 */
final class Party {

    static final int MAX_QUEUE = 100;
    static final int MAX_NAME = 24;
    private static final long ONLINE_MS = 60_000;

    static final class User {
        String id;      // nyilvános azonosító (mások ezt látják)
        String token;   // titkos, csak a saját böngészője tudja
        String name;
        boolean admin;
        long seen;
    }

    static final class Item {
        long qid;
        String id, title, artist, thumb, duration, by, byId;
    }

    interface Store {
        void save(String json);
    }

    private final Map<String, User> byToken = new LinkedHashMap<>();
    private final ArrayList<Item> queue = new ArrayList<>();
    private Item current;
    private long nextQid = 1;
    private final SecureRandom rnd = new SecureRandom();
    private final Store store;

    Party(String savedJson, Store store) {
        this.store = store;
        if (savedJson != null && !savedJson.isEmpty()) {
            try {
                load(new JSONObject(savedJson));
            } catch (JSONException ignored) {
                // sérült mentés -> tiszta lappal indulunk
            }
        }
    }

    // ---------------------------------------------------------------- felhasználók

    /** Belépés / átnevezés. Ismert tokennél csak a nevet frissíti. */
    synchronized User join(String token, String rawName) {
        String name = cleanName(rawName);
        if (name.isEmpty()) return null;
        User u = token != null ? byToken.get(token) : null;
        if (u == null) {
            u = new User();
            u.token = hex(16);
            u.id = hex(6);
            byToken.put(u.token, u);
        }
        u.name = name;
        u.seen = System.currentTimeMillis();
        save();
        return u;
    }

    synchronized User auth(String token) {
        if (token == null || token.isEmpty()) return null;
        User u = byToken.get(token);
        if (u != null) u.seen = System.currentTimeMillis();
        return u;
    }

    synchronized void makeAdmin(User u) {
        u.admin = true;
        save();
    }

    /** Admin kinevez / lefokoz valakit a nyilvános azonosítója alapján. */
    synchronized boolean setAdmin(String targetId, boolean on) {
        for (User u : byToken.values()) {
            if (u.id.equals(targetId)) {
                u.admin = on;
                save();
                return true;
            }
        }
        return false;
    }

    synchronized boolean hasAdmin() {
        for (User u : byToken.values()) if (u.admin) return true;
        return false;
    }

    // ---------------------------------------------------------------- várólista

    synchronized Item add(User by, String id, String title, String artist, String thumb, String duration) {
        if (queue.size() >= MAX_QUEUE) return null;
        Item it = new Item();
        it.qid = nextQid++;
        it.id = id;
        it.title = clip(title, 200);
        it.artist = clip(artist, 200);
        it.thumb = thumb != null && thumb.startsWith("https://") && thumb.length() < 600 ? thumb : "";
        it.duration = clip(duration, 10);
        it.by = by.name;
        it.byId = by.id;
        queue.add(it);
        save();
        return it;
    }

    synchronized boolean remove(long qid) {
        int i = indexOf(qid);
        if (i < 0) return false;
        queue.remove(i);
        save();
        return true;
    }

    /** Elem mozgatása: delta = -1 feljebb, +1 lejjebb. */
    synchronized boolean move(long qid, int delta) {
        int i = indexOf(qid);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= queue.size()) return false;
        queue.add(j, queue.remove(i));
        save();
        return true;
    }

    synchronized void clear() {
        queue.clear();
        save();
    }

    /** A sor elejét veszi le, és ezt jelöli "most szól"-nak. */
    synchronized Item pop() {
        if (queue.isEmpty()) return null;
        current = queue.remove(0);
        save();
        return current;
    }

    /** Adott elem azonnali lejátszásra kivétele a sorból. */
    synchronized Item take(long qid) {
        int i = indexOf(qid);
        if (i < 0) return null;
        current = queue.remove(i);
        save();
        return current;
    }

    synchronized Item current() {
        return current;
    }

    synchronized void clearCurrent() {
        current = null;
    }

    synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    synchronized int size() {
        return queue.size();
    }

    // ---------------------------------------------------------------- JSON

    synchronized JSONObject state(User me) throws JSONException {
        JSONArray q = new JSONArray();
        for (Item it : queue) q.put(itemJson(it, me));

        JSONArray users = new JSONArray();
        long now = System.currentTimeMillis();
        for (User u : byToken.values()) {
            users.put(new JSONObject()
                    .put("id", u.id)
                    .put("name", u.name)
                    .put("admin", u.admin)
                    .put("online", now - u.seen < ONLINE_MS)
                    .put("me", u == me));
        }

        return new JSONObject()
                .put("me", new JSONObject().put("id", me.id).put("name", me.name).put("admin", me.admin))
                .put("queue", q)
                .put("current", current != null ? itemJson(current, me) : JSONObject.NULL)
                .put("users", users);
    }

    private static JSONObject itemJson(Item it, User me) throws JSONException {
        return new JSONObject()
                .put("qid", it.qid)
                .put("id", it.id)
                .put("title", it.title)
                .put("artist", it.artist)
                .put("thumb", it.thumb)
                .put("duration", it.duration)
                .put("by", it.by)
                .put("mine", me != null && me.id.equals(it.byId));
    }

    private void save() {
        if (store == null) return;
        try {
            JSONArray users = new JSONArray();
            for (User u : byToken.values()) {
                users.put(new JSONObject().put("id", u.id).put("token", u.token)
                        .put("name", u.name).put("admin", u.admin));
            }
            JSONArray q = new JSONArray();
            for (Item it : queue) q.put(itemJson(it, null).put("byId", it.byId));
            store.save(new JSONObject().put("users", users).put("queue", q)
                    .put("nextQid", nextQid).toString());
        } catch (JSONException ignored) {
        }
    }

    private void load(JSONObject o) throws JSONException {
        JSONArray users = o.optJSONArray("users");
        if (users != null) {
            for (int i = 0; i < users.length(); i++) {
                JSONObject j = users.getJSONObject(i);
                User u = new User();
                u.id = j.getString("id");
                u.token = j.getString("token");
                u.name = j.optString("name", "?");
                u.admin = j.optBoolean("admin", false);
                byToken.put(u.token, u);
            }
        }
        JSONArray q = o.optJSONArray("queue");
        if (q != null) {
            for (int i = 0; i < q.length(); i++) {
                JSONObject j = q.getJSONObject(i);
                Item it = new Item();
                it.qid = j.getLong("qid");
                it.id = j.getString("id");
                it.title = j.optString("title", "");
                it.artist = j.optString("artist", "");
                it.thumb = j.optString("thumb", "");
                it.duration = j.optString("duration", "");
                it.by = j.optString("by", "");
                it.byId = j.optString("byId", "");
                queue.add(it);
            }
        }
        nextQid = Math.max(o.optLong("nextQid", 1), 1);
        for (Item it : queue) nextQid = Math.max(nextQid, it.qid + 1);
    }

    // ---------------------------------------------------------------- segédek

    private int indexOf(long qid) {
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).qid == qid) return i;
        return -1;
    }

    static String cleanName(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c)) b.append(c);
        }
        String n = b.toString().trim().replaceAll("\\s+", " ");
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME).trim() : n;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
