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
import hh.kensakudl.app.util.FormatUtil;

/**
 * Keeps downloads represented by a foreground service and publishes per-item notifications from DownloadManager updates.
 */
public class DownloadService extends Service implements DownloadManager.DownloadListener {
    private static final String CHANNEL_ID = "kensaku_downloads";
    private static final int FOREGROUND_SERVICE_ID = 9001;
    private NotificationManager notificationManager;

    /**
     * Initializes the notification channel and subscribes to download-manager updates before foreground work begins.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        DownloadManager.getInstance(this).addListener(this);
    }

    /**
     * Promotes the service to foreground operation and keeps it alive while downloads need background execution.
     * 
     * @param intent start request, if supplied
     * @param flags system restart flags
     * @param startId identifier for this service start
     * @return service restart policy
     */
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

    /**
     * Unregisters the service listener before releasing service resources.
     */
    @Override
    public void onDestroy() {
        DownloadManager.getInstance(this).removeListener(this);
        super.onDestroy();
    }

    /**
     * This service is started rather than bound.
     * 
     * @param intent binding request
     * @return {@code null} because no binding interface is exposed
     */
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
     * Derives the notification identifier used for a specific queue item.
     * 
     * @param item download item represented by the notification
     * @return stable notification identifier
     */
    public static int getItemNotificationId(DownloadItem item) {
        return 10000 + Math.abs(item.getId().hashCode() % 80000);
    }

    /**
     * Removes the notification associated with a queue item.
     * 
     * @param context context used to obtain NotificationManager
     * @param item item whose notification should be cancelled
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

        String title = getDisplayEpisodeName(item);
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

    private String getDisplayEpisodeName(DownloadItem item) {
        String track = "dub".equalsIgnoreCase(item.getTrack()) ? "Dub" : "Sub";
        return track
                + " — "
                + item.getAnimeTitle()
                + " - Episode "
                + FormatUtil.safeEpisodeNumber(item.getEpisodeName());
    }

    /**
     * Refreshes the per-item foreground notification to reflect the latest download state.
     * 
     * @param item updated download state
     */
    @Override
    public void onDownloadUpdated(DownloadItem item) {
        int notifId = getItemNotificationId(item);
        if (item.getStatus() == DownloadStatus.DOWNLOADING) {
            notificationManager.notify(notifId, buildItemNotification(item));
        } else if (item.getStatus() == DownloadStatus.COMPLETED) {
            Notification doneNotif = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getDisplayEpisodeName(item))
                    .setContentText("Download Complete")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setAutoCancel(true)
                    .build();
            notificationManager.notify(notifId, doneNotif);
        } else if (item.getStatus() == DownloadStatus.CANCELLED || item.getStatus() == DownloadStatus.PAUSED) {
            notificationManager.cancel(notifId);
        }
    }

    /**
     * Updates service-level notification state when the queue changes.
     */
    @Override
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