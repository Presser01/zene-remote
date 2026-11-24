package hu.peresztegi.zeneremote;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Kezelőfelület: szerver ki/be, elérési linkek másolással, admin PIN, engedélyek, napló. */
public class MainActivity extends Activity {

    // színek (sötét téma, a weboldalhoz illően)
    private static final int BG = 0xFF0F0F0F;
    private static final int CARD = 0xFF1C1C1C;
    private static final int CARD2 = 0xFF2A2A2A;
    private static final int TXT = 0xFFF3F3F3;
    private static final int DIM = 0xFF9A9A9A;
    private static final int ACC = 0xFFFF2D55;
    private static final int OK = 0xFF34C759;
    private static final int WARN = 0xFFFF9F0A;
    private static final String LOCAL_LABEL = "Ezen a telefonon";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    private TextView statusPill;
    private TextView heroTitle;
    private TextView heroSub;
    private TextView mainBtn;
    private LinearLayout linksBox;
    private TextView pinView;
    private LinearLayout permsBox;
    private TextView logView;
    private String lastLinksKey = "";
    private String lastPermsKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // ---- fejléc
        LinearLayout head = row();
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Passenger DJ", 28, TXT, true));
        titles.addView(text("YouTube Music távirányító", 14, DIM, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        statusPill = text("", 13, TXT, true);
        statusPill.setPadding(dp(12), dp(6), dp(12), dp(6));
        head.addView(statusPill);
        root.addView(head);

        // ---- szerver kártya
        LinearLayout hero = card(root);
        heroTitle = text("", 22, TXT, true);
        hero.addView(heroTitle);
        heroSub = text("", 14, DIM, false);
        heroSub.setPadding(0, dp(4), 0, dp(14));
        hero.addView(heroSub);
        mainBtn = button("", true, v -> {
            if (RemoteService.running) RemoteService.stop(this);
            else RemoteService.start(this);
            handler.postDelayed(this::refresh, 500);
        });
        mainBtn.setTextSize(17);
        mainBtn.setPadding(0, dp(14), 0, dp(14));
        hero.addView(mainBtn, new LinearLayout.LayoutParams(-1, -2));

        // ---- elérés
        LinearLayout links = card(root);
        links.addView(header("Elérés"));
        linksBox = new LinearLayout(this);
        linksBox.setOrientation(LinearLayout.VERTICAL);
        links.addView(linksBox);
        links.addView(divider());
        links.addView(small("Helyi név (ékezet nélkül, kisbetűvel)"));
        LinearLayout nameRow = row();
        nameRow.setPadding(0, dp(6), 0, 0);
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setTextColor(TXT);
        nameInput.setHintTextColor(DIM);
        nameInput.setTextSize(16);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameInput.setBackground(rounded(CARD2, 12));
        nameInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        nameInput.setText(RemoteService.prefs(this)
                .getString(RemoteService.PREF_HOSTNAME, RemoteService.DEFAULT_HOSTNAME));
        nameRow.addView(nameInput, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView suffix = text(".local", 15, DIM, false);
        suffix.setPadding(dp(8), 0, dp(10), 0);
        nameRow.addView(suffix);
        nameRow.addView(button("Mentés", false, v -> saveName(nameInput)));
        links.addView(nameRow);

        // ---- admin PIN
        LinearLayout pinCard = card(root);
        pinCard.addView(header("Admin PIN"));
        pinCard.addView(small("Aki ezt beírja a weboldalon (Résztvevők fül), admin lesz. "
                + "Amíg nincs admin, mindenki egyformán irányíthat."));
        LinearLayout pinRow = row();
        pinRow.setPadding(0, dp(10), 0, 0);
        pinView = text("", 30, TXT, true);
        pinView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pinView.setLetterSpacing(0.15f);
        pinRow.addView(pinView, new LinearLayout.LayoutParams(0, -2, 1f));
        pinRow.addView(button("Másolás", false, v -> copy("Admin PIN", RemoteService.pin(this))));
        pinRow.addView(gap());
        pinRow.addView(button("Új", false, v -> {
            RemoteService.newPin(this);
            refresh();
            toast("Új PIN kész. A régi már nem működik.");
        }));
        pinCard.addView(pinRow);

        // ---- engedélyek
        LinearLayout perms = card(root);
        perms.addView(header("Engedélyek és háttérfutás"));
        permsBox = new LinearLayout(this);
        permsBox.setOrientation(LinearLayout.VERTICAL);
        perms.addView(permsBox);
        perms.addView(small("Honor/MagicOS-en a legfontosabb: Alkalmazásindítás → Passenger DJ → "
                + "Kézi kezelés, mind a 3 kapcsolóval. Érdemes a legutóbbi appok között is "
                + "lezárni (húzd lefelé a kártyát → lakat)."));

        // ---- beállítások
        LinearLayout settings = card(root);
        settings.addView(header("Beállítások"));
        Switch waze = new Switch(this);
        waze.setText("Vissza a Waze-re, ha egy dal előhozná a YouTube Musicot");
        waze.setTextColor(TXT);
        waze.setTextSize(15);
        waze.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ACC, 0xFFBDBDBD}));
        waze.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0x80FF2D55, 0xFF555555}));
        waze.setChecked(RemoteService.prefs(this).getBoolean(RemoteService.PREF_BACK_TO_WAZE, false));
        waze.setOnCheckedChangeListener((b, checked) -> RemoteService.prefs(this).edit()
                .putBoolean(RemoteService.PREF_BACK_TO_WAZE, checked).apply());
        settings.addView(waze);

        // ---- napló
        LinearLayout log = card(root);
        log.addView(header("Napló"));
        log.addView(small("Ha a szerver magától leáll, itt látszik, mikor."));
        logView = text("", 12, DIM, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(0, dp(8), 0, 0);
        logView.setTextIsSelectable(true);
        log.addView(logView);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        sv.addView(root);
        // Android 15+: a tartalom a rendszersávok alá is benyúlik, ezért kézzel hagyunk helyet
        sv.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = i.top;
                bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(pad, pad + top, pad, pad + bottom);
            return insets;
        });
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastPermsKey = ""; // a beállításokból visszatérve frissüljön
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    // ---------------------------------------------------------------- frissítés

    private void refresh() {
        boolean running = RemoteService.running;

        statusPill.setText(running ? "● Fut" : "○ Áll");
        statusPill.setTextColor(running ? OK : DIM);
        statusPill.setBackground(rounded(running ? 0x2634C759 : CARD, 20));

        heroTitle.setText(running ? "A szerver fut" : "A szerver áll");
        String sub = running
                ? "Az appot nyugodtan bezárhatod, a háttérben tovább fut."
                : "Indítsd el, és utána a telefon újraindítása után is magától elindul.";
        if (!running && RemoteService.lastError != null) sub = "Hiba: " + RemoteService.lastError;
        heroSub.setText(sub);
        mainBtn.setText(running ? "Leállítás" : "Indítás");
        mainBtn.setBackground(ripple(running ? CARD2 : ACC, 16));

        String pin = RemoteService.pin(this);
        pinView.setText(pin.substring(0, 3) + " " + pin.substring(3));

        refreshLinks(running);
        refreshPerms();
        String log = ServiceLog.get(this);
        logView.setText(log.isEmpty() ? "Még nincs bejegyzés." : log);
    }

    private void refreshLinks(boolean running) {
        List<String[]> items = new ArrayList<>();
        if (running) {
            // A saját telefonon a .local név nem mindig működik (pl. ha fut a Tailscale/VPN),
            // a localhost viszont mindig.
            items.add(new String[]{LOCAL_LABEL, "http://localhost:" + RemoteService.PORT});
            if (RemoteService.hostName != null) {
                items.add(new String[]{"Helyi név · más eszközről, Wi-Fi és hotspot",
                        "http://" + RemoteService.hostName + ":" + RemoteService.PORT});
            }
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address) {
                            items.add(new String[]{ifaceLabel(ni.getName()),
                                    "http://" + a.getHostAddress() + ":" + RemoteService.PORT});
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        StringBuilder key = new StringBuilder(String.valueOf(running));
        for (String[] it : items) key.append(it[0]).append(it[1]);
        if (key.toString().equals(lastLinksKey)) return; // ne építsük újra feleslegesen
        lastLinksKey = key.toString();

        linksBox.removeAllViews();
        if (!running) {
            linksBox.addView(small("Indítsd el a szervert, és itt megjelennek a linkek."));
            return;
        }
        if (items.isEmpty()) {
            linksBox.addView(small("Nincs hálózat. Kapcsold be a Wi-Fit vagy a hotspotot."));
            return;
        }
        for (String[] it : items) {
            LinearLayout r = row();
            r.setPadding(0, dp(8), 0, dp(8));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(small(it[0]));
            TextView url = text(it[1].replace("http://", ""), 17, TXT, true);
            url.setSingleLine(true);
            url.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(url);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            if (it[0].equals(LOCAL_LABEL)) {
                r.addView(button("Megnyitás", false, v -> openUrl(it[1])));
                r.addView(gap());
            }
            r.addView(button("Másolás", false, v -> copy(it[0], it[1])));
            linksBox.addView(r);
        }
    }

    private static String ifaceLabel(String name) {
        if (name.equals("wlan0")) return "Wi-Fi IP";
        if (name.startsWith("tun")) return "Tailscale IP";
        if (name.startsWith("ap") || name.startsWith("swlan") || name.equals("wlan1")
                || name.startsWith("wigig")) return "Hotspot IP";
        if (name.startsWith("rmnet") || name.startsWith("ccmni")) return "Mobilnet IP (kívülről nem elérhető)";
        return "IP (" + name + ")";
    }

    private void refreshPerms() {
        boolean notif = hasNotificationAccess();
        boolean overlay = Settings.canDrawOverlays(this);
        boolean battery = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        String key = "" + notif + overlay + battery;
        if (key.equals(lastPermsKey)) return;
        lastPermsKey = key;

        permsBox.removeAllViews();
        permRow(notif ? 1 : 0, "Értesítés-hozzáférés", "Enélkül nem tudja vezérelni a YouTube Musicot",
                () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        permRow(overlay ? 1 : 0, "Más appok feletti megjelenítés", "A háttérből indított dalokhoz",
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        permRow(battery ? 1 : 0, "Akkumulátor-optimalizálás kikapcsolása", "Hogy a rendszer ne altassa el",
                () -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()))));
        String m = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        if (m.contains("honor") || m.contains("huawei")) {
            permRow(-1, "Alkalmazásindítás: Kézi kezelés", "Honor: mind a 3 kapcsoló legyen bekapcsolva",
                    this::openLaunchManager);
        }
    }

    /** state: 1 = rendben, 0 = hiányzik, -1 = nem ellenőrizhető (csak kézzel). */
    private void permRow(int state, String title, String sub, Runnable action) {
        LinearLayout r = row();
        r.setPadding(dp(4), dp(10), dp(4), dp(10));
        r.setBackground(ripple(Color.TRANSPARENT, 12));
        r.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Exception e) {
                toast("Nem sikerült megnyitni a beállítást");
            }
        });
        TextView icon = text(state == 1 ? "✓" : state == 0 ? "!" : "i", 15, Color.BLACK, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(state == 1 ? OK : state == 0 ? WARN : DIM, 14));
        r.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        col.addView(text(title, 15, TXT, false));
        col.addView(small(sub));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(text("›", 22, DIM, false));
        permsBox.addView(r);
    }

    /** Honor / Huawei "Alkalmazásindítás" képernyő; ha nincs meg, az app saját beállításai. */
    private void openLaunchManager() {
        String[][] candidates = {
                {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
        };
        for (String[] c : candidates) {
            try {
                startActivity(new Intent().setComponent(new ComponentName(c[0], c[1])));
                return;
            } catch (Exception ignored) {
                // következő próbálkozás
            }
        }
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
        toast("Akkumulátor → Alkalmazásindítás → Passenger DJ → Kézi kezelés");
    }

    // ---------------------------------------------------------------- műveletek

    private void saveName(EditText nameInput) {
        String n = nameInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (n.endsWith(".local")) n = n.substring(0, n.length() - ".local".length());
        if (!n.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")) {
            toast("Csak kisbetű, szám és kötőjel (ékezet nélkül)");
            return;
        }
        nameInput.setText(n);
        nameInput.clearFocus();
        RemoteService.prefs(this).edit().putString(RemoteService.PREF_HOSTNAME, n).apply();
        if (RemoteService.running) { // új név -> szerver újraindítása
            stopService(new Intent(this, RemoteService.class));
            handler.postDelayed(() -> RemoteService.start(this), 800);
        }
        toast("Mentve: " + n + ".local");
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Nincs böngésző, ami megnyitná");
        }
    }

    private void copy(String label, String value) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText(label, value));
        if (Build.VERSION.SDK_INT < 33) toast("Kimásolva: " + value); // 13+ saját jelzést ad
    }

    private boolean hasNotificationAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        String me = new ComponentName(this, MediaListener.class).flattenToString();
        for (String s : flat.split(":")) {
            if (me.equals(s)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- UI segédek

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, 20));
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private TextView header(String s) {
        TextView t = text(s.toUpperCase(new Locale("hu")), 12, DIM, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView small(String s) {
        TextView t = text(s, 13, DIM, false);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView button(String s, boolean primary, View.OnClickListener l) {
        TextView b = text(s, 14, primary ? Color.WHITE : TXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        b.setBackground(ripple(primary ? ACC : CARD2, primary ? 16 : 18));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        return v;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFF2C2C2C);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private RippleDrawable ripple(int color, int radiusDp) {
        GradientDrawable mask = rounded(Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(color, radiusDp), mask);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
