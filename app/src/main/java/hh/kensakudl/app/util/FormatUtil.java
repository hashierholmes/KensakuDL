package hh.kensakudl.app.util;
/**
 * Small formatting and filename-sanitization utilities shared across the application.
 */

public class FormatUtil {
/**
 * Sanitizes a title for use as a filesystem path component.
 *
 * @param name source title
 * @return filesystem-safe title
 */
    public static String cleanName(String name) {
        if (name == null) return "Unknown";
        return name.replaceAll("[/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
    }
/**
 * Extracts and normalizes the first numeric episode value from a display name.
 *
 * @param epName episode display name
 * @return two-digit episode number when numeric data is available
 */

    public static String safeEpisodeNumber(String epName) {
        if (epName == null) return "00";
        String clean = epName.replaceAll("[/:*?\"<>|]", "_").trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b(\\d+)\\b").matcher(clean);
        if (matcher.find()) {
            try {
                return String.format("%02d", Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {}
        }
        return clean;
    }
/**
 * Formats a byte count using a human-readable unit.
 *
 * @param bytes size in bytes
 * @return formatted size
 */

    public static String formatSize(long bytes) {
        double b = bytes;
        if (b < 1024) return String.format("%.0f B", b);
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024 * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024.0));
        return String.format("%.2f GB", b / (1024.0 * 1024.0 * 1024.0));
    }
/**
 * Formats a duration in seconds as {@code MM:SS} or {@code H:MM:SS}.
 *
 * @param seconds duration in seconds
 * @return formatted duration
 */

    public static String formatTime(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }
}
