package hu.peresztegi.zeneremote;

import android.service.notification.NotificationListenerService;

/**
 * Értesítés-figyelő. Elsősorban a jogosultság miatt létezik: ha a felhasználó
 * engedélyezi, a MediaSessionManager visszaadja a többi app (YouTube Music)
 * lejátszási munkamenetét, amit így vezérelni tudunk.
 *
 * Bónusz: a rendszer magától újraköti ezt a szolgáltatást (indításkor, vagy ha az
 * appot leállította), így innen visszaindítjuk a szervert, ha be volt kapcsolva.
 */
public class MediaListener extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        if (RemoteService.isEnabled(this) && !RemoteService.running) {
            RemoteService.start(this);
        }
    }
}
