package hh.kensakudl.app.util;

/**
 * Provides consistent filename sanitization and human-readable size and duration formatting for download-related UI and storage paths.
 */
public class FormatUtil {
    /**
     * Replaces filename-reserved characters and collapses whitespace while keeping the human-readable title.
     * 
     * @param name raw title or filename component
     * @return sanitized name, or {@code Unknown} for {@code null}
     */
    public static String cleanName(String name) {
        if (name == null) return "Unknown";
        return name.replaceAll("[/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
    }

    /**
     * Extracts the first standalone numeric episode identifier and pads it to at least two digits; labels without a number are sanitized and retained.
     * 
     * @param epName episode title or label
     * @return filesystem-safe episode component
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
     * Formats a byte count using B, KB, MB, or GB units.
     * 
     * @param bytes size in bytes
     * @return compact human-readable size
     */
    public static String formatSize(long bytes) {
        double b = bytes;
        if (b < 1024) return String.format("%.0f B", b);
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024 * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024.0));
        return String.format("%.2f GB", b / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Formats a duration as {@code mm:ss} or {@code h:mm:ss}, clamping negative values to zero.
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
