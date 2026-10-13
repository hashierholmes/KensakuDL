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
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Owns the download queue and the HLS transfer pipeline, including persistence, duplicate reconciliation, segment recovery, decryption, finalization, and listener notifications. Queue mutations are synchronized where shared state is accessed.
 */
public class DownloadManager {
    private static final String TAG = "DownloadManager";
    private static final String PREFS_NAME = "kensaku_download_queue";
    private static final String KEY_QUEUE_DATA = "saved_queue_items";
    // Conservative default: enough parallelism to hide network latency without
    // overwhelming mobile connections or HLS providers.
    private static final int MAX_PARALLEL_SEGMENTS = 4;

    /** Receives download-state changes emitted by the queue manager. */
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
    private final ConcurrentHashMap<String, Integer> automaticRetryCounts = new ConcurrentHashMap<>();

    private DownloadManager(Context context) {
        this.context = context.getApplicationContext();
        loadQueueFromPrefs();
        syncWithDiskStorage();
    }

    /**
     * Returns the process-wide download manager, initializing it with the application context on first use.
     * 
     * @param context context used to initialize persistent storage
     * @return shared download manager instance
     */
    public static synchronized DownloadManager getInstance(Context context) {
        if (instance == null) {
            instance = new DownloadManager(context);
        }
        return instance;
    }

    /**
     * Registers a listener for item and queue changes. Duplicate registrations are ignored by the listener collection.
     * 
     * @param listener listener to register
     */
    public synchronized void addListener(DownloadListener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    /**
     * Unregisters a listener so it no longer receives queue updates.
     * 
     * @param listener listener to remove
     */
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
     * Returns a snapshot of the current queue rather than exposing the manager's mutable internal collection.
     * 
     * @return queue snapshot in manager order
     */
    public synchronized List<DownloadItem> getQueue() {
        return new ArrayList<>(queueList);
    }

    /**
     * Scans storage, removes ghost items, deletes empty directories,
     * and ensures only valid folders appear on the list.
     */
    public synchronized void syncWithDiskStorage() {
        try {
            boolean changed = false;

            // 1. Purge items from queueList whose titles are blank or whose completed files were deleted from disk
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
                            : FileUtil.getFinalMp4File(item.getAnimeTitle(), item.getEpisodeName(), item.getTrack());

                    if (!file.exists() || file.length() == 0) {
                        queueMap.remove(item.getId());
                        queueIterator.remove();
                        changed = true;
                    }
                }
            }

            // 2. Scan physical KensakuDL directory
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

                // If folder has an empty or blank name, clean it up
                if (animeTitle.isEmpty()) {
                    folder.delete();
                    continue;
                }

                File[] files = folder.listFiles();

                // Delete physically empty folders from disk
                if (files == null || files.length == 0) {
                    folder.delete();
                    continue;
                }

                // Count valid video files
                int validVideos = 0;
                Arrays.sort(files, Comparator.comparing(File::getName));

                for (File file : files) {
                    String fileName = file.getName();
                    if (fileName.endsWith(".mp4") && !fileName.endsWith(".part.mp4") && file.length() > 0) {
                        validVideos++;
                        String episodeName = normalizeEpisodeName(extractEpisodeName(fileName));
                        String track = extractTrack(fileName);
                        DownloadItem existing = findByLogicalEpisode(animeTitle, episodeName, track);

                        if (existing == null) {
                            String id = animeTitle + "::" + track + "::" + episodeName + "::" + file.getAbsolutePath();
                            DownloadItem item = new DownloadItem(id, animeTitle, episodeName, episodeName, "", track);
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

                // If folder contains no valid mp4s or active downloads, remove empty folder
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
        if (clean.contains("_Episodes_")) {
            String[] parts = clean.split("_Episodes_", 2);
            if (parts.length > 1) return parts[1];
        }
        if (clean.contains("_Episode_")) {
            String[] parts = clean.split("_Episode_", 2);
            if (parts.length > 1) return parts[1];
        }
        Matcher m = Pattern.compile("\\b(\\d+)\\b").matcher(clean);
        if (m.find()) {
            return m.group(1);
        }
        return clean;
    }


    private String extractTrack(String fileName) {
        if (fileName != null && fileName.regionMatches(true, 0, "Dub - ", 0, 6)) return "dub";
        return "sub";
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
                                canonical.getEpisodeName(),
                                canonical.getTrack()
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
        String track = source.getTrack();
        String id = source.getAnimeTitle() + "::" + track + "::" + episodeName + "::" + source.getEpisodeLink();

        DownloadItem item = new DownloadItem(
                id,
                source.getAnimeTitle(),
                episodeName,
                source.getEpisodeSlug(),
                source.getEpisodeLink(),
                track
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

    private String buildLogicalEpisodeKey(String animeTitle, String episodeName, String track) {
        // Queue identity follows the same sanitized title used by the filesystem.
        // This prevents a title such as "Mushoku Tensei: ..." from becoming a
        // second queue group when the physical folder is "Mushoku Tensei_ ...".
        String title = FormatUtil.cleanName(animeTitle).toLowerCase();
        String normalizedTrack = "dub".equalsIgnoreCase(track) ? "dub" : "sub";
        return title + "::" + normalizedTrack + "::" + normalizeEpisodeName(episodeName);
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

    private DownloadItem findByLogicalEpisode(String animeTitle, String episodeName, String track) {
        String key = buildLogicalEpisodeKey(animeTitle, episodeName, track);

        for (DownloadItem item : queueList) {
            if (buildLogicalEpisodeKey(item.getAnimeTitle(), item.getEpisodeName(), item.getTrack()).equals(key)) {
                return item;
            }
        }

        return null;
    }

    /**
     * Checks whether any queue item is currently in an active download state.
     * 
     * @return {@code true} if a download is running
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
     * Adds the requested episodes to the queue in playlist order, reconciling logical duplicates before scheduling work.
     * 
     * @param animeTitle title used to group and name the downloaded episodes
     * @param episodes episodes to enqueue in the supplied order
     */
    public synchronized void enqueueBatch(String animeTitle, List<EpisodeItem> episodes) {
        if (episodes == null || episodes.isEmpty()) return;
        if (animeTitle == null || animeTitle.trim().isEmpty()) return;

        String firstIdToStart = null;

        for (EpisodeItem ep : episodes) {
            if (ep == null) continue;

            String episodeName = normalizeEpisodeName(ep.getName());
            String track = ep.getTrack();
            if (findByLogicalEpisode(animeTitle, episodeName, track) != null) {
                continue;
            }

            String id = animeTitle + "::" + track + "::" + episodeName + "::" + ep.getLink();
            DownloadItem item = new DownloadItem(
                    id,
                    animeTitle,
                    episodeName,
                    ep.getSlug(),
                    ep.getLink(),
                    track
            );

            queueList.add(item);
            queueMap.put(id, item);

            if (firstIdToStart == null) {
                firstIdToStart = id;
            }
        }

        notifyQueueChanged();

        if (!isAnyDownloadRunning() && firstIdToStart != null) {
            start(firstIdToStart);
        }
    }

    /**
     * Starts the queued item identified by {@code id} when its current state allows it.
     * 
     * @param id stable queue item identifier
     */
    public void start(String id) {
        DownloadItem item = queueMap.get(id);
        if (item == null) return;

        try {
            Intent serviceIntent = new Intent(context, DownloadService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
        } catch (Exception ignored) {}

        automaticRetryCounts.remove(item.getId());
        item.setStatus(DownloadStatus.RESOLVING);
        item.setError(null);
        notifyUpdated(item);
        notifyQueueChanged();

        executor.execute(() -> executeDownload(item));
    }

    /**
     * Requests that the identified active download stop at a resumable boundary.
     * 
     * @param id stable queue item identifier
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
     * Returns a paused item to the runnable queue.
     * 
     * @param id stable queue item identifier
     */
    public void resume(String id) {
        start(id);
    }

    /**
     * Cancels active work for the identified item and performs its cancellation cleanup.
     * 
     * @param id stable queue item identifier
     */
    public void cancel(String id) {
        DownloadItem item = queueMap.get(id);
        if (item == null) return;

        boolean wasRunning = (item.getStatus() == DownloadStatus.DOWNLOADING || item.getStatus() == DownloadStatus.RESOLVING);
        item.setStatus(DownloadStatus.CANCELLED);
        item.setDownloadedSegments(0);

        File tsFile = FileUtil.getTempTsFile(item.getAnimeTitle(), item.getEpisodeName(), item.getTrack());
        if (tsFile.exists()) tsFile.delete();

        File subFile = FileUtil.getTempSubFile(item.getAnimeTitle(), item.getEpisodeName(), item.getTrack());
        if (subFile.exists()) subFile.delete();

        File partFile = FileUtil.getPartFile(context, item.getAnimeTitle(), item.getEpisodeName(), item.getTrack());
        if (partFile.exists()) partFile.delete();

        DownloadService.cancelNotification(context, item);

        notifyUpdated(item);
        notifyQueueChanged();

        if (wasRunning) {
            checkAndStartNextQueued();
        }
    }

    /**
     * Resets a retryable failed item and schedules another attempt.
     * 
     * @param id stable queue item identifier
     */
    public void retry(String id) {
        DownloadItem item = queueMap.get(id);
        if (item != null) {
            item.setDownloadedSegments(0);
        }
        start(id);
    }

    /**
     * Removes an item from the queue and releases its associated temporary state where applicable.
     * 
     * @param id stable queue item identifier
     */
    public synchronized void remove(String id) {
        DownloadItem it = queueMap.get(id);
        if (it == null) return;

        boolean wasRunning = (it.getStatus() == DownloadStatus.DOWNLOADING || it.getStatus() == DownloadStatus.RESOLVING);
        it.setStatus(DownloadStatus.CANCELLED);
        it.setDownloadedSegments(0);

        File tsFile = FileUtil.getTempTsFile(it.getAnimeTitle(), it.getEpisodeName(), it.getTrack());
        if (tsFile.exists()) tsFile.delete();

        File subFile = FileUtil.getTempSubFile(it.getAnimeTitle(), it.getEpisodeName(), it.getTrack());
        if (subFile.exists()) subFile.delete();

        File partFile = FileUtil.getPartFile(context, it.getAnimeTitle(), it.getEpisodeName(), it.getTrack());
        if (partFile.exists()) partFile.delete();

        File finalMp4 = (it.getOutputPath() != null && !it.getOutputPath().isEmpty())
                ? new File(it.getOutputPath())
                : FileUtil.getFinalMp4File(it.getAnimeTitle(), it.getEpisodeName(), it.getTrack());

        if (finalMp4.exists()) {
            finalMp4.delete();
            FileUtil.scanMedia(context, finalMp4);
        }

        File finalVtt = FileUtil.getFinalVttFile(it.getAnimeTitle(), it.getEpisodeName(), it.getTrack());
        if (finalVtt.exists()) {
            finalVtt.delete();
        }

        DownloadService.cancelNotification(context, it);

        queueMap.remove(id);
        queueList.remove(it);

        // Delete parent folder if empty
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
     * Removes the queue entries associated with the supplied anime title.
     * 
     * @param animeTitle series title used to identify its queue entries
     */
    public synchronized void removeAnimeSeries(String animeTitle) {
        if (animeTitle == null || animeTitle.trim().isEmpty()) return;

        List<DownloadItem> toRemove = new ArrayList<>();
        boolean wasAnyRunning = false;

        String normalizedAnimeTitle = FormatUtil.cleanName(animeTitle);
        for (DownloadItem item : queueList) {
            if (FormatUtil.cleanName(item.getAnimeTitle()).equalsIgnoreCase(normalizedAnimeTitle)) {
                toRemove.add(item);
                if (item.getStatus() == DownloadStatus.DOWNLOADING || item.getStatus() == DownloadStatus.RESOLVING) {
                    wasAnyRunning = true;
                }
            }
        }

        for (DownloadItem it : toRemove) {
            it.setStatus(DownloadStatus.CANCELLED);
            it.setDownloadedSegments(0);

            File tsFile = FileUtil.getTempTsFile(it.getAnimeTitle(), it.getEpisodeName(), it.getTrack());
            if (tsFile.exists()) tsFile.delete();

            File subFile = FileUtil.getTempSubFile(it.getAnimeTitle(), it.getEpisodeName(), it.getTrack());
            if (subFile.exists()) subFile.delete();

            File partFile = FileUtil.getPartFile(context, it.getAnimeTitle(), it.getEpisodeName(), it.getTrack());
            if (partFile.exists()) partFile.delete();

            File finalMp4 = (it.getOutputPath() != null && !it.getOutputPath().isEmpty())
                    ? new File(it.getOutputPath())
                    : FileUtil.getFinalMp4File(it.getAnimeTitle(), it.getEpisodeName(), it.getTrack());

            if (finalMp4.exists()) {
                finalMp4.delete();
                FileUtil.scanMedia(context, finalMp4);
            }

            File finalVtt = FileUtil.getFinalVttFile(it.getAnimeTitle(), it.getEpisodeName(), it.getTrack());
            if (finalVtt.exists()) {
                finalVtt.delete();
            }

            DownloadService.cancelNotification(context, it);

            queueMap.remove(it.getId());
            queueList.remove(it);
        }

        // Clean up empty directory on disk
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
     * Removes terminal queue entries that no longer need to be shown in the active queue.
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

    /**
     * Selects the next eligible queued item when the manager has capacity to begin another download.
     */
    private synchronized void checkAndStartNextQueued() {
        if (isAnyDownloadRunning()) return;
        for (DownloadItem item : queueList) {
            if (item.getStatus() == DownloadStatus.QUEUED) {
                start(item.getId());
                break;
            }
        }
    }

    /**
     * Fetches and caches the encryption key for an HLS manifest so encrypted segments can be decrypted without repeating the same key request.
     * 
     * @param hlsKeyCache per-download cache keyed by key URL
     * @param manifest manifest providing request headers and source context
     * @param keyUrl key URI from the playlist
     * @return AES key bytes
     * @throws Exception if the key cannot be retrieved
     */
    private synchronized byte[] fetchHlsKey(Map<String, byte[]> hlsKeyCache, ApiClient.HlsManifest manifest, String keyUrl) throws Exception {
        byte[] cached = hlsKeyCache.get(keyUrl);
        if (cached != null) return cached;

        Request.Builder builder = new Request.Builder()
                .url(keyUrl)
                .header("User-Agent", ApiClient.UA)
                .header("Referer", manifest.playerUrl)
                .header("Accept", "*/*");
        if (manifest.origin != null && !manifest.origin.isEmpty()) {
            builder.header("Origin", manifest.origin);
        }

        byte[] key;
        try (Response response = ApiClient.getInstance().getHttpClient().newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HLS key request failed: HTTP " + response.code());
            }
            key = response.body().bytes();
        }

        if (key.length != 16) {
            throw new Exception("Invalid HLS encryption key.");
        }

        hlsKeyCache.put(keyUrl, key);
        return key;
    }

    /**
     * Creates the 16-byte initialization vector used when an encrypted HLS segment relies on its media sequence number.
     * 
     * @param sequenceNumber segment sequence number
     * @return 16-byte IV in network byte order
     */
    private byte[] createSequenceIv(long sequenceNumber) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 0; i--) {
            iv[i] = (byte) (sequenceNumber & 0xff);
            sequenceNumber >>>= 8;
        }
        return iv;
    }

    /**
     * Decrypts an AES-encrypted HLS segment using the supplied key and IV.
     * 
     * @param encrypted encrypted segment bytes
     * @param key AES key bytes
     * @param iv initialization vector
     * @return decrypted segment bytes
     * @throws Exception if decryption fails
     */
    private byte[] decryptHlsSegment(byte[] encrypted, byte[] key, byte[] iv) throws Exception {
        if ((encrypted.length & 15) != 0) {
            throw new Exception("Invalid encrypted HLS segment size.");
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(encrypted);
    }

    /**
     * Downloads a segment into the temporary transport-stream output, applying playlist encryption metadata and resume bookkeeping as needed.
     */
    private void downloadHlsSegmentToFile(DownloadItem item, ApiClient.HlsManifest manifest,
                                          ApiClient.HlsSegment segment, int segmentIndex,
                                          Map<String, byte[]> hlsKeyCache, File destination) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (item.getStatus() == DownloadStatus.PAUSED || item.getStatus() == DownloadStatus.CANCELLED
                    || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Download stopped");
            }

            Request.Builder builder = new Request.Builder()
                    .url(segment.url)
                    .header("User-Agent", ApiClient.UA)
                    .header("Referer", manifest.playerUrl)
                    .header("Accept", "*/*");
            if (manifest.origin != null && !manifest.origin.isEmpty()) {
                builder.header("Origin", manifest.origin);
            }

            try (Response response = ApiClient.getInstance().getHttpClient().newCall(builder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    if (response.code() == 451) throw new Exception("HTTP 451 on segment " + segmentIndex);
                    throw new Exception("HTTP " + response.code() + " on segment " + segmentIndex);
                }
                String contentType = response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT);
                byte[] downloaded = response.body().bytes();
                if (downloaded.length == 0) throw new Exception("Empty media segment " + segmentIndex);

                // HLS segment endpoints sometimes return an HTML/JSON error page with
                // HTTP 200. Treat that as a bad mirror so the caller can fail over.
                boolean textResponse = contentType.contains("text/html")
                        || contentType.contains("application/json")
                        || contentType.contains("text/plain");
                boolean looksLikeErrorPage = downloaded.length >= 5
                        && new String(downloaded, 0, Math.min(downloaded.length, 32),
                                java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase(java.util.Locale.ROOT)
                                .matches("^(<!doctype html|<html|\\{\\s*\"(error|message|status)|access denied).*" );
                if (textResponse || looksLikeErrorPage) {
                    throw new Exception("Invalid media response for segment " + segmentIndex
                            + " (HTTP " + response.code() + ", type "
                            + (contentType.isEmpty() ? "unknown" : contentType) + ")");
                }
                byte[] mediaBytes = downloaded;
                if (segment.encryptionKeyUrl != null && !segment.encryptionKeyUrl.isEmpty()) {
                    byte[] key = fetchHlsKey(hlsKeyCache, manifest, segment.encryptionKeyUrl);
                    byte[] iv = segment.encryptionIv != null
                            ? segment.encryptionIv : createSequenceIv(segment.sequenceNumber);
                    mediaBytes = decryptHlsSegment(downloaded, key, iv);
                }
                if (mediaBytes.length == 0) throw new Exception("Empty decrypted segment " + segmentIndex);
                try (FileOutputStream output = new FileOutputStream(destination, false)) {
                    output.write(mediaBytes);
                }
                return;
            } catch (Exception e) {
                lastError = e;
                if (e instanceof InterruptedException || item.getStatus() == DownloadStatus.PAUSED
                        || item.getStatus() == DownloadStatus.CANCELLED) throw e;
                if (attempt < 3) Thread.sleep(300L * attempt);
            }
        }
        throw (lastError != null ? lastError : new Exception("Failed segment " + segmentIndex));
    }

    // Checkpoint the exact committed byte length and playlist identity. If the app
    // dies while appending a batch, the next run truncates the file back to the
    // last durable checkpoint instead of appending onto a partial batch.
    /**
     * Builds a stable identity for an HLS manifest to determine whether a saved segment checkpoint belongs to the same playlist.
     * 
     * @param manifest manifest whose identity is required
     * @return playlist identity string
     */
    private String hlsPlaylistIdentity(ApiClient.HlsManifest manifest) {
        StringBuilder value = new StringBuilder();
        value.append(manifest.segments.size()).append('|');
        for (ApiClient.HlsSegment segment : manifest.segments) {
            value.append(segment.sequenceNumber).append(':')
                    .append(segment.url == null ? "" : segment.url).append(':')
                    .append(segment.encryptionKeyUrl == null ? "" : segment.encryptionKeyUrl)
                    .append(':');
            if (segment.encryptionIv != null) {
                for (byte b : segment.encryptionIv) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            }
            value.append(';');
        }
        return Integer.toHexString(value.toString().hashCode()) + "-" + manifest.segments.size();
    }

    /**
     * Validates and restores the segment checkpoint associated with a partial transport-stream file.
     * 
     * @return the number of segments confirmed as already present
     */
    private int restoreHlsCheckpoint(File tempTsFile, File checkpointFile,
                                     ApiClient.HlsManifest manifest, int totalSegments) {
        if (!checkpointFile.exists() || !tempTsFile.exists()) {
            if (tempTsFile.exists()) tempTsFile.delete();
            if (checkpointFile.exists()) checkpointFile.delete();
            return 0;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(checkpointFile))) {
            String identity = reader.readLine();
            String countLine = reader.readLine();
            String lengthLine = reader.readLine();
            if (!hlsPlaylistIdentity(manifest).equals(identity) || countLine == null || lengthLine == null) {
                tempTsFile.delete(); checkpointFile.delete(); return 0;
            }
            int count = Integer.parseInt(countLine);
            long length = Long.parseLong(lengthLine);
            if (count < 0 || count > totalSegments || length < 0 || tempTsFile.length() < length) {
                tempTsFile.delete(); checkpointFile.delete(); return 0;
            }
            // Any bytes after the last checkpoint were from an uncommitted batch.
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(tempTsFile, "rw")) {
                file.setLength(length);
                file.getFD().sync();
            }
            return count;
        } catch (Exception e) {
            Log.w(TAG, "Invalid HLS checkpoint; restarting this stream safely", e);
            tempTsFile.delete(); checkpointFile.delete();
            return 0;
        }
    }

    /**
     * Persists the current segment checkpoint so a later attempt can resume compatible partial work.
     */
    private void saveHlsCheckpoint(File tempTsFile, File checkpointFile,
                                   ApiClient.HlsManifest manifest, int committedSegments) throws Exception {
        File parent = checkpointFile.getParentFile();
        File tempCheckpoint = new File(parent, checkpointFile.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tempCheckpoint, false)) {
            String data = hlsPlaylistIdentity(manifest) + "\n" + committedSegments + "\n" + tempTsFile.length() + "\n";
            output.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        if (checkpointFile.exists() && !checkpointFile.delete()) throw new Exception("Could not replace HLS checkpoint.");
        if (!tempCheckpoint.renameTo(checkpointFile)) throw new Exception("Could not commit HLS checkpoint.");
    }

    /**
     * Deletes a file or directory tree used for temporary download state.
     * 
     * @param file root of the tree to remove
     */
    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /**
     * Runs the end-to-end episode download: resolves a usable HLS manifest, transfers and assembles segments, finalizes the media, and publishes the resulting state.
     */
    private void executeDownload(DownloadItem item) {
        // A previous attempt may have failed and scheduled this retry. Clear that
        // transient message as soon as the new attempt actually starts so it cannot
        // remain visible while the item is already downloading successfully.
        if (item.getError() != null && !item.getError().isEmpty()) {
            item.setError(null);
            item.setEta("Connecting to provider...");
            notifyUpdated(item);
            notifyQueueChanged();
        }

        String track = item.getTrack();
        File finalMp4 = FileUtil.getFinalMp4File(item.getAnimeTitle(), item.getEpisodeName(), track);
        File finalVtt = FileUtil.getFinalVttFile(item.getAnimeTitle(), item.getEpisodeName(), track);

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

        File partFile = FileUtil.getPartFile(context, item.getAnimeTitle(), item.getEpisodeName(), track);
        File tempTsFile = FileUtil.getTempTsFile(item.getAnimeTitle(), item.getEpisodeName(), track);
        File tempSubFile = FileUtil.getTempSubFile(item.getAnimeTitle(), item.getEpisodeName(), track);
        File hlsCheckpointFile = new File(tempTsFile.getAbsolutePath() + ".checkpoint");

        Map<String, byte[]> hlsKeyCache = new HashMap<>();

        try {
            item.setEta("Connecting to provider...");
            notifyUpdated(item);
            List<ApiClient.HlsManifest> manifests = ApiClient.getInstance()
                    .resolveAllHlsManifests(item.getEpisodeLink(), track);
            if (manifests.isEmpty()) {
                throw new Exception("Could not find any stream segments to download.");
            }

            item.setStatus(DownloadStatus.DOWNLOADING);
            notifyUpdated(item);
            notifyQueueChanged();

            Throwable lastError = null;
            boolean completed = false;

            for (int mIndex = 0; mIndex < manifests.size() && !completed; mIndex++) {
                ApiClient.HlsManifest manifest = manifests.get(mIndex);
                int totalSegments = manifest.segments.size();

                // Every mirror is a separate media stream. Never append bytes from one
                // provider to another provider's partial TS file.
                if (mIndex > 0) {
                    if (tempTsFile.exists()) tempTsFile.delete();
                    if (hlsCheckpointFile.exists()) hlsCheckpointFile.delete();
                    if (tempSubFile.exists()) tempSubFile.delete();
                    if (partFile.exists()) partFile.delete();
                    item.setDownloadedSegments(0);
                    item.setEta("Retrying source...");
                    notifyUpdated(item);
                }

                // The checkpoint is authoritative; never trust the saved UI counter alone.
                int startSegment = restoreHlsCheckpoint(tempTsFile, hlsCheckpointFile, manifest, totalSegments);
                item.setDownloadedSegments(startSegment);
                boolean append = startSegment > 0 && tempTsFile.exists();
                if (!append) {
                    if (tempTsFile.exists()) tempTsFile.delete();
                    saveHlsCheckpoint(tempTsFile, hlsCheckpointFile, manifest, 0);
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
                boolean mediaDownloaded = false;
                File segmentDir = new File(tempTsFile.getParentFile(), tempTsFile.getName() + ".segments");
                deleteRecursively(segmentDir);
                if (!segmentDir.exists() && !segmentDir.mkdirs()) {
                    throw new Exception("Could not create temporary segment directory.");
                }

                ExecutorService segmentExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_SEGMENTS);
                try (FileOutputStream out = new FileOutputStream(tempTsFile, append)) {
                    int committedSegments = startSegment;
                    while (committedSegments < totalSegments) {
                        if (item.getStatus() == DownloadStatus.PAUSED) {
                            item.setDownloadedSegments(committedSegments);
                            notifyUpdated(item);
                            notifyQueueChanged();
                            return;
                        }
                        if (item.getStatus() == DownloadStatus.CANCELLED) {
                            out.flush();
                            if (tempTsFile.exists()) tempTsFile.delete();
                            if (hlsCheckpointFile.exists()) hlsCheckpointFile.delete();
                            if (tempSubFile.exists()) tempSubFile.delete();
                            if (partFile.exists()) partFile.delete();
                            return;
                        }

                        final int batchStart = committedSegments;
                        final int batchEnd = Math.min(totalSegments, batchStart + MAX_PARALLEL_SEGMENTS);
                        List<Future<?>> futures = new ArrayList<>();
                        List<File> batchFiles = new ArrayList<>();

                        for (int i = batchStart; i < batchEnd; i++) {
                            final int segmentIndex = i;
                            File segmentFile = new File(segmentDir, String.format(java.util.Locale.ROOT, "segment_%08d.bin", segmentIndex));
                            if (segmentFile.exists()) segmentFile.delete();
                            batchFiles.add(segmentFile);
                            futures.add(segmentExecutor.submit(() -> {
                                downloadHlsSegmentToFile(item, manifest, manifest.segments.get(segmentIndex),
                                        segmentIndex, hlsKeyCache, segmentFile);
                                return null;
                            }));
                        }

                        try {
                            for (Future<?> future : futures) future.get();
                        } catch (Exception batchError) {
                            for (Future<?> future : futures) future.cancel(true);
                            throw batchError;
                        }

                        // Only append a complete batch, in playlist order. This keeps the
                        // .part.ts file resumable and prevents parallel workers corrupting it.
                        if (item.getStatus() == DownloadStatus.PAUSED) {
                            item.setDownloadedSegments(committedSegments);
                            notifyUpdated(item);
                            notifyQueueChanged();
                            return;
                        }
                        if (item.getStatus() == DownloadStatus.CANCELLED) {
                            if (tempTsFile.exists()) tempTsFile.delete();
                            if (hlsCheckpointFile.exists()) hlsCheckpointFile.delete();
                            if (tempSubFile.exists()) tempSubFile.delete();
                            if (partFile.exists()) partFile.delete();
                            return;
                        }

                        long batchFileLength = tempTsFile.length();
                        try {
                            byte[] copyBuffer = new byte[64 * 1024];
                            for (int j = 0; j < batchFiles.size(); j++) {
                                File segmentFile = batchFiles.get(j);
                                if (!segmentFile.isFile() || segmentFile.length() <= 0) {
                                    throw new Exception("Missing or empty HLS segment " + (batchStart + j));
                                }
                                try (InputStream in = new FileInputStream(segmentFile)) {
                                    int read;
                                    while ((read = in.read(copyBuffer)) != -1) out.write(copyBuffer, 0, read);
                                }
                                bytesDownloadedThisSession += segmentFile.length();
                                int doneIndex = batchStart + j + 1;
                                item.setProgress(Math.min(99, Math.max(1, (int) ((doneIndex / (double) totalSegments) * 100.0))));
                            }
                            out.flush();
                            out.getFD().sync();
                            committedSegments = batchEnd;
                            // Commit the checkpoint only after the media bytes are durable.
                            saveHlsCheckpoint(tempTsFile, hlsCheckpointFile, manifest, committedSegments);
                            item.setDownloadedSegments(committedSegments);
                            item.setSize(FormatUtil.formatSize(tempTsFile.length()));
                            for (File segmentFile : batchFiles) segmentFile.delete();
                        } catch (Exception appendError) {
                            // Roll back a failed batch so its bytes can never be mistaken for committed segments.
                            out.flush();
                            try (java.io.RandomAccessFile rollback = new java.io.RandomAccessFile(tempTsFile, "rw")) {
                                rollback.setLength(batchFileLength);
                                rollback.getFD().sync();
                            }
                            throw appendError;
                        }

                        long elapsedMs = System.currentTimeMillis() - startTime;
                        if (elapsedMs > 0) {
                            double bytesPerSec = (bytesDownloadedThisSession / (double) elapsedMs) * 1000.0;
                            item.setSpeed(FormatUtil.formatSize((long) bytesPerSec) + "/s");
                            int remainingSegs = totalSegments - committedSegments;
                            double avgSecPerSeg = (elapsedMs / 1000.0) / Math.max(1, committedSegments - startSegment);
                            item.setEta(FormatUtil.formatTime((long) (remainingSegs * avgSecPerSeg)));
                        }
                        notifyUpdated(item);
                    }
                    out.flush();
                    mediaDownloaded = tempTsFile.exists() && tempTsFile.length() > 0;
                } catch (Throwable t) {
                    lastError = t;
                    Log.w(TAG, "Media source index " + mIndex + " failed: " + t.getMessage());
                } finally {
                    segmentExecutor.shutdownNow();
                    deleteRecursively(segmentDir);
                }

                if (item.getStatus() == DownloadStatus.PAUSED || item.getStatus() == DownloadStatus.CANCELLED) {
                    return;
                }

                if (!mediaDownloaded) {
                    if (mIndex + 1 < manifests.size()) continue;
                    throw (lastError != null ? lastError : new Exception("All media sources failed."));
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
                boolean remuxSuccess = ReturnCode.isSuccess(session.getReturnCode())
                        && partFile.exists() && partFile.length() > 0;

                if (!remuxSuccess) {
                    lastError = new Exception("Media finalization failed for source " + (mIndex + 1) + ".");
                    Log.w(TAG, "FFmpeg failed for media source index " + mIndex);
                    if (partFile.exists()) partFile.delete();
                    if (tempTsFile.exists()) tempTsFile.delete();
                    if (hlsCheckpointFile.exists()) hlsCheckpointFile.delete();
                    if (tempSubFile.exists()) tempSubFile.delete();
                    item.setDownloadedSegments(0);

                    if (mIndex + 1 < manifests.size()) {
                        item.setEta("Retrying source...");
                        notifyUpdated(item);
                        continue;
                    }

                    throw lastError;
                }

                if (!partFile.renameTo(finalMp4)) {
                    throw new Exception("Unable to finalize the downloaded video.");
                }

                tempTsFile.delete();
                hlsCheckpointFile.delete();
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
                completed = true;
            }

            if (!completed) {
                throw (lastError != null ? lastError : new Exception("All media sources failed."));
            }

            notifyUpdated(item);
            notifyQueueChanged();
            checkAndStartNextQueued();

        } catch (Throwable t) {
            Log.e(TAG, "Download failed for " + item.getAnimeTitle() + " episode " + item.getEpisodeName(), t);
            if (partFile.exists()) partFile.delete();
            if (tempTsFile.exists()) tempTsFile.delete();
            if (hlsCheckpointFile.exists()) hlsCheckpointFile.delete();
            if (tempSubFile.exists()) tempSubFile.delete();

            int retries = automaticRetryCounts.getOrDefault(item.getId(), 0);
            if (retries < 1) {
                automaticRetryCounts.put(item.getId(), retries + 1);
                item.setStatus(DownloadStatus.RESOLVING);
                item.setError("Download failed: " + readableDownloadError(t) + " Retrying once…");
                item.setProgress(0);
                notifyUpdated(item);
                notifyQueueChanged();
                executor.execute(() -> {
                    try { Thread.sleep(1200L); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        automaticRetryCounts.remove(item.getId());
                        return;
                    }
                    if (item.getStatus() == DownloadStatus.CANCELLED || item.getStatus() == DownloadStatus.PAUSED) {
                        automaticRetryCounts.remove(item.getId());
                        return;
                    }
                    executeDownload(item);
                });
                return;
            }

            automaticRetryCounts.remove(item.getId());
            item.setStatus(DownloadStatus.FAILED);
            item.setError(readableDownloadError(t));
            notifyUpdated(item);
            notifyQueueChanged();
            checkAndStartNextQueued();
        }
    }

    /**
     * Converts low-level failures into a message suitable for the download queue without exposing unnecessary implementation details.
     * 
     * @param error failure to describe
     * @return user-facing error text
     */
    private String readableDownloadError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "Unknown error. Check your connection and retry manually.";
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("unable to resolve host") || lower.contains("failed to connect") || lower.contains("network is unreachable")) return "Network connection failed. Check your internet connection.";
        if (lower.contains("timed out")) return "The request timed out. Check your connection and try again.";
        if (lower.contains("http 403")) return "The video provider blocked the request (HTTP 403). It may be temporary.";
        if (lower.contains("http 404")) return "The requested video source was not found (HTTP 404).";
        if (lower.contains("http 5")) return "The video provider is temporarily unavailable (server error).";
        return message;
    }

    /**
     * Copies a file using the manager's stream handling for finalization and recovery paths.
     * 
     * @param src source file
     * @param dst destination file
     */
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