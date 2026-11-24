package hu.peresztegi.zeneremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Telefon-újraindítás vagy app-frissítés után visszaindítja a szervert, ha be volt kapcsolva. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            if (RemoteService.isEnabled(context)) RemoteService.start(context);
        }
    }
}
