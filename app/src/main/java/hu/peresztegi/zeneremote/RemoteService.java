package hu.peresztegi.zeneremote;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Előtérben futó szolgáltatás egy minimális HTTP szerverrel a PORT-on.
 * Az app bezárása után is fut; telefon-újraindításkor magától elindul (BootReceiver).
 *
 * Nyilvános végpontok:
 *   GET /                 -> távirányító oldal (assets/index.html)
 *   GET /join?name=       -> belépés névvel, visszaadja a tokent
 *
 * Belépés után (X-Token fejléc):
 *   GET /state            -> most szól + várólista + résztvevők (JSON)
 *   GET /status           -> csak a most szól (JSON)
 *   GET /search?q=        -> YouTube Music keresés, dalok (JSON)
 *   GET /queue/add?id=&t=&a=&th=&d=  -> dal a sor végére (bárki)
 *   GET /toggle, /volup, /voldown    -> bárki
 *   GET /admin/claim?pin= -> adminná válás a telefonon látható PIN-nel
 *
 * Csak adminnak (ha nincs egy admin se, akkor bárkinek):
 *   GET /next, /prev, /play/{id}
 *   GET /queue/remove?qid=, /queue/move?qid=&d=-1|1, /queue/playnow?qid=, /queue/clear
 *
 * Csak adminnak: GET /users/admin?id=&on=1|0 (saját magáról bárki lemondhat)
 */
public class RemoteService extends Service {

    public static final int PORT = 1821;
    public static volatile boolean running = false;
    public static volatile String lastError = null;

    private static final String TAG = "ZeneRemote";
    private static final String CHANNEL_ID = "remote";
    private static final String YTM = "com.google.android.apps.youtube.music";
    private static final String WAZE = "com.waze";
    static final String PREFS = "settings";
    static final String PREF_BACK_TO_WAZE = "backToWaze";
    static final String PREF_HOSTNAME = "hostname";
    static final String PREF_ENABLED = "enabled";
    static final String PREF_PIN = "adminPin";
    static final String PREF_PARTY = "party";
    static final String DEFAULT_HOSTNAME = "zene";
    static final String ACTION_STOP = "hu.peresztegi.zeneremote.STOP";
    /** Az aktuális helyi név, pl. "zene.local" (a MainActivity mutatja). */
    public static volatile String hostName = null;

    /** Ennyi ideig nem léptetünk újra egy dal indítása után (betöltés). */
    private static final long SETTLE_MS = 8000;
    /** Ha a dalból ennyi van hátra, indítjuk a sor következőjét. */
    private static final long END_MS = 1500;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile ServerSocket server;
    private ExecutorService pool;
    private ScheduledExecutorService ticker;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;
    private MdnsResponder mdns;
    private byte[] indexHtml;
    private Party party;
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    private int ticks;
    private long lastTickAt;

    // PIN próbálkozások korlátozása
    private int pinFails;
    private long pinFailAt;

    // automatikus léptetés állapota (csak a ticker szál használja, kivéve lastAdvanceAt)
    private volatile long lastAdvanceAt;
    private String lastKey;
    private long lastPos, lastDur;
    private boolean lastPlaying;
    private volatile String currentKey;

    // ---------------------------------------------------------------- indítás kívülről

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(PREF_ENABLED, false);
    }

    /** Bekapcsolja (és megjegyzi, hogy fusson). Hamis, ha a rendszer most nem engedte. */
    static boolean start(Context c) {
        prefs(c).edit().putBoolean(PREF_ENABLED, true).apply();
        try {
            c.startForegroundService(new Intent(c, RemoteService.class));
            return true;
        } catch (Exception e) { // Android 12+: háttérből indítás tiltva lehet
            Log.w(TAG, "Szolgáltatás indítása nem sikerült", e);
            return false;
        }
    }

    /** Leállítja, és megjegyzi, hogy magától se induljon újra. */
    static void stop(Context c) {
        prefs(c).edit().putBoolean(PREF_ENABLED, false).apply();
        c.stopService(new Intent(c, RemoteService.class));
    }

    /** 6 jegyű admin PIN; első használatkor generálja. */
    static String pin(Context c) {
        SharedPreferences p = prefs(c);
        String pin = p.getString(PREF_PIN, null);
        if (pin == null) pin = newPin(c);
        return pin;
    }

    static String newPin(Context c) {
        String pin = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
        prefs(c).edit().putString(PREF_PIN, pin).apply();
        return pin;
    }

    // ---------------------------------------------------------------- életciklus

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = prefs(this);
        startInForeground();
        ServiceLog.started(this);
        acquireLocks();
        indexHtml = loadAsset("index.html");
        party = new Party(prefs.getString(PREF_PARTY, null),
                json -> prefs.edit().putString(PREF_PARTY, json).apply());
        startServer();

        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(() -> {
            try {
                if (++ticks % 60 == 0) ServiceLog.alive(this); // percenként életjel a naplóhoz
                // Ha a rendszer befagyasztja / elaltatja a folyamatot, a ticker sem fut:
                // feléledéskor látszik a kiesés, ezt naplózzuk.
                long now = SystemClock.elapsedRealtime();
                if (lastTickAt > 0 && now - lastTickAt > 15_000) {
                    ServiceLog.add(this, "⏸ " + (now - lastTickAt) / 1000
                            + " mp-ig nem futott (a rendszer befagyasztotta vagy elaltatta)");
                }
                lastTickAt = now;
                tick();
            } catch (Throwable t) { // különben a ticker csendben leállna
                Log.w(TAG, "Léptetés hiba", t);
            }
        }, 2, 1, TimeUnit.SECONDS);

        prefListener = (p, key) -> {
            if (PREF_PIN.equals(key)) startInForeground(); // új PIN az értesítésben
        };
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        String name = prefs.getString(PREF_HOSTNAME, DEFAULT_HOSTNAME);
        mdns = new MdnsResponder(name);
        mdns.start();
        hostName = mdns.hostName();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean(PREF_ENABLED, false).apply();
            ServiceLog.add(this, "Leállítás az értesítésből");
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground(); // minden startForegroundService után kötelező
        return START_STICKY;
    }

    /** Ha az appot kisöprik a legutóbbiak közül, és a rendszer ezzel minket is leállítana. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        ServiceLog.add(this, "Az appot kisöpörték a legutóbbiak közül");
        if (isEnabled(this)) {
            PendingIntent pi = PendingIntent.getForegroundService(this, 2,
                    new Intent(this, RemoteService.class), PendingIntent.FLAG_IMMUTABLE);
            getSystemService(AlarmManager.class)
                    .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 2000, pi);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        ServiceLog.stopped(this);
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
        if (ticker != null) ticker.shutdownNow();
        if (prefListener != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (mdns != null) mdns.stop();
        hostName = null;
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- setup

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Távirányító szerver", NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RemoteService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Passenger DJ fut")
                .setContentText("Port: " + PORT + "  ·  Admin PIN: " + pin(this))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Leállítás", stop).build())
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager pm = getSystemService(PowerManager.class);
        // Honor/Huawei energiakezelője (PowerGenie) lelövi a sokáig wakelockot tartó appokat,
        // kivéve, ha a wakelock neve a beépített fehérlistáján van. Ez a név rajta van.
        String m = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        String tag = m.contains("honor") || m.contains("huawei") ? "LocationManagerService" : "ZeneRemote:cpu";
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ZeneRemote:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
            // Wi-Fi-n enélkül a telefon eldobja a multicast (mDNS) csomagokat
            multicastLock = wm.createMulticastLock("ZeneRemote:mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
    }

    private byte[] loadAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            return out.toByteArray();
        } catch (IOException e) {
            return "<h1>index.html hiányzik</h1>".getBytes(StandardCharsets.UTF_8);
        }
    }

    private void startServer() {
        pool = Executors.newFixedThreadPool(6);
        Thread t = new Thread(() -> {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT)); // minden interfészen: Wi-Fi + Tailscale
                server = s;
                running = true;
                lastError = null;
                while (!s.isClosed()) {
                    Socket client = s.accept();
                    pool.execute(() -> handle(client));
                }
            } catch (IOException e) {
                // szándékos leállításnál a socket már zárva van -> az nem hiba
                if (server == null || !server.isClosed()) {
                    lastError = e.getMessage();
                    Log.e(TAG, "Szerver hiba", e);
                }
            } finally {
                running = false;
            }
        }, "http-accept");
        t.start();
    }

    // ---------------------------------------------------------------- HTTP

    private static final class Resp {
        final int code;
        final String type;
        final byte[] body;

        Resp(int code, String type, byte[] body) {
            this.code = code;
            this.type = type;
            this.body = body;
        }

        static Resp json(int code, String json) {
            return new Resp(code, "application/json; charset=utf-8",
                    json.getBytes(StandardCharsets.UTF_8));
        }

        static Resp json(JSONObject o) {
            return json(200, o.toString());
        }

        static Resp ok() {
            return json(200, "{\"ok\":true}");
        }

        static Resp err(int code, String msg) {
            return json(code, "{\"ok\":false,\"error\":\"" + esc(msg) + "\"}");
        }
    }

    private void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String token = null;
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                if (h.regionMatches(true, 0, "X-Token:", 0, 8)) token = h.substring(8).trim();
            }

            String[] parts = requestLine.split(" ");
            Resp resp;
            if (parts.length < 2) {
                resp = Resp.err(400, "Hibás kérés");
            } else {
                String path = parts[1];
                String query = "";
                int q = path.indexOf('?');
                if (q >= 0) {
                    query = path.substring(q + 1);
                    path = path.substring(0, q);
                }
                try {
                    resp = route(path, query, token);
                } catch (Exception e) {
                    Log.w(TAG, "Feldolgozás hiba", e);
                    resp = Resp.err(500, "Belső hiba: " + e.getMessage());
                }
            }
            write(s.getOutputStream(), resp);
        } catch (Exception e) {
            Log.w(TAG, "Kérés hiba", e);
        }
    }

    private static void write(OutputStream out, Resp r) throws IOException {
        String head = "HTTP/1.1 " + r.code + " " + (r.code == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + r.type + "\r\n"
                + "Content-Length: " + r.body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(r.body);
        out.flush();
    }

    private Resp route(String path, String query, String token) throws Exception {
        // --- belépés nélkül ---
        switch (path) {
            case "/":
            case "/index.html":
                return new Resp(200, "text/html; charset=utf-8", indexHtml);
            case "/join": {
                Party.User u = party.join(token, param(query, "name"));
                if (u == null) return Resp.err(400, "Adj meg egy nevet");
                return Resp.json(new JSONObject().put("ok", true).put("token", u.token)
                        .put("me", new JSONObject().put("id", u.id).put("name", u.name)
                                .put("admin", u.admin)));
            }
        }

        Party.User me = party.auth(token);
        if (me == null) return Resp.json(401, "{\"ok\":false,\"auth\":true,\"error\":\"Előbb add meg a neved\"}");

        // --- bárki ---
        switch (path) {
            case "/state": {
                JSONObject st = party.state(me).put("ok", true).put("now", nowJson());
                st.getJSONObject("me").put("canControl", canControl(me));
                return Resp.json(st);
            }
            case "/status":
                return Resp.json(nowJson().put("ok", true));
            case "/search":
                return search(param(query, "q"));
            case "/toggle":
                return toggle();
            case "/volup":
                adjustVolume(AudioManager.ADJUST_RAISE);
                return Resp.ok();
            case "/voldown":
                adjustVolume(AudioManager.ADJUST_LOWER);
                return Resp.ok();
            case "/queue/add":
                return queueAdd(me, query);
            case "/admin/claim":
                return claimAdmin(me, param(query, "pin"));
        }

        // Admin-jog kezelése: csak tényleges admin nevezhet ki / fokozhat le másokat.
        // Saját magáról bárki lemondhat (ha az utolsó admin lemond, mindenki egyenrangú lesz).
        if (path.equals("/users/admin")) {
            String id = param(query, "id");
            boolean on = "1".equals(param(query, "on"));
            boolean self = id.equals(me.id);
            if (!me.admin || (self && on)) return Resp.err(403, "Ehhez admin kell");
            return party.setAdmin(id, on) ? Resp.ok() : Resp.err(404, "Nincs ilyen résztvevő");
        }

        boolean adminPath = path.equals("/next") || path.equals("/prev") || path.startsWith("/play/")
                || path.startsWith("/queue/");
        if (!adminPath) return Resp.err(404, "Nincs ilyen");
        // Ha nincs egy admin se, mindenki egyenrangú: bárki léptethet, törölhet.
        if (!canControl(me)) return Resp.err(403, "Ehhez admin kell");

        // --- admin, vagy bárki, ha nincs admin ---
        switch (path) {
            case "/next":
                if (advance()) return Resp.ok(); // ha van a sorban, az jön
                return withController(c -> c.getTransportControls().skipToNext());
            case "/prev":
                return withController(c -> c.getTransportControls().skipToPrevious());
            case "/queue/remove":
                return party.remove(lparam(query, "qid")) ? Resp.ok() : Resp.err(404, "Nincs a sorban");
            case "/queue/move":
                return party.move(lparam(query, "qid"), (int) Math.signum(lparam(query, "d")))
                        ? Resp.ok() : Resp.err(400, "Nem mozgatható");
            case "/queue/clear":
                party.clear();
                return Resp.ok();
            case "/queue/playnow": {
                Party.Item it = party.take(lparam(query, "qid"));
                if (it == null) return Resp.err(404, "Nincs a sorban");
                return startItem(it);
            }
        }
        if (path.startsWith("/play/")) {
            String id = path.substring("/play/".length());
            if (!id.matches("[A-Za-z0-9_-]{11}")) return Resp.err(400, "Hibás videó ID");
            party.clearCurrent();
            markStarted();
            return openSong(id, param(query, "t"), param(query, "a"));
        }
        return Resp.err(404, "Nincs ilyen");
    }

    /** Admin vagy, vagy senki sem admin (egyenrangú mód). */
    private boolean canControl(Party.User me) {
        return me.admin || !party.hasAdmin();
    }

    private static String param(String query, String name) {
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            String k = eq >= 0 ? kv.substring(0, eq) : kv;
            if (k.equals(name)) {
                try {
                    return URLDecoder.decode(eq >= 0 ? kv.substring(eq + 1) : "", "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    private static long lparam(String query, String name) {
        try {
            return Long.parseLong(param(query, name).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Resp search(String q) {
        q = q.trim();
        if (q.isEmpty()) return Resp.err(400, "Üres keresés");
        if (q.length() > 200) q = q.substring(0, 200);
        try {
            JSONArray results = YtmSearch.search(q);
            return Resp.json(200, new JSONObject()
                    .put("ok", true)
                    .put("results", results)
                    .toString());
        } catch (Exception e) {
            Log.w(TAG, "Keresés hiba", e);
            return Resp.err(502, "Keresés sikertelen: " + e.getMessage());
        }
    }

    private Resp claimAdmin(Party.User me, String pin) {
        synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            if (pinFails >= 5 && now - pinFailAt < 60_000) {
                return Resp.err(429, "Túl sok próbálkozás, várj egy percet");
            }
            if (!pin.trim().equals(pin(this))) {
                if (now - pinFailAt > 60_000) pinFails = 0;
                pinFails++;
                pinFailAt = now;
                return Resp.err(403, "Rossz PIN");
            }
            pinFails = 0;
        }
        party.makeAdmin(me);
        return Resp.ok();
    }

    // ---------------------------------------------------------------- várólista

    private Resp queueAdd(Party.User me, String query) throws Exception {
        String id = param(query, "id");
        if (!id.matches("[A-Za-z0-9_-]{11}")) return Resp.err(400, "Hibás videó ID");
        Party.Item it = party.add(me, id, param(query, "t"), param(query, "a"),
                param(query, "th"), param(query, "d"));
        if (it == null) return Resp.err(400, "Tele a sor (max " + Party.MAX_QUEUE + ")");

        // Ha épp semmi nem szól (nincs lejátszás, szünetel vagy le van állítva), egyből indítjuk.
        boolean idle;
        try {
            MediaController c = findController();
            int st = c != null && c.getPlaybackState() != null ? c.getPlaybackState().getState()
                    : PlaybackState.STATE_NONE;
            idle = c == null || st == PlaybackState.STATE_NONE || st == PlaybackState.STATE_STOPPED
                    || st == PlaybackState.STATE_PAUSED;
        } catch (SecurityException e) {
            idle = false;
        }
        boolean started = idle && party.current() == null && advance();
        return Resp.json(new JSONObject().put("ok", true).put("started", started)
                .put("position", started ? 0 : party.size()));
    }

    /** A sor következő elemének indítása. Hamis, ha üres a sor. */
    private synchronized boolean advance() {
        Party.Item it = party.pop();
        if (it == null) return false;
        startItem(it);
        return true;
    }

    private Resp startItem(Party.Item it) {
        markStarted();
        return openSong(it.id, it.title, it.artist);
    }

    private void markStarted() {
        lastAdvanceAt = SystemClock.elapsedRealtime();
        currentKey = null;
    }

    /**
     * Másodpercenként: ha a szóló dal a végéhez ér (vagy épp átváltott a YouTube Music
     * saját következőjére), és van valami a sorban, indítjuk a sor következőjét.
     */
    private void tick() {
        MediaController c;
        try {
            c = findController();
        } catch (SecurityException e) {
            return;
        }
        if (c == null) return;
        PlaybackState st = c.getPlaybackState();
        MediaMetadata md = c.getMetadata();
        String key = trackKey(md);
        long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        long pos = position(st);
        int state = st != null ? st.getState() : PlaybackState.STATE_NONE;
        boolean playing = state == PlaybackState.STATE_PLAYING;

        long sinceStart = SystemClock.elapsedRealtime() - lastAdvanceAt;
        if (sinceStart <= SETTLE_MS) {
            // épp indítottunk egy dalt: az előző dal adatai már nem számítanak
            lastKey = null;
            lastDur = 0;
            lastPlaying = false;
            return;
        }

        // "kérte: X" kijelzés: a sorból indított dal felismerése, majd törlése, ha továbbment
        Party.Item cur = party.current();
        if (cur != null) {
            if (currentKey == null) {
                if (looseMatch(cur.title, md)) currentKey = key;
                else if (sinceStart > 20_000) party.clearCurrent();
            } else if (!currentKey.equals(key)) {
                party.clearCurrent();
                currentKey = null;
            }
        }

        if (!party.isEmpty()) {
            boolean nearEnd = playing && dur > 0 && dur - pos < END_MS;
            boolean switchedAtEnd = lastKey != null && key != null && !key.equals(lastKey)
                    && lastDur > 0 && lastDur - lastPos < 6000;
            boolean stoppedAtEnd = lastPlaying && !playing
                    && (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE)
                    && lastDur > 0 && lastDur - lastPos < 6000;
            if (nearEnd || switchedAtEnd || stoppedAtEnd) {
                advance();
                lastKey = null;
                return;
            }
        }

        lastKey = key;
        lastPos = pos;
        lastDur = dur;
        lastPlaying = playing;
    }

    private static String trackKey(MediaMetadata md) {
        if (md == null) return null;
        CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence a = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (t == null) return null;
        return t + "|" + (a != null ? a : "");
    }

    private static boolean looseMatch(String title, MediaMetadata md) {
        if (md == null || title == null || title.isEmpty()) return false;
        CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) return false;
        String a = title.toLowerCase(Locale.ROOT).trim();
        String b = t.toString().toLowerCase(Locale.ROOT).trim();
        return !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    private static long position(PlaybackState st) {
        if (st == null) return 0;
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                    * st.getPlaybackSpeed());
        }
        return Math.max(0, pos);
    }

    // ---------------------------------------------------------------- média

    private interface ControllerAction {
        void run(MediaController c);
    }

    private Resp withController(ControllerAction action) {
        MediaController c;
        try {
            c = findController();
        } catch (SecurityException e) {
            return Resp.err(500, "Nincs értesítés-hozzáférés megadva");
        }
        if (c == null) return Resp.err(500, "Nincs aktív lejátszás a telefonon");
        action.run(c);
        return Resp.ok();
    }

    /** YouTube Music munkamenete, ha van; különben bármelyik aktív lejátszó. */
    private MediaController findController() {
        MediaSessionManager msm = getSystemService(MediaSessionManager.class);
        List<MediaController> list =
                msm.getActiveSessions(new ComponentName(this, MediaListener.class));
        if (list == null || list.isEmpty()) return null;
        for (MediaController c : list) {
            if (YTM.equals(c.getPackageName())) return c;
        }
        return list.get(0);
    }

    /**
     * Lejátszás / szünet. Ha semmi nem szól: előbb a sor jön (ha van benne valami),
     * különben folytatja, ami volt; ha a YouTube Music nem is fut, felébreszti.
     */
    private Resp toggle() {
        MediaController c;
        try {
            c = findController();
        } catch (SecurityException e) {
            return Resp.err(500, "Nincs értesítés-hozzáférés megadva");
        }
        if (c != null && isPlaying(c)) {
            c.getTransportControls().pause();
            return Resp.ok();
        }
        if (party.current() == null && !party.isEmpty() && advance()) return Resp.ok();
        if (c != null) {
            c.getTransportControls().play();
            return Resp.ok();
        }
        if (wakeYtm() != null) return Resp.ok();
        return Resp.err(500, "Nem indult el. Nyisd meg egyszer a YouTube Musicot a telefonon.");
    }

    /**
     * Ha nincs aktív lejátszó, "megnyomja" a telefon play gombját (mint egy Bluetooth
     * fülhallgató). Ezt a rendszer az utoljára használt zenelejátszónak küldi, ami így
     * a háttérben elindul. Visszaadja a YouTube Music munkamenetét, ha előjött.
     */
    private MediaController wakeYtm() {
        AudioManager am = getSystemService(AudioManager.class);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        for (int i = 0; i < 16; i++) {
            SystemClock.sleep(250);
            try {
                MediaController c = findController();
                if (c != null && YTM.equals(c.getPackageName())) return c;
            } catch (SecurityException e) {
                return null;
            }
        }
        ServiceLog.add(this, "A YouTube Music nem indult el a play gombra");
        return null;
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState st = c.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    private void adjustVolume(int direction) {
        AudioManager am = getSystemService(AudioManager.class);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
    }

    /**
     * Dal indítása. Először a háttérben próbálja (a YouTube Music lejátszási munkamenetén
     * keresztül, mint az Android Auto / Asszisztens), és csak ha az nem megy, nyitja meg
     * linkként. Linknél opcionálisan visszavált a Waze-re.
     */
    private Resp openSong(String id, String title, String artist) {
        Uri uri = Uri.parse("https://music.youtube.com/watch?v=" + id);

        MediaController c = null;
        try {
            c = findController();
        } catch (SecurityException ignored) {
        }
        // Nincs aktív lejátszó: felébresztjük a YouTube Musicot, hogy a háttérben
        // tudjuk indítani a dalt (és ne kelljen előhozni az appot).
        if (c == null) c = wakeYtm();
        if (c != null && YTM.equals(c.getPackageName())) {
            PlaybackState st = c.getPlaybackState();
            long actions = st != null ? st.getActions() : 0;
            if ((actions & PlaybackState.ACTION_PLAY_FROM_URI) != 0) {
                c.getTransportControls().playFromUri(uri, null);
                return Resp.json(200, "{\"ok\":true,\"mode\":\"background\"}");
            }
            if (!title.isEmpty() && (actions & PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0) {
                Bundle extras = new Bundle();
                extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE);
                extras.putString(MediaStore.EXTRA_MEDIA_TITLE, title);
                if (!artist.isEmpty()) extras.putString(MediaStore.EXTRA_MEDIA_ARTIST, artist);
                c.getTransportControls().playFromSearch((title + " " + artist).trim(), extras);
                return Resp.json(200, "{\"ok\":true,\"mode\":\"background\"}");
            }
        }

        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setPackage(YTM);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            i.setPackage(null); // nincs YouTube Music -> bármi, ami megnyitja
            try {
                startActivity(i);
            } catch (ActivityNotFoundException e2) {
                return Resp.err(500, "Nincs app, ami megnyitná a linket");
            }
        }
        if (prefs.getBoolean(PREF_BACK_TO_WAZE, false)) {
            mainHandler.postDelayed(this::bringWazeBack, 2500);
        }
        return Resp.json(200, "{\"ok\":true,\"mode\":\"foreground\"}");
    }

    private void bringWazeBack() {
        Intent w = getPackageManager().getLaunchIntentForPackage(WAZE);
        if (w == null) return;
        w.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(w);
        } catch (Exception e) {
            Log.w(TAG, "Waze visszahozása nem sikerült", e);
        }
    }

    /** Mi szól most: cím, előadó, pozíció, hangerő. */
    private JSONObject nowJson() throws Exception {
        MediaController c;
        try {
            c = findController();
        } catch (SecurityException e) {
            return new JSONObject().put("active", false).put("error", "Nincs értesítés-hozzáférés megadva");
        }
        if (c == null) return new JSONObject().put("active", false);

        String title = "";
        String artist = "";
        String art = "";
        long dur = 0;
        MediaMetadata md = c.getMetadata();
        if (md != null) {
            CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
            CharSequence a = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
            if (t != null) title = t.toString();
            if (a != null) artist = a.toString();
            dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            for (String k : new String[]{MediaMetadata.METADATA_KEY_ART_URI,
                    MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
                String u = md.getString(k);
                if (u != null && u.startsWith("http")) {
                    art = u;
                    break;
                }
            }
        }
        AudioManager am = getSystemService(AudioManager.class);
        PlaybackState st = c.getPlaybackState();

        return new JSONObject()
                .put("active", true)
                .put("playing", isPlaying(c))
                .put("title", title)
                .put("artist", artist)
                .put("art", art)
                .put("position", position(st))
                .put("duration", dur)
                .put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("maxVolume", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .put("app", c.getPackageName())
                .put("actions", st != null ? st.getActions() : 0);
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.toString();
    }
}
