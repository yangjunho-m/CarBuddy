package app.carbuddy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class CarBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !CharacterArt.preferences(context).getBoolean(CharacterArt.PICKED, false)) return;
        try {
            context.startForegroundService(new Intent(context, FloatingBuddyService.class));
        } catch (RuntimeException e) {
            Log.w("CarBuddy", "Background startup is unavailable", e);
        }
    }
}
