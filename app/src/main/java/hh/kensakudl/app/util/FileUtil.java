package hh.kensakudl.app.util;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import java.io.File;
/**
 * Defines KensakuDL's download paths and media-scanning helpers.
 *
 * <p>Temporary and completed episode artifacts share a deterministic naming scheme so
 * interrupted downloads can be resumed or cleaned up without additional metadata.</p>
 */

public class FileUtil {
/**
 * Returns the series directory used for KensakuDL downloads, creating it when necessary.
 *
 * @param animeTitle series title
 * @return download directory
 */

    public static File getKensakuDir(String animeTitle) {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File kensakuDir = new File(downloadDir, "KensakuDL/" + FormatUtil.cleanName(animeTitle));
        if (!kensakuDir.exists()) {
            kensakuDir.mkdirs();
        }
        return kensakuDir;
    }

    public static File getPartFile(Context context, String animeTitle, String episodeNumber) {
        File dir = getKensakuDir(animeTitle);
        String fileName = FormatUtil.cleanName(animeTitle) + "_Episode_" + FormatUtil.safeEpisodeNumber(episodeNumber) + ".part.mp4";
        File file = new File(dir, fileName);
        MediaScannerConnection.scanFile(context, new String[]{dir.getAbsolutePath(), file.getAbsolutePath()}, null, null);
        return file;
    }

    public static File getTempTsFile(String animeTitle, String episodeNumber) {
        File dir = getKensakuDir(animeTitle);
        String fileName = FormatUtil.cleanName(animeTitle) + "_Episode_" + FormatUtil.safeEpisodeNumber(episodeNumber) + ".part.ts";
        return new File(dir, fileName);
    }

    public static File getTempSubFile(String animeTitle, String episodeNumber) {
        File dir = getKensakuDir(animeTitle);
        String fileName = FormatUtil.cleanName(animeTitle) + "_Episode_" + FormatUtil.safeEpisodeNumber(episodeNumber) + ".part.vtt";
        return new File(dir, fileName);
    }
/**
 * Returns the final MP4 path for an episode.
 *
 * @param animeTitle series title
 * @param episodeNumber episode identifier used in the filename
 * @return final MP4 file path
 */

    public static File getFinalMp4File(String animeTitle, String episodeNumber) {
        File dir = getKensakuDir(animeTitle);
        String fileName = FormatUtil.cleanName(animeTitle) + "_Episode_" + FormatUtil.safeEpisodeNumber(episodeNumber) + ".mp4";
        return new File(dir, fileName);
    }
/**
 * Returns the final WebVTT subtitle path for an episode.
 *
 * @param animeTitle series title
 * @param episodeNumber episode identifier used in the filename
 * @return final subtitle file path
 */

    public static File getFinalVttFile(String animeTitle, String episodeNumber) {
        File dir = getKensakuDir(animeTitle);
        String fileName = FormatUtil.cleanName(animeTitle) + "_Episode_" + FormatUtil.safeEpisodeNumber(episodeNumber) + ".vtt";
        return new File(dir, fileName);
    }
/**
 * Notifies Android's media scanner about a completed video file.
 *
 * @param context context used to request the scan
 * @param file media file to scan
 */

    public static void scanMedia(Context context, File file) {
        if (file != null && file.exists()) {
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        }
    }
}