package hu.peresztegi.zeneremote;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Kis napló a szerver indulásairól / leállásairól, hogy látszódjon, ha a rendszer
 * (pl. Honor energiakezelés) lelövi a háttérben. Az appban a "Napló" kártyán jelenik meg.
 */
final class ServiceLog {

    private static final String PREF_LOG = "serviceLog";
    private static final String PREF_ALIVE = "aliveAt";      // utolsó életjel (ms)
    private static final String PREF_RUNNING = "wasRunning"; // tiszta leállításkor false
    private static final int MAX_LINES = 15;

    private ServiceLog() {
    }

    static synchronized void add(Context c, String msg) {
        SharedPreferences p = RemoteService.prefs(c);
        String line = new SimpleDateFormat("MM.dd. HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + msg;
        String old = p.getString(PREF_LOG, "");
        String all = old.isEmpty() ? line : line + "\n" + old;
        String[] lines = all.split("\n");
        if (lines.length > MAX_LINES) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < MAX_LINES; i++) {
                if (i > 0) b.append('\n');
                b.append(lines[i]);
            }
            all = b.toString();
        }
        p.edit().putString(PREF_LOG, all).apply();
    }

    static String get(Context c) {
        return RemoteService.prefs(c).getString(PREF_LOG, "");
    }

    /** Szolgáltatás indulásakor: ha az előző futás nem állt le rendesen, azt is naplózza. */
    static void started(Context c) {
        SharedPreferences p = RemoteService.prefs(c);
        if (p.getBoolean(PREF_RUNNING, false)) {
            long alive = p.getLong(PREF_ALIVE, 0);
            String when = alive > 0
                    ? " (utolsó életjel: " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(alive)) + ")"
                    : "";
            add(c, "⚠️ Az előző futást a rendszer leállította" + when);
        }
        p.edit().putBoolean(PREF_RUNNING, true).putLong(PREF_ALIVE, System.currentTimeMillis()).apply();
        add(c, "▶ Szerver elindult");
    }

    static void alive(Context c) {
        RemoteService.prefs(c).edit().putLong(PREF_ALIVE, System.currentTimeMillis()).apply();
    }

    static void stopped(Context c) {
        RemoteService.prefs(c).edit().putBoolean(PREF_RUNNING, false).apply();
        add(c, "⏹ Szerver leállt");
    }
}
