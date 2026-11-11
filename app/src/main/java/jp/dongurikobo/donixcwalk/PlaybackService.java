package jp.dongurikobo.donixcwalk;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Foreground process protection for the WebView-based audio player. */
public final class PlaybackService extends Service {
    static volatile Notification currentNotification;
    private PowerManager.WakeLock playbackWakeLock;
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("donix_playback", "DONIX C-WALK 再生", NotificationManager.IMPORTANCE_LOW));
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = currentNotification;
        if (notification == null) {
            Notification.Builder builder = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("DONIX C-WALK")
                    .setContentText("音楽を再生中")
                    .setOngoing(true);
            if (Build.VERSION.SDK_INT >= 26) builder.setChannelId("donix_playback");
            notification = builder.build();
        }
        startForeground(MainActivity.MEDIA_NOTIFICATION_ID, notification);
        // Keep the CPU running while the WebView audio player is active and screen is off.
        if (playbackWakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                playbackWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DONIX:CWalkPlayback");
                playbackWakeLock.setReferenceCounted(false);
            }
        }
        if (playbackWakeLock != null && !playbackWakeLock.isHeld()) playbackWakeLock.acquire();
        return START_STICKY;
    }
    @Override public void onDestroy() {
        if (playbackWakeLock != null && playbackWakeLock.isHeld()) playbackWakeLock.release();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
