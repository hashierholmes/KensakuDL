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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;


/**
 * Provides the app's catalogue and episode API access, resolves provider playback metadata into HLS manifests, and parses playlist, encryption, and subtitle information. Network operations use a shared OkHttp client and cached API responses.
 */
public class ApiClient {
    public static final String UA = "Mozilla/5.0 (Linux; Android 14; Mblu 21) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    private static final String BASE_API = "https://www.miruro.to/api/v1/anime";
    private static final String MIRURO_API_FALLBACK = "https://miruro.to/api/v1/anime";
    private static final String XOR_KEY = "miruro/catalog";
    private static final String MIRURO_ORIGIN = "https://www.miruro.to";

    private static final String TRACK_SUB = "sub";
    private static final String TRACK_DUB = "dub";
    private static final String TRACK_SSUB = "ssub";
    private static final String PROVIDER_ANIWAVES = "aniwaves";
    private static final String SERVER_VIDPLAY = "Vidplay";
    private static final String FORMAT_HLS = "hls";

    private static ApiClient instance;
    private final OkHttpClient client;
    private final ConcurrentHashMap<String, JsonObject> playCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<EpisodeItem>> episodeCache = new ConcurrentHashMap<>();

    private ApiClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Returns the shared API client used for catalogue, episode, and playback-source requests.
     * 
     * @return singleton client
     */
    public static synchronized ApiClient getInstance() {
        if (instance == null) instance = new ApiClient();
        return instance;
    }

    /**
     * Exposes the configured OkHttp client for requests that need the same network configuration.
     * 
     * @return shared HTTP client
     */
    public OkHttpClient getHttpClient() {
        return client;
    }

    /**
     * Searches the catalogue for titles matching the supplied query.
     * 
     * @param keyword search text
     * @return matching anime entries
     * @throws IOException if the request or response parsing fails
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
     * Loads the popularity-sorted catalogue shelf.
     * 
     * @return popular anime entries
     * @throws IOException if the request fails
     */
    public List<AnimeItem> getPopularAnime() throws IOException { return getAnimeList(BASE_API + "?sort=-popularity&limit=15"); }
    /**
     * Loads the catalogue shelf sorted by score.
     * 
     * @return top-rated anime entries
     * @throws IOException if the request fails
     */
    public List<AnimeItem> getTopRatedAnime() throws IOException { return getAnimeList(BASE_API + "?sort=-score&limit=12"); }
    /**
     * Loads currently airing titles from the trending endpoint.
     * 
     * @return currently airing anime entries
     * @throws IOException if the request fails
     */
    public List<AnimeItem> getTopAiringAnime() throws IOException { return getAnimeList(BASE_API + "/trending?limit=12&status=RELEASING"); }
    /**
     * Loads the upcoming catalogue shelf.
     * 
     * @return upcoming anime entries
     * @throws IOException if the request fails
     */
    public List<AnimeItem> getUpcomingAnime() throws IOException { return getAnimeList(BASE_API + "?status=NOT_YET_RELEASED&sort=-popularity&limit=12"); }

    private List<AnimeItem> getAnimeList(String url) throws IOException {
        JsonObject root = getDecodedJson(url);
        JsonArray data = root.has("data") && root.get("data").isJsonArray() ? root.getAsJsonArray("data") : new JsonArray();
        List<AnimeItem> result = new ArrayList<>();
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            AnimeItem item = parseAnime(element.getAsJsonObject());
            if (item != null && !item.getId().isEmpty()) result.add(item);
        }
        return result;
    }

    /**
     * Loads the default subtitle-track episode list for an anime.
     * 
     * @param animeId catalogue identifier for the series
     * @return episodes in playlist order
     * @throws IOException if the episode data cannot be loaded
     */
    public List<EpisodeItem> getEpisodes(String animeId) throws IOException {
        return getEpisodes(animeId, TRACK_SUB);
    }

    /**
     * Loads episode metadata for an anime, optionally scoped to the requested subtitle or dub track. Cached base episode data is copied before track-specific changes are applied.
     * 
     * @param animeId catalogue identifier for the series
     * @param track requested audio track
     * @return episodes in playlist order
     * @throws IOException if the episode data cannot be loaded
     */
    public List<EpisodeItem> getEpisodes(String animeId, String track) throws IOException {
        if (animeId == null || animeId.trim().isEmpty()) {
            throw new IOException("Unable to load episodes.");
        }

        String requestedTrack = normalizeTrack(track);
        List<EpisodeItem> episodes = getBaseEpisodes(animeId);

        if (TRACK_SUB.equals(requestedTrack)) {
            for (EpisodeItem item : episodes) item.setTrack(TRACK_SUB);
            return episodes;
        }

        List<EpisodeItem> dubEpisodes = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, Math.max(1, episodes.size())));
        try {
            List<Future<EpisodeItem>> futures = new ArrayList<>();
            for (EpisodeItem episode : episodes) {
                futures.add(executor.submit(() -> {
                    try {
                        JsonObject play = getPlayJson(episode.getLink());
                        return hasUsableAniWavesTrack(play, TRACK_DUB) ? episode : null;
                    } catch (Exception ignored) {
                        return null;
                    }
                }));
            }

            for (Future<EpisodeItem> future : futures) {
                try {
                    EpisodeItem episode = future.get();
                    if (episode != null) {
                        episode.setTrack(TRACK_DUB);
                        dubEpisodes.add(episode);
                    }
                } catch (Exception ignored) {}
            }
        } finally {
            executor.shutdownNow();
        }

        Collections.sort(dubEpisodes, Comparator.comparingInt(EpisodeItem::getEpisodeNumber));
        return dubEpisodes;
    }

    private List<EpisodeItem> getBaseEpisodes(String animeId) throws IOException {
        List<EpisodeItem> cached = episodeCache.get(animeId);
        if (cached != null) return copyEpisodes(cached);

        // Miruro's working website request uses /episodes?limit=10000 (without kind).
        // Fetch once, then filter locally because the kind query can return HTTP 400.
        List<EpisodeItem> all = getEpisodesByKind(animeId);
        List<EpisodeItem> regular = new ArrayList<>();
        List<EpisodeItem> films = new ArrayList<>();

        for (EpisodeItem item : all) {
            String kind = item.getKind() == null ? "" : item.getKind().trim().toLowerCase(Locale.ROOT);
            if (kind.isEmpty() || "regular".equals(kind)) {
                regular.add(item);
            } else if ("film".equals(kind)) {
                films.add(item);
            }
        }

        List<EpisodeItem> selected;
        if (!regular.isEmpty()) selected = regular;
        else if (!films.isEmpty()) selected = films;
        else selected = all;

        episodeCache.put(animeId, copyEpisodes(selected));
        return copyEpisodes(selected);
    }

    private List<EpisodeItem> copyEpisodes(List<EpisodeItem> source) {
        List<EpisodeItem> copies = new ArrayList<>(source.size());
        for (EpisodeItem original : source) {
            EpisodeItem item = new EpisodeItem();
            item.setEpisodeNumber(original.getEpisodeNumber());
            item.setAbsoluteNumber(original.getAbsoluteNumber());
            item.setAnimeId(original.getAnimeId());
            item.setName(original.getName());
            item.setTitle(original.getTitle());
            item.setSlug(original.getSlug());
            item.setLink(original.getLink());
            item.setSynopsis(original.getSynopsis());
            item.setThumbnailUrl(original.getThumbnailUrl());
            item.setAiredOn(original.getAiredOn());
            item.setDurationSeconds(original.getDurationSeconds());
            item.setKind(original.getKind());
            item.setCanonType(original.getCanonType());
            item.setSelected(original.isSelected());
            item.setTrack(original.getTrack());
            copies.add(item);
        }
        return copies;
    }

    private List<EpisodeItem> getEpisodesByKind(String animeId) throws IOException {
        String url = BASE_API + "/" + encodePath(animeId) + "/episodes?limit=10000";
        String referer = MIRURO_ORIGIN + "/watch/" + encodePath(animeId) + "/";
        JsonObject root = getDecodedJson(url, referer);
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
            item.setTrack(TRACK_SUB);

            if (number > 0 && seenEpisodeNumbers.add(number)) result.add(item);
        }

        Collections.sort(result, Comparator.comparingInt(EpisodeItem::getEpisodeNumber));
        return result;
    }

    /** One media segment from an HLS playlist, including its timing and encryption metadata. */
    public static class HlsSegment {
        public final String url;
        public final double duration;
        public final long sequenceNumber;
        public final String encryptionKeyUrl;
        public final byte[] encryptionIv;

        /**
         * Creates the descriptor used to download and decrypt one HLS segment.
         *
         * @param url segment resource URL
         * @param duration segment duration in seconds
         * @param sequenceNumber playlist sequence number used for ordering and IV derivation
         * @param encryptionKeyUrl AES key URL, or {@code null} for an unencrypted segment
         * @param encryptionIv explicit initialization vector, or {@code null} when sequence-derived
         */
        public HlsSegment(String url, double duration, long sequenceNumber,
                          String encryptionKeyUrl, byte[] encryptionIv) {
            this.url = url;
            this.duration = duration;
            this.sequenceNumber = sequenceNumber;
            this.encryptionKeyUrl = encryptionKeyUrl;
            this.encryptionIv = encryptionIv;
        }
    }

    /** Resolved playback URL together with request headers and an optional subtitle resource. */
    public static class StreamSource {
        public final String url;
        public final String referer;
        public final String origin;
        public final String subtitleUrl;
        /**
         * Creates a resolved stream descriptor for the player.
         *
         * @param url playable media URL
         * @param referer Referer header required by the provider, if any
         * @param origin Origin header required by the provider, if any
         * @param subtitleUrl subtitle resource URL, or an empty string when unavailable
         */
        public StreamSource(String url, String referer, String origin, String subtitleUrl) {
            this.url = url; this.referer = referer; this.origin = origin; this.subtitleUrl = subtitleUrl;
        }
    }

    /**
     * Resolves a playable HLS source for the built-in player, including the request headers
     * and optional subtitle URL required by the selected provider.
     *
     * @param episodeLink episode page or playback reference
     * @param track requested audio track
     * @return resolved stream URL and its associated playback metadata
     * @throws IOException if no usable source can be resolved
     */
    public StreamSource resolveStreamingSource(String episodeLink, String track) throws IOException {
        if (episodeLink == null || episodeLink.trim().isEmpty()) throw new IOException("Episode link is missing.");
        String requestedTrack = normalizeTrack(track);
        JsonObject root = getPlayJson(episodeLink);
        String[] tracks = TRACK_SUB.equals(requestedTrack) ? new String[]{TRACK_SUB, TRACK_SSUB} : new String[]{requestedTrack};
        for (String wanted : tracks) {
            JsonArray trackArray = root.has("tracks") && root.get("tracks").isJsonArray() ? root.getAsJsonArray("tracks") : new JsonArray();
            for (int pass = 0; pass < 2; pass++) {
                for (JsonElement te : trackArray) {
                    if (!te.isJsonObject()) continue;
                    JsonObject to = te.getAsJsonObject();
                    if (!wanted.equalsIgnoreCase(getString(to, "track"))) continue;
                    JsonArray providers = to.has("providers") && to.get("providers").isJsonArray() ? to.getAsJsonArray("providers") : new JsonArray();
                    for (JsonElement pe : providers) {
                        if (!pe.isJsonObject()) continue;
                        JsonObject provider = pe.getAsJsonObject();
                        boolean aniWaves = PROVIDER_ANIWAVES.equalsIgnoreCase(getString(provider, "provider"));
                        if ((pass == 0) != aniWaves) continue;
                        JsonArray servers = provider.has("servers") && provider.get("servers").isJsonArray() ? provider.getAsJsonArray("servers") : new JsonArray();
                        for (JsonElement se : servers) {
                            if (!se.isJsonObject()) continue;
                            JsonObject server = se.getAsJsonObject();
                            String url = findHlsStream(server);
                            if (url.isEmpty()) continue;
                            String referer = getHeader(server, "Referer", MIRURO_ORIGIN + "/");
                            String origin = getHeader(server, "Origin", "");
                            String subtitle = findProviderSubtitleUrl(provider);
                            if (subtitle == null) subtitle = findSubtitleUrl(getEmbedUrl(server));
                            return new StreamSource(url, referer, origin, subtitle == null ? "" : subtitle);
                        }
                    }
                }
            }
        }
        throw new IOException("No playable stream found for this language.");
    }

    /** Parsed HLS playlist with ordered segments and the metadata required for retrieval. */
    public static class HlsManifest {
        public final List<HlsSegment> segments;
        public final double totalDuration;
        public final String playerUrl;
        public final String origin;
        public final String subtitleUrl;

        /**
         * Creates a parsed manifest descriptor for the downloader.
         *
         * @param segments media segments in playlist order
         * @param totalDuration sum of parsed segment durations in seconds
         * @param playerUrl source URL used to resolve relative playlist resources
         * @param origin Origin header associated with the playlist request
         * @param subtitleUrl subtitle resource URL, or an empty string when unavailable
         */
        public HlsManifest(List<HlsSegment> segments, double totalDuration,
                           String playerUrl, String origin, String subtitleUrl) {
            this.segments = segments;
            this.totalDuration = totalDuration;
            this.playerUrl = playerUrl;
            this.origin = origin;
            this.subtitleUrl = subtitleUrl;
        }
    }

    /**
     * Resolves HLS manifests using the default subtitle track.
     * 
     * @param episodeLink episode page or playback reference
     * @return usable HLS manifests
     * @throws IOException if source resolution fails
     */
    public List<HlsManifest> resolveAllHlsManifests(String episodeLink) throws IOException {
        return resolveAllHlsManifests(episodeLink, TRACK_SUB);
    }

    /**
     * Resolves HLS manifests for the requested audio track. Subtitle and dub requests use
     * the same provider-resolution pipeline, while retaining track-specific episode metadata.
     *
     * @param episodeLink episode page or playback reference
     * @param track requested audio track
     * @return usable manifests in provider discovery order
     * @throws IOException if the episode cannot be resolved or no usable manifest is found
     */
    public List<HlsManifest> resolveAllHlsManifests(String episodeLink, String track) throws IOException {
        if (episodeLink == null || episodeLink.trim().isEmpty()) {
            throw new IOException("Unable to resolve this episode.");
        }

        String requestedTrack = normalizeTrack(track);
        JsonObject root = getPlayJson(episodeLink);
        List<HlsManifest> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Try the selected track first. Keep every usable HLS server so the
        // downloader can switch mirrors if a later segment is blocked/expired.
        collectTrackManifests(root, requestedTrack, results, seen);

        // Some entries expose the subtitle source under ssub even though the UI
        // labels the selection as sub. Append these after normal sub candidates so
        // DownloadManager can use them if every regular-sub source fails mid-stream.
        if (TRACK_SUB.equals(requestedTrack)) {
            collectTrackManifests(root, TRACK_SSUB, results, seen);
        }

        if (results.isEmpty()) {
            throw new IOException("Unable to find a usable HLS video source for this track.");
        }
        return results;
    }

    private void collectTrackManifests(JsonObject root, String track,
                                       List<HlsManifest> results, Set<String> seen) {
        JsonArray tracks = root.has("tracks") && root.get("tracks").isJsonArray()
                ? root.getAsJsonArray("tracks") : new JsonArray();
        for (JsonElement trackElement : tracks) {
            if (!trackElement.isJsonObject()) continue;
            JsonObject trackObject = trackElement.getAsJsonObject();
            if (!track.equalsIgnoreCase(getString(trackObject, "track"))) continue;
            JsonArray providers = trackObject.has("providers") && trackObject.get("providers").isJsonArray()
                    ? trackObject.getAsJsonArray("providers") : new JsonArray();

            // Preserve the known-good AniWaves priority, then try all other providers.
            for (int pass = 0; pass < 2; pass++) {
                for (JsonElement providerElement : providers) {
                    if (!providerElement.isJsonObject()) continue;
                    JsonObject provider = providerElement.getAsJsonObject();
                    boolean aniWaves = PROVIDER_ANIWAVES.equalsIgnoreCase(getString(provider, "provider"));
                    if ((pass == 0) != aniWaves) continue;
                    for (HlsManifest manifest : tryResolveProvider(provider)) {
                        if (manifest == null || manifest.segments.isEmpty()) continue;
                        String identity = manifestIdentity(manifest);
                        if (seen.add(identity)) results.add(manifest);
                    }
                }
            }
        }
    }

    private String manifestIdentity(HlsManifest manifest) {
        StringBuilder key = new StringBuilder();
        for (HlsSegment segment : manifest.segments) key.append(segment.url).append('|');
        return key.toString();
    }

    private List<HlsManifest> tryResolveProvider(JsonObject provider) {
        List<HlsManifest> results = new ArrayList<>();
        if (provider == null) return results;
        JsonArray servers = provider.has("servers") && provider.get("servers").isJsonArray()
                ? provider.getAsJsonArray("servers") : new JsonArray();

        for (JsonElement serverElement : servers) {
            if (!serverElement.isJsonObject()) continue;
            JsonObject server = serverElement.getAsJsonObject();
            String streamUrl = findHlsStream(server);
            if (streamUrl.isEmpty()) continue;

            String referer = getHeader(server, "Referer", MIRURO_ORIGIN + "/");
            String origin = getHeader(server, "Origin", "");
            String embedUrl = getEmbedUrl(server);
            String subtitleUrl = findProviderSubtitleUrl(provider);
            if (subtitleUrl == null) subtitleUrl = findSubtitleUrl(embedUrl);

            try {
                HlsManifest manifest = fetchAndParseHls(streamUrl, referer, origin, subtitleUrl);
                if (manifest != null && !manifest.segments.isEmpty()) results.add(manifest);
            } catch (Exception ignored) {
                // Stale servers are skipped; remaining mirrors are still attempted.
            }
        }
        return results;
    }

    private JsonObject findProvider(JsonObject root, String track, String providerName) {
        JsonArray tracks = root.has("tracks") && root.get("tracks").isJsonArray()
                ? root.getAsJsonArray("tracks") : new JsonArray();

        for (JsonElement trackElement : tracks) {
            if (!trackElement.isJsonObject()) continue;
            JsonObject trackObject = trackElement.getAsJsonObject();
            if (!track.equalsIgnoreCase(getString(trackObject, "track"))) continue;

            JsonArray providers = trackObject.has("providers") && trackObject.get("providers").isJsonArray()
                    ? trackObject.getAsJsonArray("providers") : new JsonArray();
            for (JsonElement providerElement : providers) {
                if (!providerElement.isJsonObject()) continue;
                JsonObject provider = providerElement.getAsJsonObject();
                if (providerName.equalsIgnoreCase(getString(provider, "provider"))) return provider;
            }
        }
        return null;
    }

    private boolean hasUsableAniWavesTrack(JsonObject root, String track) {
        JsonObject provider = findProvider(root, track, PROVIDER_ANIWAVES);
        if (provider == null || !provider.has("servers") || !provider.get("servers").isJsonArray()) return false;

        for (JsonElement serverElement : provider.getAsJsonArray("servers")) {
            if (!serverElement.isJsonObject()) continue;
            JsonObject server = serverElement.getAsJsonObject();
            if (!SERVER_VIDPLAY.equalsIgnoreCase(getString(server, "server"))) continue;
            if (!findHlsStream(server).isEmpty()) return true;
        }
        return false;
    }

    private String findHlsStream(JsonObject server) {
        if (server == null || !server.has("streams") || !server.get("streams").isJsonArray()) return "";
        for (JsonElement streamElement : server.getAsJsonArray("streams")) {
            if (!streamElement.isJsonObject()) continue;
            JsonObject stream = streamElement.getAsJsonObject();
            if (!FORMAT_HLS.equalsIgnoreCase(getString(stream, "format"))) continue;
            String url = getString(stream, "url");
            if (!url.isEmpty()) return url;
        }
        return "";
    }

    private String getHeader(JsonObject server, String name, String fallback) {
        JsonObject headers = server != null && server.has("headers") && server.get("headers").isJsonObject()
                ? server.getAsJsonObject("headers") : null;
        String value = headers != null ? getString(headers, name) : "";
        return value.isEmpty() ? fallback : value;
    }

    private String findProviderSubtitleUrl(JsonObject provider) {
        if (provider == null || !provider.has("subtitles") || !provider.get("subtitles").isJsonArray()) return null;

        String fallback = null;
        for (JsonElement element : provider.getAsJsonArray("subtitles")) {
            if (!element.isJsonObject()) continue;
            JsonObject subtitle = element.getAsJsonObject();
            String file = getString(subtitle, "file");
            if (file.isEmpty()) continue;
            String language = getString(subtitle, "language").toLowerCase(Locale.ROOT);
            String label = getString(subtitle, "label").toLowerCase(Locale.ROOT);
            boolean english = language.equals("eng") || language.equals("en") || label.contains("english") || label.equals("en");
            if (english && "vtt".equalsIgnoreCase(getString(subtitle, "format"))) return file;
            if (fallback == null && "vtt".equalsIgnoreCase(getString(subtitle, "format"))) fallback = file;
        }
        return fallback;
    }

    private HlsManifest fetchAndParseHls(String hlsUrl, String referer, String origin, String subtitleUrl) throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
                .url(hlsUrl)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .header("Accept", "*/*");
        if (origin != null && !origin.isEmpty()) requestBuilder.header("Origin", origin);

        String playlist;
        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HLS request failed: " + response.code());
            }
            playlist = response.body().string();
        }

        if (!playlist.contains("#EXTM3U")) throw new IOException("Invalid HLS playlist.");

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
                    try { bandwidth = Long.parseLong(bandwidthMatcher.group(1)); } catch (Exception ignored) {}
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

            if (bestChildUrl != null) return fetchAndParseHls(bestChildUrl, referer, origin, subtitleUrl);
        }

        List<HlsSegment> segments = new ArrayList<>();
        double currentDuration = 0.0;
        double totalDuration = 0.0;
        long mediaSequence = 0L;
        String encryptionKeyUrl = null;
        byte[] encryptionIv = null;

        for (String rawLine : playlist.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    mediaSequence = Long.parseLong(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
                } catch (Exception ignored) {
                    mediaSequence = 0L;
                }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                String method = getHlsAttribute(line, "METHOD");
                if ("NONE".equalsIgnoreCase(method)) {
                    encryptionKeyUrl = null;
                    encryptionIv = null;
                } else if ("AES-128".equalsIgnoreCase(method)) {
                    String keyUri = getHlsAttribute(line, "URI");
                    encryptionKeyUrl = keyUri.isEmpty() ? null : resolveUrl(hlsUrl, keyUri);
                    String iv = getHlsAttribute(line, "IV");
                    encryptionIv = parseHlsIv(iv);
                }
            } else if (line.startsWith("#EXTINF:")) {
                try {
                    String value = line.substring(8).split(",", 2)[0].trim();
                    currentDuration = Double.parseDouble(value);
                } catch (Exception ignored) {
                    currentDuration = 0.0;
                }
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String segmentUrl = resolveUrl(hlsUrl, line);
                segments.add(new HlsSegment(segmentUrl, currentDuration, mediaSequence,
                        encryptionKeyUrl, encryptionIv == null ? null : encryptionIv.clone()));
                totalDuration += currentDuration;
                currentDuration = 0.0;
                mediaSequence++;
            }
        }

        if (segments.isEmpty()) return null;
        return new HlsManifest(segments, totalDuration, referer, origin, subtitleUrl);
    }

    private String getHlsAttribute(String line, String name) {
        Matcher quoted = Pattern.compile("(?:^|,)" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(line);
        if (quoted.find()) return quoted.group(1);

        Matcher plain = Pattern.compile("(?:^|,)" + Pattern.quote(name) + "=([^,]*)").matcher(line);
        return plain.find() ? plain.group(1).trim() : "";
    }

    private byte[] parseHlsIv(String value) {
        if (value == null || value.isEmpty()) return null;
        String hex = value.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.isEmpty()) return null;
        if ((hex.length() & 1) != 0) hex = "0" + hex;

        byte[] raw = new byte[Math.min(16, hex.length() / 2)];
        int start = hex.length() - raw.length * 2;
        for (int i = 0; i < raw.length; i++) {
            try {
                raw[i] = (byte) Integer.parseInt(hex.substring(start + i * 2, start + i * 2 + 2), 16);
            } catch (Exception e) {
                return null;
            }
        }

        byte[] iv = new byte[16];
        System.arraycopy(raw, 0, iv, 16 - raw.length, raw.length);
        return iv;
    }

    private String findSubtitleUrl(String embedUrl) {
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

    private JsonObject getPlayJson(String url) throws IOException {
        JsonObject cached = playCache.get(url);
        if (cached != null) return cached;
        JsonObject root = getDecodedJson(url);
        playCache.put(url, root);
        return root;
    }

    private JsonObject getDecodedJson(String url) throws IOException {
        return getDecodedJson(url, MIRURO_ORIGIN + "/");
    }

    private JsonObject getDecodedJson(String url, String referer) throws IOException {
        IOException firstError = null;
        String fallback = getFallbackUrl(url);
        String[] candidates = fallback == null ? new String[]{url} : new String[]{url, fallback};

        for (String candidate : candidates) {
            try {
                Request request = new Request.Builder()
                        .url(candidate)
                        .header("User-Agent", UA)
                        .header("Accept", "*/*")
                        .header("Referer", referer == null || referer.isEmpty() ? MIRURO_ORIGIN + "/" : referer)
                        .build();

                byte[] body;
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("Miruro request failed: " + response.code());
                    }
                    body = response.body().bytes();
                }

                try {
                    return JsonParser.parseString(decodeResponse(body)).getAsJsonObject();
                } catch (Exception decodeError) {
                    throw new IOException("Miruro returned data that could not be decoded or parsed.", decodeError);
                }
            } catch (IOException e) {
                if (firstError == null) firstError = e;
            }
        }

        throw firstError != null ? firstError : new IOException("Unable to reach service.");
    }

    private String getFallbackUrl(String url) {
        if (url == null) return null;
        if (url.startsWith(BASE_API)) return MIRURO_API_FALLBACK + url.substring(BASE_API.length());
        if (url.startsWith(MIRURO_API_FALLBACK)) return BASE_API + url.substring(MIRURO_API_FALLBACK.length());
        return null;
    }

    private String decodeResponse(byte[] encrypted) throws IOException {
        if (encrypted == null || encrypted.length == 0) throw new IOException("Empty Miruro response.");

        byte[] xored = new byte[encrypted.length];
        byte[] key = XOR_KEY.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < encrypted.length; i++) xored[i] = (byte) (encrypted[i] ^ key[i % key.length]);

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(xored));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) != -1) out.write(buffer, 0, read);
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
        if (value.isJsonArray() && value.getAsJsonArray().size() > 0) return value.getAsJsonArray().get(0).getAsString();
        if (!value.isJsonNull()) return value.getAsString();
        return "";
    }

    private String getEmbedUrl(JsonObject server) {
        if (server == null || !server.has("embed") || !server.get("embed").isJsonObject()) return null;
        return getString(server.getAsJsonObject("embed"), "url");
    }

    private String buildPlayUrl(String animeId, int episodeNumber) {
        return BASE_API + "/" + encodePath(animeId) + "/episodes/" + episodeNumber + "/play";
    }

    private String encodePath(String value) {
        return value.replace("/", "%2F").replace("?", "%3F").replace("#", "%23");
    }

    private String resolveUrl(String baseUrl, String relativeUrl) {
        try { return URI.create(baseUrl).resolve(relativeUrl).toString(); }
        catch (Exception e) { return relativeUrl; }
    }

    private String normalizeTrack(String track) {
        if (TRACK_DUB.equalsIgnoreCase(track)) return TRACK_DUB;
        if (TRACK_SSUB.equalsIgnoreCase(track)) return TRACK_SSUB;
        return TRACK_SUB;
    }

    private String slugify(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
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
