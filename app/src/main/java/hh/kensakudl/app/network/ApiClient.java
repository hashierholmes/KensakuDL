package hh.kensakudl.app.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hh.kensakudl.app.model.AnimeItem;
import hh.kensakudl.app.model.EpisodeItem;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
/**
 * Network boundary for anime metadata and playback-source resolution.
 *
 * <p>The client centralizes HTTP configuration, API response decoding, episode lookup,
 * and HLS manifest resolution so UI and download code do not need to know the provider's
 * response format.</p>
 */

public class ApiClient {
    public static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.7922.200 Mobile Safari/537.36";

    private static final String BASE_API = "https://miruro.to/api/v1/anime";
    private static final String MIRURO_API_FALLBACK = "https://www.miruro.to/api/v1/anime";
    private static final String XOR_KEY = "miruro/catalog";
    private static final String MIRURO_ORIGIN = "https://www.miruro.to";

    private static final String TRACK_SUB = "sub";
    private static final String PROVIDER_ANIWAVES = "aniwaves";
    private static final String SERVER_VIDPLAY = "Vidplay";
    private static final String FORMAT_HLS = "hls";

    private static ApiClient instance;
    private final OkHttpClient client;

    private ApiClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build();
    }
/**
 * Returns the shared network client.
 *
 * @return singleton API client
 */

    public static synchronized ApiClient getInstance() {
        if (instance == null) instance = new ApiClient();
        return instance;
    }
/**
 * Exposes the configured OkHttp client for download operations that share the same
 * connection settings.
 *
 * @return configured HTTP client
 */

    public OkHttpClient getHttpClient() {
        return client;
    }
/**
 * Searches the remote catalog for matching anime.
 *
 * @param keyword user-provided search text
 * @return matching anime metadata
 * @throws IOException when the request or response processing fails
 */

    public List<AnimeItem> searchAnime(String keyword) throws IOException {
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name());
        String url = BASE_API + "?q=" + encoded + "&limit=15&sort=-popularity";

        JsonObject root = getDecodedJson(url);
        JsonArray data = root.has("data") && root.get("data").isJsonArray()
                ? root.getAsJsonArray("data") : new JsonArray();

        List<AnimeItem> result = new ArrayList<>();
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            AnimeItem item = parseAnime(element.getAsJsonObject());
            if (!item.getId().isEmpty()) result.add(item);
        }
        return result;
    }
/**
 * Retrieves the available episodes for an anime identifier.
 *
 * @param animeId remote anime identifier
 * @return available episodes
 * @throws IOException when the request or response processing fails
 */

    public List<EpisodeItem> getEpisodes(String animeId) throws IOException {
        if (animeId == null || animeId.trim().isEmpty()) {
            throw new IOException("Missing Miruro anime ID.");
        }

        
        
        try {
            List<EpisodeItem> result = getEpisodesByKind(animeId, "regular");
            if (!result.isEmpty()) return result;
        } catch (IOException ignored) {
            
        }

        return getEpisodesByKind(animeId, "film");
    }

    private List<EpisodeItem> getEpisodesByKind(String animeId, String kind) throws IOException {
        String url = BASE_API + "/" + encodePath(animeId)
                + "/episodes?kind=" + URLEncoder.encode(kind, StandardCharsets.UTF_8.name())
                + "&limit=10000";

        JsonObject root = getDecodedJson(url);
        JsonArray data = root.has("data") && root.get("data").isJsonArray()
                ? root.getAsJsonArray("data") : new JsonArray();

        List<EpisodeItem> result = new ArrayList<>();
        Set<Integer> seenEpisodeNumbers = new HashSet<>();
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();

            EpisodeItem item = new EpisodeItem();
            int number = getInt(obj, "episode_number");
            item.setEpisodeNumber(number);
            item.setAbsoluteNumber(getInt(obj, "absolute_number"));
            item.setAnimeId(animeId);
            item.setName(String.valueOf(number));
            item.setTitle(getString(obj, "title"));
            item.setSlug(slugify(item.getTitle()));
            item.setSynopsis(getString(obj, "synopsis"));
            item.setThumbnailUrl(getString(obj, "thumbnail_url"));
            item.setAiredOn(getString(obj, "aired_on"));
            item.setDurationSeconds(getInt(obj, "duration_seconds"));
            item.setKind(getString(obj, "kind"));
            item.setCanonType(getString(obj, "canon_type"));
            item.setLink(buildPlayUrl(animeId, number));

            if (number > 0 && seenEpisodeNumbers.add(number)) result.add(item);
        }

        Collections.sort(result, Comparator.comparingInt(EpisodeItem::getEpisodeNumber));
        return result;
    }

    public static class HlsSegment {
        public final String url;
        public final double duration;

        public HlsSegment(String url, double duration) {
            this.url = url;
            this.duration = duration;
        }
    }

    public static class HlsManifest {
        public final List<HlsSegment> segments;
        public final double totalDuration;
        public final String playerUrl;
        public final String subtitleUrl;

        public HlsManifest(List<HlsSegment> segments, double totalDuration,
                           String playerUrl, String subtitleUrl) {
            this.segments = segments;
            this.totalDuration = totalDuration;
            this.playerUrl = playerUrl;
            this.subtitleUrl = subtitleUrl;
        }
    }
/**
 * Resolves the playback endpoint into the HLS manifests used by the downloader and player.
 *
 * @param episodeLink episode page or playback link
 * @return resolved HLS manifests
 * @throws IOException when resolution or manifest retrieval fails
 */

    
    public List<HlsManifest> resolveAllHlsManifests(String episodeLink) throws IOException {
        if (episodeLink == null || episodeLink.trim().isEmpty()) {
            throw new IOException("Missing Miruro play URL.");
        }

        JsonObject root = getDecodedJson(episodeLink);
        JsonArray tracks = root.has("tracks") && root.get("tracks").isJsonArray()
                ? root.getAsJsonArray("tracks") : new JsonArray();

        JsonObject selectedServer = null;
        String selectedEmbedUrl = null;
        String selectedReferer = null;
        String selectedStreamUrl = null;

        for (JsonElement trackElement : tracks) {
            if (!trackElement.isJsonObject()) continue;
            JsonObject track = trackElement.getAsJsonObject();
            if (!TRACK_SUB.equalsIgnoreCase(getString(track, "track"))) continue;

            JsonArray providers = track.has("providers") && track.get("providers").isJsonArray()
                    ? track.getAsJsonArray("providers") : new JsonArray();

            for (JsonElement providerElement : providers) {
                if (!providerElement.isJsonObject()) continue;
                JsonObject provider = providerElement.getAsJsonObject();
                if (!PROVIDER_ANIWAVES.equalsIgnoreCase(getString(provider, "provider"))) continue;

                JsonArray servers = provider.has("servers") && provider.get("servers").isJsonArray()
                        ? provider.getAsJsonArray("servers") : new JsonArray();

                for (JsonElement serverElement : servers) {
                    if (!serverElement.isJsonObject()) continue;
                    JsonObject server = serverElement.getAsJsonObject();
                    if (!SERVER_VIDPLAY.equalsIgnoreCase(getString(server, "server"))) continue;

                    JsonArray streams = server.has("streams") && server.get("streams").isJsonArray()
                            ? server.getAsJsonArray("streams") : new JsonArray();

                    for (JsonElement streamElement : streams) {
                        if (!streamElement.isJsonObject()) continue;
                        JsonObject stream = streamElement.getAsJsonObject();
                        if (!FORMAT_HLS.equalsIgnoreCase(getString(stream, "format"))) continue;

                        String url = getString(stream, "url");
                        if (url.isEmpty()) continue;

                        selectedServer = server;
                        selectedStreamUrl = url;
                        selectedEmbedUrl = getEmbedUrl(server);

                        JsonObject headers = server.has("headers") && server.get("headers").isJsonObject()
                                ? server.getAsJsonObject("headers") : null;
                        selectedReferer = headers != null ? getString(headers, "Referer") : "";
                        if (selectedReferer.isEmpty()) selectedReferer = "https://play.echovideo.ru/";
                        break;
                    }
                    if (selectedStreamUrl != null) break;
                }
                if (selectedStreamUrl != null) break;
            }
            if (selectedStreamUrl != null) break;
        }

        if (selectedServer == null || selectedStreamUrl == null) {
            throw new IOException("SUB AniWaves Vidplay HLS stream is unavailable.");
        }

        String subtitleUrl = findSubtitleUrl(selectedEmbedUrl, selectedReferer);
        HlsManifest manifest = fetchAndParseHls(selectedStreamUrl, selectedReferer, subtitleUrl);
        if (manifest == null || manifest.segments.isEmpty()) {
            throw new IOException("Vidplay HLS playlist is empty or unavailable.");
        }

        List<HlsManifest> result = new ArrayList<>();
        result.add(manifest);
        return result;
    }

    private HlsManifest fetchAndParseHls(String hlsUrl, String referer, String subtitleUrl) throws IOException {
        Request request = new Request.Builder()
                .url(hlsUrl)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .header("Accept", "*/*")
                .build();

        String playlist;
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HLS request failed: " + response.code());
            }
            playlist = response.body().string();
        }

        if (!playlist.contains("#EXTM3U")) {
            throw new IOException("Invalid HLS playlist.");
        }

        if (playlist.contains("#EXT-X-STREAM-INF:")) {
            String bestChildUrl = null;
            long maxBandwidth = -1;
            String[] lines = playlist.split("\\r?\\n");

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;

                long bandwidth = 0;
                Matcher bandwidthMatcher = Pattern.compile("BANDWIDTH=(\\d+)").matcher(line);
                if (bandwidthMatcher.find()) {
                    try {
                        bandwidth = Long.parseLong(bandwidthMatcher.group(1));
                    } catch (Exception ignored) {}
                }

                for (int j = i + 1; j < lines.length; j++) {
                    String child = lines[j].trim();
                    if (child.isEmpty() || child.startsWith("#")) continue;
                    if (bandwidth > maxBandwidth || bestChildUrl == null) {
                        maxBandwidth = bandwidth;
                        bestChildUrl = resolveUrl(hlsUrl, child);
                    }
                    break;
                }
            }

            if (bestChildUrl != null) {
                return fetchAndParseHls(bestChildUrl, referer, subtitleUrl);
            }
        }

        List<HlsSegment> segments = new ArrayList<>();
        double currentDuration = 0.0;
        double totalDuration = 0.0;

        for (String rawLine : playlist.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#EXTINF:")) {
                try {
                    String value = line.substring(8).split(",", 2)[0].trim();
                    currentDuration = Double.parseDouble(value);
                } catch (Exception ignored) {
                    currentDuration = 0.0;
                }
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String segmentUrl = resolveUrl(hlsUrl, line);
                segments.add(new HlsSegment(segmentUrl, currentDuration));
                totalDuration += currentDuration;
                currentDuration = 0.0;
            }
        }

        if (segments.isEmpty()) return null;
        return new HlsManifest(segments, totalDuration, referer, subtitleUrl);
    }

    private String findSubtitleUrl(String embedUrl, String referer) {
        if (embedUrl == null || embedUrl.isEmpty()) return null;

        try {
            Request request = new Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", UA)
                    .header("Referer", MIRURO_ORIGIN + "/")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build();

            String html;
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                html = response.body().string();
            }

            html = html.replace("\\/", "/").replace("\\u0026", "&");

            Pattern[] patterns = new Pattern[]{
                    Pattern.compile("https?://[^\\\"'\\s<>]+/sub\\.vtt(?:\\?[^\\\"'\\s<>]*)?", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("https?://[^\\\"'\\s<>]+sub\\.vtt(?:\\?[^\\\"'\\s<>]*)?", Pattern.CASE_INSENSITIVE)
            };

            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(html);
                if (matcher.find()) return matcher.group(0);
            }
        } catch (Exception ignored) {}

        return null;
    }

    private JsonObject getDecodedJson(String url) throws IOException {
        IOException firstError = null;
        String fallback = getFallbackUrl(url);
        String[] candidates = fallback == null ? new String[] { url } : new String[] { url, fallback };

        for (String candidate : candidates) {
            try {
                Request request = new Request.Builder()
                        .url(candidate)
                        .header("User-Agent", UA)
                        .header("Accept", "application/octet-stream, application/json, text/plain, */*")
                        .header("Referer", MIRURO_ORIGIN + "/")
                        .build();

                byte[] body;
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("Miruro request failed: " + response.code());
                    }
                    body = response.body().bytes();
                }

                return JsonParser.parseString(decodeResponse(body)).getAsJsonObject();
            } catch (IOException e) {
                if (firstError == null) firstError = e;
            }
        }

        throw firstError != null ? firstError : new IOException("Unable to reach service.");
    }

    private String getFallbackUrl(String url) {
        if (url == null) return null;
        if (url.startsWith(BASE_API)) {
            return MIRURO_API_FALLBACK + url.substring(BASE_API.length());
        }
        if (url.startsWith(MIRURO_API_FALLBACK)) {
            return BASE_API + url.substring(MIRURO_API_FALLBACK.length());
        }
        return null;
    }

    private String decodeResponse(byte[] encrypted) throws IOException {
        if (encrypted == null || encrypted.length == 0) {
            throw new IOException("Empty Miruro response.");
        }

        byte[] xored = new byte[encrypted.length];
        byte[] key = XOR_KEY.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < encrypted.length; i++) {
            xored[i] = (byte) (encrypted[i] ^ key[i % key.length]);
        }

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(xored));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private AnimeItem parseAnime(JsonObject obj) {
        AnimeItem item = new AnimeItem();
        item.setId(getString(obj, "id"));

        JsonObject external = obj.has("external_ids") && obj.get("external_ids").isJsonObject()
                ? obj.getAsJsonObject("external_ids") : null;
        item.setAnilist(firstExternalId(external, "anilist"));
        item.setMal(firstExternalId(external, "mal"));

        JsonObject title = obj.has("title") && obj.get("title").isJsonObject()
                ? obj.getAsJsonObject("title") : null;
        String english = title != null ? getString(title, "english") : "";
        String romaji = title != null ? getString(title, "romaji") : "";
        item.setPostName(!english.isEmpty() ? english : romaji);

        item.setPostYear(getString(obj, "season_year"));
        item.setAniTypes(getString(obj, "format"));
        item.setPostSeasonType(getString(obj, "season"));
        item.setStatus(getString(obj, "status"));
        item.setSource(getString(obj, "source"));
        item.setCountryOfOrigin(getString(obj, "country_of_origin"));
        item.setDescription(getString(obj, "description"));
        item.setAniCoverLarge(getString(obj, "cover_url"));
        item.setBannerUrl(getString(obj, "banner_url"));
        item.setBackgroundUrl(getString(obj, "background_url"));
        item.setLogoUrl(getString(obj, "logo_url"));
        item.setEpisodeCount(getInt(obj, "episode_count"));
        item.setEpisodeDurationMinutes(getInt(obj, "episode_duration_minutes"));
        item.setPopularity(getInt(obj, "popularity"));
        item.setAverageScore(getDouble(obj, "average_score"));
        item.setAdult(getBoolean(obj, "is_adult"));

        return item;
    }

    private String firstExternalId(JsonObject external, String key) {
        if (external == null || !external.has(key)) return "";
        JsonElement value = external.get(key);
        if (value.isJsonArray() && value.getAsJsonArray().size() > 0) {
            return value.getAsJsonArray().get(0).getAsString();
        }
        if (!value.isJsonNull()) return value.getAsString();
        return "";
    }

    private String getEmbedUrl(JsonObject server) {
        if (!server.has("embed") || !server.get("embed").isJsonObject()) return null;
        return getString(server.getAsJsonObject("embed"), "url");
    }

    private String buildPlayUrl(String animeId, int episodeNumber) {
        return BASE_API + "/" + encodePath(animeId) + "/episodes/" + episodeNumber + "/play";
    }

    private String encodePath(String value) {
        return value.replace("/", "%2F").replace("?", "%3F").replace("#", "%23");
    }

    private String resolveUrl(String baseUrl, String relativeUrl) {
        try {
            return URI.create(baseUrl).resolve(relativeUrl).toString();
        } catch (Exception e) {
            return relativeUrl;
        }
    }

    private String slugify(String value) {
        if (value == null) return "";
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug;
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        try { return obj.get(key).getAsString(); } catch (Exception e) { return ""; }
    }

    private int getInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try { return obj.get(key).getAsInt(); } catch (Exception e) { return 0; }
    }

    private double getDouble(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0.0;
        try { return obj.get(key).getAsDouble(); } catch (Exception e) { return 0.0; }
    }

    private boolean getBoolean(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return false;
        try { return obj.get(key).getAsBoolean(); } catch (Exception e) { return false; }
    }
}
