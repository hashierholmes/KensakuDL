package hh.kensakudl.app.model;

import java.io.Serializable;
/**
 * Mutable state object representing one episode in the download queue.
 *
 * <p>It contains both the immutable episode identity used to resolve a download and the
 * progress metadata displayed by the queue UI and notifications.</p>
 */

public class DownloadItem implements Serializable {
    private final String id;
    private final String animeTitle;
    private final String episodeName;
    private final String episodeSlug;
    private final String episodeLink;

    private DownloadStatus status;
    private int progress;
    private String speed;
    private String size;
    private String eta;
    private String error;
    private String outputPath;
    private int downloadedSegments;

    public DownloadItem(String id, String animeTitle, String episodeName, String episodeSlug, String episodeLink) {
        this.id = id;
        this.animeTitle = animeTitle;
        this.episodeName = episodeName;
        this.episodeSlug = episodeSlug;
        this.episodeLink = episodeLink;
        this.status = DownloadStatus.QUEUED;
        this.progress = 0;
        this.speed = "N/A";
        this.size = "0 B";
        this.eta = "--:--";
        this.downloadedSegments = 0;
    }

    public String getId() { return id; }
    public String getAnimeTitle() { return animeTitle; }
    public String getEpisodeName() { return episodeName; }
    public String getEpisodeSlug() { return episodeSlug; }
    public String getEpisodeLink() { return episodeLink; }
    public DownloadStatus getStatus() { return status; }
    public void setStatus(DownloadStatus status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public String getSpeed() { return speed; }
    public void setSpeed(String speed) { this.speed = speed; }
    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }
    public String getEta() { return eta; }
    public void setEta(String eta) { this.eta = eta; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
    public int getDownloadedSegments() { return downloadedSegments; }
    public void setDownloadedSegments(int downloadedSegments) { this.downloadedSegments = downloadedSegments; }
}