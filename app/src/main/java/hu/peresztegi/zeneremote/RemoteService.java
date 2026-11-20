package hu.peresztegi.zeneremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Előtérben futó szolgáltatás egy minimális HTTP szerverrel a PORT-on.
 *
 * Végpontok:
 *   GET /            -> távirányító oldal (assets/index.html)
 *   GET /next        -> következő szám
 *   GET /prev        -> előző szám
 *   GET /toggle      -> lejátszás / szünet
 *   GET /volup       -> hangosabb
 *   GET /voldown     -> halkabb
 *   GET /play/{id}   -> adott YouTube (Music) videó ID indítása
 *   GET /status      -> mi szól most (JSON)
 *   GET /search?q=   -> YouTube Music keresés, dalok (JSON)
 */
public class RemoteService extends Service {

    public static final int PORT = 1821;
    public static volatile boolean running = false;
    public static volatile String lastError = null;

    private static final String TAG = "ZeneRemote";
    private static final String CHANNEL_ID = "remote";
    private static final String YTM = "com.google.android.apps.youtube.music";

    private volatile ServerSocket server;
    private ExecutorService pool;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private byte[] indexHtml;

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();
        acquireLocks();
        indexHtml = loadAsset("index.html");
        startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
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

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Zene Remote fut")
                .setContentText("Port: " + PORT)
                .setContentIntent(open)
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZeneRemote:cpu");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ZeneRemote:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
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
        pool = Executors.newFixedThreadPool(4);
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
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                // fejlécek átugrása
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
                resp = route(path, query);
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

    private Resp route(String path, String query) {
        switch (path) {
            case "/search":
                return search(param(query, "q"));
            case "/":
            case "/index.html":
                return new Resp(200, "text/html; charset=utf-8", indexHtml);
            case "/next":
                return withController(c -> c.getTransportControls().skipToNext());
            case "/prev":
                return withController(c -> c.getTransportControls().skipToPrevious());
            case "/toggle":
                return withController(c -> {
                    if (isPlaying(c)) c.getTransportControls().pause();
                    else c.getTransportControls().play();
                });
            case "/volup":
                adjustVolume(AudioManager.ADJUST_RAISE);
                return Resp.ok();
            case "/voldown":
                adjustVolume(AudioManager.ADJUST_LOWER);
                return Resp.ok();
            case "/status":
                return status();
            default:
                if (path.startsWith("/play/")) {
                    String id = path.substring("/play/".length());
                    if (!id.matches("[A-Za-z0-9_-]{11}")) return Resp.err(400, "Hibás videó ID");
                    return openSong(id);
                }
                return Resp.err(404, "Nincs ilyen");
        }
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

    private static boolean isPlaying(MediaController c) {
        PlaybackState st = c.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    private void adjustVolume(int direction) {
        AudioManager am = getSystemService(AudioManager.class);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
    }

    private Resp openSong(String id) {
        Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://music.youtube.com/watch?v=" + id));
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
        return Resp.ok();
    }

    private Resp status() {
        MediaController c;
        try {
            c = findController();
        } catch (SecurityException e) {
            return Resp.err(500, "Nincs értesítés-hozzáférés megadva");
        }
        if (c == null) return Resp.json(200, "{\"ok\":true,\"active\":false}");

        String title = "";
        String artist = "";
        MediaMetadata md = c.getMetadata();
        if (md != null) {
            CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
            CharSequence a = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
            if (t != null) title = t.toString();
            if (a != null) artist = a.toString();
        }
        AudioManager am = getSystemService(AudioManager.class);
        int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        return Resp.json(200, "{\"ok\":true,\"active\":true"
                + ",\"playing\":" + isPlaying(c)
                + ",\"title\":\"" + esc(title) + "\""
                + ",\"artist\":\"" + esc(artist) + "\""
                + ",\"volume\":" + vol + ",\"maxVolume\":" + max + "}");
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
