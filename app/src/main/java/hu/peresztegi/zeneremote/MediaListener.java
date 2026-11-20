package hu.peresztegi.zeneremote;

import android.service.notification.NotificationListenerService;

/**
 * Üres értesítés-figyelő. Csak a jogosultság miatt létezik: ha a felhasználó
 * engedélyezi, a MediaSessionManager visszaadja a többi app (YouTube Music)
 * lejátszási munkamenetét, amit így vezérelni tudunk.
 */
public class MediaListener extends NotificationListenerService {
}
