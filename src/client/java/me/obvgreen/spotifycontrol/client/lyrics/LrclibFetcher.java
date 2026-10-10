package me.obvgreen.spotifycontrol.client.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * {@link LyricsFetcher} backed by <a href="https://lrclib.net">LRCLIB</a>, a free community lyrics
 * database that serves both time-synced (LRC) and plain lyrics over a small JSON API.
 *
 * <p>Strategy per query:</p>
 * <ol>
 *   <li>{@code GET /api/get} with title, artist, album and duration - an exact, canonical match;</li>
 *   <li>if that 404s, {@code GET /api/search} with title and artist only, and the best candidate is
 *       picked by scoring synced-over-plain, then duration closeness, then album match. Search results
 *       often carry crowd-uploaded duplicates, so a naive "take the first hit" would regularly sync to
 *       the wrong recording.</li>
 * </ol>
 *
 * <p>Everything runs on a private daemon executor. The {@link HttpClient} never touches the render
 * thread and every request has a hard timeout so a hung provider cannot leak resources.</p>
 */
public final class LrclibFetcher implements LyricsFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Lyrics");
    private static final String BASE_URL = System.getProperty("spotifycontrol.lrclib.baseUrl", "https://lrclib.net");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "SpotifyControl/1.0 (Fabric client mod)";

    private final HttpClient http;
    private final ExecutorService executor;

    public LrclibFetcher() {
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "SpotifyControl-Lyrics");
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
    }

    @Override
    public String id() {
        return "lrclib";
    }

    @Override
    public CompletableFuture<Lyrics> fetch(LyricsQuery query) {
        if (query.title() == null || query.title().isBlank() || query.artist() == null || query.artist().isBlank()) {
            return CompletableFuture.completedFuture(Lyrics.empty("lrclib"));
        }
        CompletableFuture<Lyrics> result = new CompletableFuture<>();
        // Hop onto the private executor: the caller is usually a background worker, and the exact
        // endpoint dance below can take two round trips.
        executor.execute(() -> {
            try {
                Lyrics exact = requestExact(query);
                if (exact != null) {
                    result.complete(exact);
                    return;
                }
                result.complete(requestSearch(query));
            } catch (Exception exception) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("LRCLIB lookup failed for '{} - {}': {}", query.artist(), query.title(),
                            exception.toString());
                }
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Exact lookup; returns {@code null} when the provider has no canonical entry. */
    @Nullable
    private Lyrics requestExact(LyricsQuery query) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/api/get?track_name=").append(encode(query.cleanTitle()))
                .append("&artist_name=").append(encode(query.primaryArtist()));
        String album = query.usableAlbum();
        if (album != null) {
            url.append("&album_name=").append(encode(album));
        }
        if (query.durationMs() > 0) {
            url.append("&duration=").append(Math.round(query.durationMs() / 1000.0));
        }
        JsonObject body = send(url.toString());
        if (body == null) {
            return null;
        }
        return toLyrics(body, "lrclib");
    }

    /** Fuzzy lookup used when the exact endpoint misses. */
    private Lyrics requestSearch(LyricsQuery query) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/api/search?track_name=").append(encode(query.cleanTitle()))
                .append("&artist_name=").append(encode(query.primaryArtist()));
        String album = query.usableAlbum();
        if (album != null) {
            url.append("&album_name=").append(encode(album));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return Lyrics.empty("lrclib");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("LRCLIB search returned HTTP " + response.statusCode());
        }

        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonArray()) {
            return Lyrics.empty("lrclib");
        }
        JsonArray candidates = parsed.getAsJsonArray();
        JsonObject best = null;
        int bestScore = Integer.MIN_VALUE;
        List<JsonObject> fallbacks = new ArrayList<>();
        for (JsonElement element : candidates) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject candidate = element.getAsJsonObject();
            fallbacks.add(candidate);
            int score = score(candidate, query);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null) {
            return Lyrics.empty("lrclib");
        }
        if (LOGGER.isDebugEnabled() && fallbacks.size() > 1) {
            LOGGER.debug("LRCLIB search for '{}' returned {} candidates, picked '{}' (score {})",
                    query.cleanTitle(), fallbacks.size(), text(best, "trackName"), bestScore);
        }
        return toLyrics(best, "lrclib (search)");
    }

    /** Higher is better: timed lyrics beat plain ones, close durations beat distant ones. */
    private int score(JsonObject candidate, LyricsQuery query) {
        int score = 0;
        String synced = text(candidate, "syncedLyrics");
        if (synced != null && !synced.isBlank()) {
            score += 100;
        } else if (text(candidate, "plainLyrics") != null) {
            score += 20;
        } else {
            score -= 50;
        }
        if (bool(candidate, "instrumental")) {
            score -= 10;
        }
        double duration = number(candidate, "duration");
        if (query.durationMs() > 0 && duration > 0) {
            double delta = Math.abs(duration - query.durationMs() / 1000.0);
            if (delta <= 2.0) {
                score += 60;
            } else if (delta <= 5.0) {
                score += 25;
            } else if (delta > 20.0) {
                score -= 40;
            }
        }
        String album = text(candidate, "albumName");
        String wanted = query.usableAlbum();
        if (wanted != null && album != null && album.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT))) {
            score += 15;
        }
        String artist = text(candidate, "artistName");
        if (artist != null && query.primaryArtist() != null
                && artist.toLowerCase(Locale.ROOT).equals(query.primaryArtist().toLowerCase(Locale.ROOT))) {
            score += 10;
        }
        return score;
    }

    /** Converts one LRCLIB record into a parsed {@link Lyrics}. */
    private Lyrics toLyrics(JsonObject body, String source) {
        if (bool(body, "instrumental")) {
            return Lyrics.instrumental(source);
        }
        String synced = text(body, "syncedLyrics");
        if (synced != null && !synced.isBlank()) {
            List<LyricsLine> lines = LrcParser.parse(synced);
            if (!lines.isEmpty()) {
                return new Lyrics(true, false, lines, source);
            }
        }
        String plain = text(body, "plainLyrics");
        List<LyricsLine> lines = LrcParser.parsePlain(plain);
        if (lines.isEmpty()) {
            return Lyrics.empty(source);
        }
        return new Lyrics(false, false, lines, source);
    }

    /** Performs a {@code /api/get} call; {@code null} means "not found". */
    @Nullable
    private JsonObject send(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("LRCLIB returned HTTP " + response.statusCode());
        }
        JsonElement parsed = JsonParser.parseString(response.body());
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    @Nullable
    private static String text(JsonObject object, String member) {
        JsonElement element = object.get(member);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static boolean bool(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static double number(JsonObject object, String member) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonPrimitive()) {
            return 0.0;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException exception) {
            return 0.0;
        }
    }

    /** Releases the request threads; called from the client shutdown hook. */
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
