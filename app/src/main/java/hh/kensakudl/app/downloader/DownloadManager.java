package hh.kensakudl.app.downloader;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.google.gson.Gson;
import hh.kensakudl.app.model.DownloadItem;
import hh.kensakudl.app.model.DownloadStatus;
import hh.kensakudl.app.model.EpisodeItem;
import hh.kensakudl.app.network.ApiClient;
import hh.kensakudl.app.util.FileUtil;
import hh.kensakudl.app.util.FormatUtil;
import okhttp3.Request;
import okhttp3.Response;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * Coordinates KensakuDL's download queue, persistence, execution, and disk state.
 *
 * <p>Downloads are executed off the main thread, while queue and progress notifications
 * are marshalled back to listeners on the main thread. Completed files on disk are also
 * reconciled with the persisted queue so the UI can recover after process restarts.</p>
 */

public class DownloadManager {
    private static final String TAG = "DownloadManager";
    private static final String PREFS_NAME = "kensaku_download_queue";
    private static final String KEY_QUEUE_DATA = "saved_queue_items";

    public interface DownloadListener {
        void onDownloadUpdated(DownloadItem item);
        void onQueueChanged();
    }

    private static DownloadManager instance;
    private final Context context;
    private final List<DownloadItem> queueList = new ArrayList<>();
    private final ConcurrentHashMap<String, DownloadItem> queueMap = new ConcurrentHashMap<>();
    private final List<DownloadListener> listeners = new ArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    private DownloadManager(Context context) {
        this.context = context.getApplicationContext();
        loadQueueFromPrefs();
/**
 * Reconciles persisted queue entries with the files currently present on disk.
 *
 * <p>This also removes stale entries and empty download directories left by interrupted
 * or manually deleted downloads.</p>
 */
        syncWithDiskStorage();
    }
/**
 * Returns the process-wide download manager instance.
 *
 * @param context context used only to initialize application-scoped storage
 * @return shared download manager
 */

    public static synchronized DownloadManager getInstance(Context context) {
        if (instance == null) {
            instance = new DownloadManager(context);
        }
        return instance;
    }

    public synchronized void addListener(DownloadListener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public synchronized void removeListener(DownloadListener listener) {
        listeners.remove(listener);
    }

    private void notifyUpdated(DownloadItem item) {
        saveQueueToPrefs();
        mainHandler.post(() -> {
            for (DownloadListener l : listeners) l.onDownloadUpdated(item);
        });
    }

    private void notifyQueueChanged() {
        saveQueueToPrefs();
        mainHandler.post(() -> {
            for (DownloadListener l : listeners) l.onQueueChanged();
        });
    }
/**
 * Returns a snapshot of the current download queue.
 *
 * @return queue contents in their current order
 */

    public synchronized List<DownloadItem> getQueue() {
        return new ArrayList<>(queueList);
    }

    
    public synchronized void syncWithDiskStorage() {
        try {
            boolean changed = false;

            
            Iterator<DownloadItem> queueIterator = queueList.iterator();
            while (queueIterator.hasNext()) {
                DownloadItem item = queueIterator.next();
                if (item.getAnimeTitle() == null || item.getAnimeTitle().trim().isEmpty()) {
                    queueMap.remove(item.getId());
                    queueIterator.remove();
                    changed = true;
                    continue;
                }

                if (item.getStatus() == DownloadStatus.COMPLETED) {
                    File file = (item.getOutputPath() != null && !item.getOutputPath().isEmpty())
                            ? new File(item.getOutputPath())
                            : FileUtil.getFinalMp4File(item.getAnimeTitle(), item.getEpisodeName());

                    if (!file.exists() || file.length() == 0) {
                        queueMap.remove(item.getId());
                        queueIterator.remove();
                        changed = true;
                    }
                }
            }

            
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File kensakuDir = new File(downloadDir, "KensakuDL");
            if (!kensakuDir.exists() || !kensakuDir.isDirectory()) {
                if (changed) notifyQueueChanged();
                return;
            }

            File[] animeFolders = kensakuDir.listFiles();
            if (animeFolders == null) {
                if (changed) notifyQueueChanged();
                return;
            }

            for (File folder : animeFolders) {
                if (!folder.isDirectory()) continue;
                String animeTitle = folder.getName().trim();

                
                if (animeTitle.isEmpty()) {
                    folder.delete();
                    continue;
                }

                File[] files = folder.listFiles();

                
                if (files == null || files.length == 0) {
                    folder.delete();
                    continue;
                }

                
                int validVideos = 0;
                Arrays.sort(files, Comparator.comparing(File::getName));

                for (File file : files) {
                    String fileName = file.getName();
                    if (fileName.endsWith(".mp4") && !fileName.endsWith(".part.mp4") && file.length() > 0) {
                        validVideos++;
                        String episodeName = normalizeEpisodeName(extractEpisodeName(fileName));
                        DownloadItem existing = findByLogicalEpisode(animeTitle, episodeName);

                        if (existing == null) {
                            String id = animeTitle + "::" + episodeName + "::" + file.getAbsolutePath();
                            DownloadItem item = new DownloadItem(id, animeTitle, episodeName, episodeName, "");
                            item.setStatus(DownloadStatus.COMPLETED);
                            item.setProgress(100);
                            item.setSize(FormatUtil.formatSize(file.length()));
                            item.setEta("00:00");
                            item.setOutputPath(file.getAbsolutePath());

                            queueList.add(item);
                            queueMap.put(id, item);
                            changed = true;
                        } else {
                            existing.setOutputPath(file.getAbsolutePath());
                            existing.setSize(FormatUtil.formatSize(file.length()));

                            if (existing.getStatus() != DownloadStatus.COMPLETED
                                    || existing.getProgress() != 100) {
                                existing.setStatus(DownloadStatus.COMPLETED);
                                existing.setProgress(100);
                                existing.setEta("00:00");
                                changed = true;
                            }
                        }
                    }
                }

                
                if (validVideos == 0) {
                    boolean hasActivePart = false;
                    for (File f : files) {
                        if (f.getName().endsWith(".part.mp4") || f.getName().endsWith(".part.ts")) {
                            hasActivePart = true;
                            break;
                        }
                    }
                    if (!hasActivePart) {
                        for (File f : files) f.delete();
                        folder.delete();
                    }
                }
            }

            if (changed) {
                notifyQueueChanged();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error syncing with disk storage: " + e.getMessage());
        }
    }

    private String extractEpisodeName(String fileName) {
        String clean = fileName.replace(".mp4", "");
        if (clean.contains("_Episode_")) {
            String[] parts = clean.split("_Episode_");
            if (parts.length > 1) {
                return parts[1];
            }
        }
        Matcher m = Pattern.compile("\\b(\\d+)\\b").matcher(clean);
        if (m.find()) {
            return m.group(1);
        }
        return clean;
    }

    private synchronized void saveQueueToPrefs() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = gson.toJson(queueList);
            prefs.edit().putString(KEY_QUEUE_DATA, json).apply();
        } catch (Exception ignored) {}
    }

    private synchronized void loadQueueFromPrefs() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_QUEUE_DATA, null);
            if (json != null) {
                DownloadItem[] saved = gson.fromJson(json, DownloadItem[].class);
                if (saved != null) {
                    Map<String, DownloadItem> unique = new LinkedHashMap<>();

                    for (DownloadItem item : saved) {
                        if (item == null
                                || item.getAnimeTitle() == null
                                || item.getAnimeTitle().trim().isEmpty()) {
                            continue;
                        }

                        if (item.getStatus() == DownloadStatus.DOWNLOADING
                                || item.getStatus() == DownloadStatus.RESOLVING) {
                            item.setStatus(DownloadStatus.PAUSED);
                        }

                        DownloadItem canonical = canonicalizeDownloadItem(item);
                        String key = buildLogicalEpisodeKey(
                                canonical.getAnimeTitle(),
                                canonical.getEpisodeName()
                        );

                        DownloadItem existing = unique.get(key);
                        if (existing == null || shouldReplaceDuplicate(existing, canonical)) {
                            unique.put(key, canonical);
                        }
                    }

                    queueList.clear();
                    queueMap.clear();

                    for (DownloadItem item : unique.values()) {
                        queueList.add(item);
                        queueMap.put(item.getId(), item);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load saved download queue", e);
        }
    }

    private DownloadItem canonicalizeDownloadItem(DownloadItem source) {
        String episodeName = normalizeEpisodeName(source.getEpisodeName());
        String id = source.getAnimeTitle() + "::" + episodeName + "::" + source.getEpisodeLink();

        DownloadItem item = new DownloadItem(
                id,
                source.getAnimeTitle(),
                episodeName,
                source.getEpisodeSlug(),
                source.getEpisodeLink()
        );

        item.setStatus(source.getStatus());
        item.setProgress(source.getProgress());
        item.setSpeed(source.getSpeed());
        item.setSize(source.getSize());
        item.setEta(source.getEta());
        item.setError(source.getError());
        item.setOutputPath(source.getOutputPath());
        item.setDownloadedSegments(source.getDownloadedSegments());

        return item;
    }

    private boolean shouldReplaceDuplicate(DownloadItem existing, DownloadItem candidate) {
        if (existing.getStatus() != DownloadStatus.COMPLETED
                && candidate.getStatus() == DownloadStatus.COMPLETED) {
            return true;
        }

        if (existing.getStatus() == DownloadStatus.COMPLETED
                && candidate.getStatus() != DownloadStatus.COMPLETED) {
            return false;
        }

        boolean existingFile = existing.getOutputPath() != null
                && !existing.getOutputPath().isEmpty()
                && new File(existing.getOutputPath()).exists();

        boolean candidateFile = candidate.getOutputPath() != null
                && !candidate.getOutputPath().isEmpty()
                && new File(candidate.getOutputPath()).exists();

        if (!existingFile && candidateFile) return true;
        if (existingFile && !candidateFile) return false;

        return candidate.getProgress() > existing.getProgress();
    }

    private String buildLogicalEpisodeKey(String animeTitle, String episodeName) {
        String title = animeTitle == null ? "" : animeTitle.trim().toLowerCase();
        return title + "::" + normalizeEpisodeName(episodeName);
    }

    private String normalizeEpisodeName(String episodeName) {
        if (episodeName == null) return "";

        Matcher matcher = Pattern.compile("\\b(\\d+)\\b").matcher(episodeName.trim());
        if (matcher.find()) {
            try {
                return String.valueOf(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {}
        }

        return episodeName.trim();
    }

    private DownloadItem findByLogicalEpisode(String animeTitle, String episodeName) {
        String key = buildLogicalEpisodeKey(animeTitle, episodeName);

        for (DownloadItem item : queueList) {
            if (buildLogicalEpisodeKey(item.getAnimeTitle(), item.getEpisodeName()).equals(key)) {
                return item;
            }
        }

        return null;
    }
/**
 * Checks whether at least one queued item is actively downloading.
 *
 * @return {@code true} when a download is currently running
 */

    public synchronized boolean isAnyDownloadRunning() {
        for (DownloadItem item : queueList) {
            if (item.getStatus() == DownloadStatus.DOWNLOADING || item.getStatus() == DownloadStatus.RESOLVING) {
                return true;
            }
        }
        return false;
    }
/**
 * Adds a collection of episodes to the queue while avoiding duplicate logical episodes.
 *
 * @param animeTitle series title used for storage and queue grouping
 * @param episodes episodes to enqueue
 */

    public synchronized void enqueueBatch(String animeTitle, List<EpisodeItem> episodes) {
        if (episodes == null || episodes.isEmpty()) return;
        if (animeTitle == null || animeTitle.trim().isEmpty()) return;

        String firstIdToStart = null;

        for (EpisodeItem ep : episodes) {
            if (ep == null) continue;

            String episodeName = normalizeEpisodeName(ep.getName());
            if (findByLogicalEpisode(animeTitle, episodeName) != null) {
                continue;
            }

            String id = animeTitle + "::" + episodeName + "::" + ep.getLink();
            DownloadItem item = new DownloadItem(
                    id,
                    animeTitle,
                    episodeName,
                    ep.getSlug(),
                    ep.getLink()
            );

            queueList.add(item);
            queueMap.put(id, item);

            if (firstIdToStart == null) {
                firstIdToStart = id;
            }
        }

        notifyQueueChanged();

        if (!isAnyDownloadRunning() && firstIdToStart != null) {
/**
 * Starts a queued download by its queue identifier.
 *
 * @param id download identifier
 */
            start(firstIdToStart);
        }
    }

    public void start(String id) {
        DownloadItem item = queueMap.get(id);
        if (item == null) return;

        try {
            Intent serviceIntent = new Intent(context, DownloadService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
        } catch (Exception ignored) {}

        item.setStatus(DownloadStatus.RESOLVING);
        item.setError(null);
        notifyUpdated(item);
        notifyQueueChanged();

        executor.execute(() -> executeDownload(item));
    }
/**
 * Requests that an active download stop at a safe interruption point.
 *
 * @param id download identifier
 */

    public void pause(String id) {
        DownloadItem item = queueMap.get(id);
        if (item == null) return;
        item.setStatus(DownloadStatus.PAUSED);
        DownloadService.cancelNotification(context, item);
        notifyUpdated(item);
        notifyQueueChanged();
    }
/**
 * Resumes a paused download.
 *
 * @param id download identifier
 */

    public void resume(String id) {
        start(id);
    }
/**
 * Cancels a download and removes its temporary state.
 *
 * @param id download identifier
 */

    public void cancel(String id) {
        DownloadItem item = queueMap.get(id);
        if (item == null) return;

        boolean wasRunning = (item.getStatus() == DownloadStatus.DOWNLOADING || item.getStatus() == DownloadStatus.RESOLVING);
        item.setStatus(DownloadStatus.CANCELLED);
        item.setDownloadedSegments(0);

        File tsFile = FileUtil.getTempTsFile(item.getAnimeTitle(), item.getEpisodeName());
        if (tsFile.exists()) tsFile.delete();

        File subFile = FileUtil.getTempSubFile(item.getAnimeTitle(), item.getEpisodeName());
        if (subFile.exists()) subFile.delete();

        File partFile = FileUtil.getPartFile(context, item.getAnimeTitle(), item.getEpisodeName());
        if (partFile.exists()) partFile.delete();

        DownloadService.cancelNotification(context, item);

        notifyUpdated(item);
        notifyQueueChanged();

        if (wasRunning) {
            checkAndStartNextQueued();
        }
    }
/**
 * Resets a failed download so it can be started again.
 *
 * @param id download identifier
 */

    public void retry(String id) {
        DownloadItem item = queueMap.get(id);
        if (item != null) {
            item.setDownloadedSegments(0);
        }
        start(id);
    }
/**
 * Removes one queue item and its associated downloaded artifacts.
 *
 * @param id download identifier
 */

    public synchronized void remove(String id) {
        DownloadItem it = queueMap.get(id);
        if (it == null) return;

        boolean wasRunning = (it.getStatus() == DownloadStatus.DOWNLOADING || it.getStatus() == DownloadStatus.RESOLVING);
        it.setStatus(DownloadStatus.CANCELLED);
        it.setDownloadedSegments(0);

        File tsFile = FileUtil.getTempTsFile(it.getAnimeTitle(), it.getEpisodeName());
        if (tsFile.exists()) tsFile.delete();

        File subFile = FileUtil.getTempSubFile(it.getAnimeTitle(), it.getEpisodeName());
        if (subFile.exists()) subFile.delete();

        File partFile = FileUtil.getPartFile(context, it.getAnimeTitle(), it.getEpisodeName());
        if (partFile.exists()) partFile.delete();

        File finalMp4 = (it.getOutputPath() != null && !it.getOutputPath().isEmpty())
                ? new File(it.getOutputPath())
                : FileUtil.getFinalMp4File(it.getAnimeTitle(), it.getEpisodeName());

        if (finalMp4.exists()) {
            finalMp4.delete();
            FileUtil.scanMedia(context, finalMp4);
        }

        File finalVtt = FileUtil.getFinalVttFile(it.getAnimeTitle(), it.getEpisodeName());
        if (finalVtt.exists()) {
            finalVtt.delete();
        }

        DownloadService.cancelNotification(context, it);

        queueMap.remove(id);
        queueList.remove(it);

        
        File dir = FileUtil.getKensakuDir(it.getAnimeTitle());
        if (dir.exists() && dir.isDirectory()) {
            File[] remaining = dir.listFiles();
            if (remaining == null || remaining.length == 0) {
                dir.delete();
            }
        }

        notifyQueueChanged();

        if (wasRunning) {
            checkAndStartNextQueued();
        }
    }
/**
 * Removes all queued and downloaded episodes belonging to a series.
 *
 * @param animeTitle series title used to locate the related files and queue entries
 */

    public synchronized void removeAnimeSeries(String animeTitle) {
        if (animeTitle == null || animeTitle.trim().isEmpty()) return;

        List<DownloadItem> toRemove = new ArrayList<>();
        boolean wasAnyRunning = false;

        for (DownloadItem item : queueList) {
            if (item.getAnimeTitle().equals(animeTitle)) {
                toRemove.add(item);
                if (item.getStatus() == DownloadStatus.DOWNLOADING || item.getStatus() == DownloadStatus.RESOLVING) {
                    wasAnyRunning = true;
                }
            }
        }

        for (DownloadItem it : toRemove) {
            it.setStatus(DownloadStatus.CANCELLED);
            it.setDownloadedSegments(0);

            File tsFile = FileUtil.getTempTsFile(it.getAnimeTitle(), it.getEpisodeName());
            if (tsFile.exists()) tsFile.delete();

            File subFile = FileUtil.getTempSubFile(it.getAnimeTitle(), it.getEpisodeName());
            if (subFile.exists()) subFile.delete();

            File partFile = FileUtil.getPartFile(context, it.getAnimeTitle(), it.getEpisodeName());
            if (partFile.exists()) partFile.delete();

            File finalMp4 = (it.getOutputPath() != null && !it.getOutputPath().isEmpty())
                    ? new File(it.getOutputPath())
                    : FileUtil.getFinalMp4File(it.getAnimeTitle(), it.getEpisodeName());

            if (finalMp4.exists()) {
                finalMp4.delete();
                FileUtil.scanMedia(context, finalMp4);
            }

            File finalVtt = FileUtil.getFinalVttFile(it.getAnimeTitle(), it.getEpisodeName());
            if (finalVtt.exists()) {
                finalVtt.delete();
            }

            DownloadService.cancelNotification(context, it);

            queueMap.remove(it.getId());
            queueList.remove(it);
        }

        
        File dir = FileUtil.getKensakuDir(animeTitle);
        if (dir.exists() && dir.isDirectory()) {
            File[] remaining = dir.listFiles();
            if (remaining == null || remaining.length == 0) {
                dir.delete();
            }
        }

        notifyQueueChanged();

        if (wasAnyRunning) {
            checkAndStartNextQueued();
        }
    }
/**
 * Removes completed items from the queue while leaving their downloaded files intact.
 */

    public synchronized void clearFinished() {
        List<DownloadItem> removeList = new ArrayList<>();
        for (DownloadItem item : queueList) {
            if (item.getStatus() == DownloadStatus.COMPLETED ||
                item.getStatus() == DownloadStatus.SKIPPED ||
                item.getStatus() == DownloadStatus.CANCELLED) {
                removeList.add(item);
            }
        }
        for (DownloadItem it : removeList) {
            DownloadService.cancelNotification(context, it);
            queueList.remove(it);
            queueMap.remove(it.getId());
        }
        notifyQueueChanged();
    }

    private synchronized void checkAndStartNextQueued() {
        if (isAnyDownloadRunning()) return;
        for (DownloadItem item : queueList) {
            if (item.getStatus() == DownloadStatus.QUEUED) {
                start(item.getId());
                break;
            }
        }
    }

    private void executeDownload(DownloadItem item) {
        File finalMp4 = FileUtil.getFinalMp4File(item.getAnimeTitle(), item.getEpisodeName());
        File finalVtt = FileUtil.getFinalVttFile(item.getAnimeTitle(), item.getEpisodeName());

        if (finalMp4.exists() && finalMp4.length() > 0) {
            item.setStatus(DownloadStatus.COMPLETED);
            item.setProgress(100);
            item.setSize(FormatUtil.formatSize(finalMp4.length()));
            item.setOutputPath(finalMp4.getAbsolutePath());
            notifyUpdated(item);
            notifyQueueChanged();
            checkAndStartNextQueued();
            return;
        }

        File partFile = FileUtil.getPartFile(context, item.getAnimeTitle(), item.getEpisodeName());
        File tempTsFile = FileUtil.getTempTsFile(item.getAnimeTitle(), item.getEpisodeName());
        File tempSubFile = FileUtil.getTempSubFile(item.getAnimeTitle(), item.getEpisodeName());

        try {
            List<ApiClient.HlsManifest> manifests = ApiClient.getInstance().resolveAllHlsManifests(item.getEpisodeLink());
            if (manifests.isEmpty()) {
                throw new Exception("Could not find any stream segments to download.");
            }

            item.setStatus(DownloadStatus.DOWNLOADING);
            notifyUpdated(item);
            notifyQueueChanged();

            byte[] buffer = new byte[64 * 1024];
            boolean downloadSuccess = false;
            Throwable lastError = null;

            for (int mIndex = 0; mIndex < manifests.size(); mIndex++) {
                ApiClient.HlsManifest manifest = manifests.get(mIndex);
                int totalSegments = manifest.segments.size();
                int startSegment = Math.min(item.getDownloadedSegments(), totalSegments);

                boolean append = startSegment > 0 && tempTsFile.exists();
                if (!append && tempTsFile.exists()) {
                    tempTsFile.delete();
                    startSegment = 0;
                }

                if (manifest.subtitleUrl != null && (!tempSubFile.exists() || tempSubFile.length() == 0)) {
                    try {
                        Request subReq = new Request.Builder()
                                .url(manifest.subtitleUrl)
                                .header("User-Agent", ApiClient.UA)
                                .header("Referer", manifest.playerUrl)
                                .build();
                        try (Response subResp = ApiClient.getInstance().getHttpClient().newCall(subReq).execute()) {
                            if (subResp.isSuccessful() && subResp.body() != null) {
                                try (FileOutputStream subOut = new FileOutputStream(tempSubFile)) {
                                    subOut.write(subResp.body().bytes());
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Subtitle download failed: " + e.getMessage());
                    }
                }

                long startTime = System.currentTimeMillis();
                long bytesDownloadedThisSession = 0;

                try (FileOutputStream out = new FileOutputStream(tempTsFile, append)) {
                    for (int i = startSegment; i < totalSegments; i++) {
                        if (item.getStatus() == DownloadStatus.PAUSED) {
                            item.setDownloadedSegments(i);
                            notifyUpdated(item);
                            notifyQueueChanged();
                            return;
                        }

                        if (item.getStatus() == DownloadStatus.CANCELLED) {
                            out.close();
                            if (tempTsFile.exists()) tempTsFile.delete();
                            if (tempSubFile.exists()) tempSubFile.delete();
                            if (partFile.exists()) partFile.delete();
                            return;
                        }

                        ApiClient.HlsSegment seg = manifest.segments.get(i);
                        boolean segSuccess = false;

                        for (int attempt = 1; attempt <= 3; attempt++) {
                            Request segReq = new Request.Builder()
                                    .url(seg.url)
                                    .header("User-Agent", ApiClient.UA)
                                    .header("Referer", manifest.playerUrl)
                                    .header("Accept", "*/*")
                                    .build();

                            try (Response segResp = ApiClient.getInstance().getHttpClient().newCall(segReq).execute()) {
                                if (!segResp.isSuccessful() || segResp.body() == null) {
                                    if (segResp.code() == 451 || attempt == 3) {
                                        throw new Exception("HTTP " + segResp.code() + " on segment " + i);
                                    }
                                    Thread.sleep(1000);
                                    continue;
                                }

                                try (InputStream in = segResp.body().byteStream()) {
                                    int read;
                                    while ((read = in.read(buffer)) != -1) {
                                        out.write(buffer, 0, read);
                                        bytesDownloadedThisSession += read;
                                    }
                                }
                                segSuccess = true;
                                break;
                            } catch (Exception e) {
                                if (attempt == 3) throw e;
                                Thread.sleep(1000);
                            }
                        }

                        if (!segSuccess) {
                            throw new Exception("Failed segment " + i);
                        }

                        item.setDownloadedSegments(i + 1);

                        int pct = (int) (((i + 1) / (double) totalSegments) * 100.0);
                        item.setProgress(Math.min(99, Math.max(1, pct)));
                        item.setSize(FormatUtil.formatSize(tempTsFile.length()));

                        long elapsedMs = System.currentTimeMillis() - startTime;
                        if (elapsedMs > 1000) {
                            double bytesPerSec = (bytesDownloadedThisSession / (double) elapsedMs) * 1000.0;
                            item.setSpeed(FormatUtil.formatSize((long) bytesPerSec) + "/s");

                            int remainingSegs = totalSegments - (i + 1);
                            double avgSecPerSeg = (elapsedMs / 1000.0) / (i + 1 - startSegment);
                            long etaSec = (long) (remainingSegs * avgSecPerSeg);
                            item.setEta(FormatUtil.formatTime(etaSec));
                        }

                        notifyUpdated(item);
                    }
                    out.flush();

                    downloadSuccess = true;
                    break;

                } catch (Throwable t) {
                    lastError = t;
                    Log.w(TAG, "Server index " + mIndex + " failed (" + t.getMessage() + "). Checking fallback...");

                    if (item.getStatus() == DownloadStatus.PAUSED || item.getStatus() == DownloadStatus.CANCELLED) {
                        return;
                    }

                    if (mIndex + 1 < manifests.size()) {
                        if (tempTsFile.exists()) tempTsFile.delete();
                        item.setDownloadedSegments(0);
                        item.setEta("Switching to mirror...");
                        notifyUpdated(item);
                    }
                }
            }

            if (!downloadSuccess) {
                throw (lastError != null ? lastError : new Exception("All servers failed."));
            }

            item.setEta("Finalizing...");
            notifyUpdated(item);

            boolean hasSub = tempSubFile.exists() && tempSubFile.length() > 0;
            String[] args;

            if (hasSub) {
                args = new String[]{
                        "-hide_banner", "-loglevel", "warning", "-y",
                        "-i", tempTsFile.getAbsolutePath(),
                        "-i", tempSubFile.getAbsolutePath(),
                        "-c:v", "copy",
                        "-c:a", "copy",
                        "-c:s", "mov_text",
                        "-metadata:s:s:0", "language=eng",
                        "-bsf:a", "aac_adtstoasc",
                        partFile.getAbsolutePath()
                };
            } else {
                args = new String[]{
                        "-hide_banner", "-loglevel", "warning", "-y",
                        "-i", tempTsFile.getAbsolutePath(),
                        "-c", "copy",
                        "-bsf:a", "aac_adtstoasc",
                        partFile.getAbsolutePath()
                };
            }

            FFmpegSession session = FFmpegKit.executeWithArguments(args);
            boolean remuxSuccess = ReturnCode.isSuccess(session.getReturnCode()) && partFile.exists() && partFile.length() > 0;

            if (remuxSuccess) {
                tempTsFile.delete();
                partFile.renameTo(finalMp4);

                if (hasSub) {
                    copyFile(tempSubFile, finalVtt);
                    tempSubFile.delete();
                }

                FileUtil.scanMedia(context, finalMp4);
                item.setStatus(DownloadStatus.COMPLETED);
                item.setProgress(100);
                item.setEta("00:00");
                item.setSize(FormatUtil.formatSize(finalMp4.length()));
                item.setOutputPath(finalMp4.getAbsolutePath());
            } else {
                tempTsFile.renameTo(finalMp4);
                if (hasSub) {
                    copyFile(tempSubFile, finalVtt);
                    tempSubFile.delete();
                }
                FileUtil.scanMedia(context, finalMp4);
                item.setStatus(DownloadStatus.COMPLETED);
                item.setProgress(100);
                item.setOutputPath(finalMp4.getAbsolutePath());
            }

            notifyUpdated(item);
            notifyQueueChanged();
            checkAndStartNextQueued();

        } catch (Throwable t) {
            Log.e(TAG, "Download failed for " + item.getAnimeTitle(), t);
            item.setStatus(DownloadStatus.FAILED);
            item.setError("Download failed. Please check your internet connection and try again.");
            notifyUpdated(item);
            notifyQueueChanged();
            checkAndStartNextQueued();
        }
    }

    private void copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (Exception ignored) {}
    }
}