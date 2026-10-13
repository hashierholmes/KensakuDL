package hh.kensakudl.app.util;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import java.io.File;

/**
 * Centralizes KensakuDL output paths and temporary-file naming, and requests media indexing for completed or intermediate files where needed.
 */
public class FileUtil {

    /**
     * Returns the series-specific directory under {@code Download/KensakuDL}, creating it if necessary.
     * 
     * @param animeTitle series title used to derive the directory name
     * @return series download directory
     */
    public static File getKensakuDir(String animeTitle) {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File kensakuDir = new File(downloadDir, "KensakuDL/" + FormatUtil.cleanName(animeTitle));
        if (!kensakuDir.exists()) {
            kensakuDir.mkdirs();
        }
        return kensakuDir;
    }

    /**
     * Builds the temporary MP4 path used while an episode is being finalized, using the default subtitle track.
     * 
     * @param context context used for media scanning
     * @param animeTitle series title
     * @param episodeNumber episode label or number
     * @return temporary MP4 file
     */
    public static File getPartFile(Context context, String animeTitle, String episodeNumber) {
        return getPartFile(context, animeTitle, episodeNumber, "sub");
    }

    /**
     * Builds the temporary MP4 path used while an episode is being finalized. The track is included in the filename to keep sub and dub output separate.
     * 
     * @param context context used for media scanning
     * @param animeTitle series title
     * @param episodeNumber episode label or number
     * @param track audio track
     * @return temporary MP4 file
     */
    public static File getPartFile(Context context, String animeTitle, String episodeNumber, String track) {
        File dir = getKensakuDir(animeTitle);
        String fileName = trackPrefix(track) + FormatUtil.cleanName(animeTitle)
                + "_Episodes_" + FormatUtil.safeEpisodeNumber(episodeNumber) + ".part.mp4";
        File file = new File(dir, fileName);
        MediaScannerConnection.scanFile(context, new String[]{dir.getAbsolutePath(), file.getAbsolutePath()}, null, null);
        return file;
    }

    /**
     * Builds the temporary transport-stream path for the default subtitle track.
     * 
     * @param animeTitle series title
     * @param episodeNumber episode label or number
     * @return temporary TS file
     */
    public static File getTempTsFile(String animeTitle, String episodeNumber) {
        return getTempTsFile(animeTitle, episodeNumber, "sub");
    }

    /**
     * Builds the temporary transport-stream path used while HLS segments are assembled.
     * 
     * @param animeTitle series title
     * @param episodeNumber episode label or number
     * @param track audio track
     * @return temporary TS file
     */
    public static File getTempTsFile(String animeTitle, String episodeNumber, String track) {
        File dir = getKensakuDir(animeTitle);
        String fileName = trackPrefix(track) + FormatUtil.cleanName(animeTitle)
                + "_Episodes_" + FormatUtil.safeEpisodeNumber(episodeNumber) + ".part.ts";
        return new File(dir, fileName);
    }

    /**
     * Builds the temporary WebVTT subtitle path for the default subtitle track.
     * 
     * @param animeTitle series title
     * @param episodeNumber episode label or number
     * @return temporary subtitle file
     */
    public static File getTempSubFile(String animeTitle, String episodeNumber) {
        return getTempSubFile(animeTitle, episodeNumber, "sub");
    }

    /**
     * Builds the temporary WebVTT subtitle path for an episode and track.
     * 
     * @param animeTitle series title
     * @param episodeNumber episode label or number
     * @param track audio track
     * @return temporary subtitle file
     */
    public static File getTempSubFile(String animeTitle, String episodeNumber, String track) {
        File dir = getKensakuDir(animeTitle);
        String fileName = trackPrefix(track) + FormatUtil.cleanName(animeTitle)
                + "_Episodes_" + FormatUtil.safeEpisodeNumber(episodeNumber) + ".part.vtt";
        return new File(dir, fileName);
    }

    /**
     * Builds the final MP4 output path for the default subtitle track.
     * 
     * @param animeTitle series title
     * @param episodeNumber episode label or number
     * @return final MP4 file
     */
    public static File getFinalMp4File(String animeTitle, String episodeNumber) {
        return getFinalMp4File(animeTitle, episodeNumber, "sub");
    }

    /**
     * Builds the final MP4 output path for the requested episode and track.
     * 
     * @param animeTitle series title
     * @param episodeNumber episode label or number
     * @param track audio track
     * @return final MP4 file
     */
    public static File getFinalMp4File(String animeTitle, String episodeNumber, String track) {
        File dir = getKensakuDir(animeTitle);
        String fileName = trackPrefix(track) + FormatUtil.cleanName(animeTitle)
                + "_Episodes_" + FormatUtil.safeEpisodeNumber(episodeNumber) + ".mp4";
        return new File(dir, fileName);
    }

    /**
     * Builds the final WebVTT subtitle path for the default subtitle track.
     * 
     * @param animeTitle series title
     * @param episodeNumber episode label or number
     * @return final VTT file
     */
    public static File getFinalVttFile(String animeTitle, String episodeNumber) {
        return getFinalVttFile(animeTitle, episodeNumber, "sub");
    }

    /**
     * Builds the final standalone WebVTT subtitle path for the requested episode and track.
     * 
     * @param animeTitle series title
     * @param episodeNumber episode label or number
     * @param track audio track
     * @return final VTT file
     */
    public static File getFinalVttFile(String animeTitle, String episodeNumber, String track) {
        File dir = getKensakuDir(animeTitle);
        String fileName = trackPrefix(track) + FormatUtil.cleanName(animeTitle)
                + "_Episodes_" + FormatUtil.safeEpisodeNumber(episodeNumber) + ".vtt";
        return new File(dir, fileName);
    }

    private static String trackPrefix(String track) {
        return "dub".equalsIgnoreCase(track) ? "Dub - " : "Sub - ";
    }

    /**
     * Requests Android media indexing for the supplied file so it can become visible to media-aware apps.
     * 
     * @param context context used for the scan request
     * @param file file to index
     */
    public static void scanMedia(Context context, File file) {
        if (file != null && file.exists()) {
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        }
    }
}
