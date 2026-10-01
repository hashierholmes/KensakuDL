package hh.kensakudl.app.model;
/**
 * Lifecycle states used by the download queue and its UI.
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
