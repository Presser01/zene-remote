package hu.peresztegi.zeneremote;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Gyorsbeállítás-csempe: a szerver ki/bekapcsolása az app megnyitása nélkül. */
public class ServerTile extends TileService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        if (RemoteService.running) {
            RemoteService.stop(this);
        } else if (!RemoteService.start(this)) {
            openApp(); // a rendszer nem engedte háttérből -> megnyitjuk az appot
        }
        handler.postDelayed(this::update, 800);
        handler.postDelayed(this::update, 2000);
    }

    @SuppressWarnings("deprecation")
    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = RemoteService.running;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Zene Remote");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(on ? "Fut" : "Áll");
        t.updateTile();
    }
}
