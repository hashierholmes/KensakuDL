package hh.kensakudl.app.model;

import java.io.Serializable;

/**
 * Serializable state for one episode download, including its stable queue identity,
 * selected audio track, progress metrics, output path, and failure details.
 */
public class DownloadItem implements Serializable {
    private final String id;
    private final String animeTitle;
    private final String episodeName;
    private final String episodeSlug;
    private final String episodeLink;
    private final String track;

    private DownloadStatus status;
    private int progress;
    private String speed;
    private String size;
    private String eta;
    private String error;
    private String outputPath;
    private int downloadedSegments;

    /** Creates a queued item using the default subtitle track. */
    public DownloadItem(String id, String animeTitle, String episodeName, String episodeSlug, String episodeLink) {
        this(id, animeTitle, episodeName, episodeSlug, episodeLink, "sub");
    }

    /**
     * Creates a queued item for a specific audio track.
     *
     * @param id stable queue identifier
     * @param animeTitle series title used for grouping and output naming
     * @param episodeName display label for the episode
     * @param episodeSlug provider episode slug
     * @param episodeLink provider episode reference used for source resolution
     * @param track requested track; values other than {@code dub} are exposed as subtitle track
     */
    public DownloadItem(String id, String animeTitle, String episodeName, String episodeSlug, String episodeLink, String track) {
        this.id = id;
        this.animeTitle = animeTitle;
        this.episodeName = episodeName;
        this.episodeSlug = episodeSlug;
        this.episodeLink = episodeLink;
        this.track = track;
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
    public String getTrack() { return "dub".equalsIgnoreCase(track) ? "dub" : "sub"; }
    public String getTrackLabel() { return "dub".equalsIgnoreCase(track) ? "Dub" : "Sub"; }
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