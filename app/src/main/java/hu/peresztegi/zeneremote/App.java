package hu.peresztegi.zeneremote;

import android.app.Application;

/**
 * Ha a rendszer lelőtte a folyamatot, és bármi miatt újra elindul (értesítés-figyelő
 * újrakötése, csempe, boot), itt rögtön visszaindítjuk a szervert, ha be volt kapcsolva.
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        if (RemoteService.isEnabled(this) && !RemoteService.running) {
            if (!RemoteService.start(this)) {
                ServiceLog.add(this, "Újraindítás nem sikerült (a rendszer nem engedte)");
            }
        }
    }
}
