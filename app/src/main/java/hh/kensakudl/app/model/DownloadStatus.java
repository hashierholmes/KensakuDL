package hh.kensakudl.app.model;

/**
 * Represents the externally visible lifecycle states of an episode in the download queue.
 */
public enum DownloadStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    SKIPPED,
    CANCELLED
}
