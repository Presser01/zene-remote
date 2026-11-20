package hu.peresztegi.zeneremote;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Egyszerű kezelőfelület: jogosultságok, szerver indítás/leállítás, elérési címek. */
public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView addrView;
    private Button toggleBtn;
    private Button notifBtn;
    private Button overlayBtn;
    private Button batteryBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("🎵 Zene Remote");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, dp(16), 0, dp(8));
        root.addView(statusView);

        addrView = new TextView(this);
        addrView.setTextSize(18);
        addrView.setTextIsSelectable(true);
        addrView.setPadding(0, 0, 0, dp(16));
        root.addView(addrView);

        toggleBtn = addButton(root, "", v -> {
            Intent svc = new Intent(this, RemoteService.class);
            if (RemoteService.running) {
                stopService(svc);
            } else {
                startForegroundService(svc);
            }
            handler.postDelayed(this::refresh, 600);
        });

        notifBtn = addButton(root, "", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        overlayBtn = addButton(root, "", v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));

        batteryBtn = addButton(root, "", v ->
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()))));

        CheckBox waze = new CheckBox(this);
        waze.setText("Ha egy dal mégis előhozná a YouTube Musicot, utána váltson vissza a Waze-re");
        waze.setPadding(0, dp(12), 0, 0);
        waze.setChecked(getSharedPreferences(RemoteService.PREFS, MODE_PRIVATE)
                .getBoolean(RemoteService.PREF_BACK_TO_WAZE, false));
        waze.setOnCheckedChangeListener((b, checked) ->
                getSharedPreferences(RemoteService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(RemoteService.PREF_BACK_TO_WAZE, checked).apply());
        root.addView(waze);

        TextView hint = new TextView(this);
        hint.setText("\nHonor/MagicOS: Beállítások → Akkumulátor → Alkalmazásindítás → "
                + "Zene Remote → Kézi kezelés, és mindhárom kapcsoló legyen bekapcsolva. "
                + "Enélkül a rendszer leállíthatja a szervert.");
        hint.setTextSize(14);
        root.addView(hint);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean running = RemoteService.running;
        boolean notif = hasNotificationAccess();
        boolean overlay = Settings.canDrawOverlays(this);
        boolean battery = getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(getPackageName());

        String st = running ? "✅ Szerver fut" : "⏹ Szerver áll";
        if (!running && RemoteService.lastError != null) {
            st += " (hiba: " + RemoteService.lastError + ")";
        }
        statusView.setText(st);
        toggleBtn.setText(running ? "Szerver leállítása" : "Szerver indítása");

        notifBtn.setText((notif ? "✅ " : "❗ ") + "Értesítés-hozzáférés (zenevezérléshez)");
        overlayBtn.setText((overlay ? "✅ " : "❗ ") + "Más appok feletti megjelenítés (link indításhoz)");
        batteryBtn.setText((battery ? "✅ " : "❗ ") + "Akkumulátor-optimalizálás kikapcsolása");

        addrView.setText(running ? addresses() : "");
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

    /** IPv4 címek interfészenként: wlan0 = otthoni Wi-Fi, tun0 = Tailscale (100.x). */
    private String addresses() {
        StringBuilder b = new StringBuilder("Linda ezt nyissa meg:\n");
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        b.append("http://").append(a.getHostAddress()).append(':')
                                .append(RemoteService.PORT).append("   (").append(ni.getName()).append(")\n");
                    }
                }
            }
        } catch (Exception e) {
            b.append("Nem sikerült lekérni a címeket");
        }
        return b.toString();
    }

    private Button addButton(LinearLayout root, String text, android.view.View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(btn, lp);
        return btn;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
