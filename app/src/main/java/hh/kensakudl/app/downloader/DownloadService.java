package hh.kensakudl.app.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import hh.kensakudl.app.MainActivity;
import hh.kensakudl.app.model.DownloadItem;
import hh.kensakudl.app.model.DownloadStatus;
/**
 * Foreground service used to keep download work associated with visible Android
 * notifications while the application is not in the foreground.
 *
 * <p>The service observes {@link DownloadManager} and translates queue and item changes
 * into notifications. It does not own the download logic itself.</p>
 */

public class DownloadService extends Service implements DownloadManager.DownloadListener {
    private static final String CHANNEL_ID = "kensaku_downloads";
    private static final int FOREGROUND_SERVICE_ID = 9001;
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        DownloadManager.getInstance(this).addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification foregroundNotification = buildServiceNotification("KensakuDL Service", "Managing active downloads");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FOREGROUND_SERVICE_ID, foregroundNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FOREGROUND_SERVICE_ID, foregroundNotification);
            }
        } catch (Exception ignored) {}
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        DownloadManager.getInstance(this).removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "KensakuDL Downloads",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active download progress");
            notificationManager.createNotificationChannel(channel);
        }
    }
/**
 * Produces a stable notification identifier for a queue item.
 *
 * @param item download item represented by the notification
 * @return notification identifier
 */

    public static int getItemNotificationId(DownloadItem item) {
        return 10000 + Math.abs(item.getId().hashCode() % 80000);
    }
/**
 * Removes the notification associated with a queue item.
 *
 * @param context service or application context
 * @param item item whose notification should be removed
 */

    public static void cancelNotification(Context context, DownloadItem item) {
        if (context == null || item == null) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(getItemNotificationId(item));
        }
    }

    private Notification buildServiceNotification(String title, String content) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private Notification buildItemNotification(DownloadItem item) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        String title = item.getAnimeTitle() + " — Ep " + item.getEpisodeName();
        String info = item.getProgress() + "% • " + item.getSize() + " • " + item.getSpeed();

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(info)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(item.getStatus() == DownloadStatus.DOWNLOADING)
                .setProgress(100, item.getProgress(), false)
                .build();
    }

    @Override
/**
 * Refreshes the corresponding item notification after a download state change.
 *
 * @param item updated download item
 */
    public void onDownloadUpdated(DownloadItem item) {
        int notifId = getItemNotificationId(item);
        if (item.getStatus() == DownloadStatus.DOWNLOADING) {
            notificationManager.notify(notifId, buildItemNotification(item));
        } else if (item.getStatus() == DownloadStatus.COMPLETED) {
            Notification doneNotif = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(item.getAnimeTitle() + " — Ep " + item.getEpisodeName())
                    .setContentText("Download Complete")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setAutoCancel(true)
                    .build();
            notificationManager.notify(notifId, doneNotif);
        } else if (item.getStatus() == DownloadStatus.CANCELLED || item.getStatus() == DownloadStatus.PAUSED) {
            notificationManager.cancel(notifId);
        }
    }

    @Override
/**
 * Refreshes the service-level notification after the queue changes.
 */
    public void onQueueChanged() {
        boolean anyRunning = false;
        for (DownloadItem it : DownloadManager.getInstance(this).getQueue()) {
            if (it.getStatus() == DownloadStatus.DOWNLOADING || it.getStatus() == DownloadStatus.RESOLVING) {
                anyRunning = true;
                break;
            }
        }
        if (!anyRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }
}