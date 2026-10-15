package me.obvgreen.spotifycontrol.client.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
import me.obvgreen.spotifycontrol.client.config.TokenSet;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Primary Spotify backend: OAuth 2.0 Authorization Code flow with PKCE (RFC 7636) plus the
 * Spotify Web API.
 *
 * <p><b>Why PKCE:</b> a Minecraft mod cannot keep a client secret, so the authorization-code flow is
 * used with a dynamically generated {@code code_verifier}/{@code code_challenge} pair. Only the
 * public Client ID is configured by the user.</p>
 *
 * <p><b>Loopback capture:</b> {@link #authorize()} starts a tiny {@link HttpServer} bound to
 * {@code 127.0.0.1:<port>}, opens the consent page in the user's browser and waits for
 * {@code GET /callback?code=...&state=...}. The {@code state} value is a CSRF nonce that is verified
 * before the code is exchanged.</p>
 *
 * <p><b>Threading:</b> the loopback server, token refresh and every HTTP call run on background
 * threads. Nothing in this class blocks the Minecraft client thread.</p>
 */
public final class SpotifyWebApiClient implements SpotifyController {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/WebApi");

    /** Endpoints. */
    private static final String AUTHORIZE_ENDPOINT = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token";
    private static final String API_BASE = "https://api.spotify.com/v1";

    /** Scopes required for metadata plus playback control. */
    private static final String SCOPES = String.join(" ",
            "user-read-playback-state",
            "user-read-currently-playing",
            "user-modify-playback-state",
            "user-read-email",
            "user-read-private");

    /** How long {@link #authorize()} waits for the browser round trip. */
    private static final Duration AUTHORIZATION_TIMEOUT = Duration.ofMinutes(5);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ModConfig config;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    private final AtomicBoolean authorizing = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** Last shuffle state reported by {@code GET /me/player}; optimistically updated on writes. */
    private volatile boolean shuffleState;
    /** Last volume reported by {@code device.volume_percent}, or {@code -1} while unknown. */
    private volatile int volumePercent = -1;

    @Nullable
    private volatile HttpServer callbackServer;
    @Nullable
    private volatile String lastError;

    public SpotifyWebApiClient(ModConfig config) {
        this.config = config;
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "SpotifyControl-WebApi");
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // SpotifyController
    // ---------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return "webapi";
    }

    @Override
    public String displayName() {
        return "Spotify Web API (OAuth PKCE)";
    }

    @Override
    public CompletableFuture<TrackInfo> getCurrentlyPlayingTrack() {
        if (!isAuthenticated()) {
            return CompletableFuture.completedFuture(idleTrack());
        }
        return supplyAsync(() -> fetchCurrentlyPlaying(0));
    }

    @Override
    public CompletableFuture<Boolean> play() {
        return playerCommand("PUT", "/me/player/play", null);
    }

    @Override
    public CompletableFuture<Boolean> pause() {
        return playerCommand("PUT", "/me/player/pause", null);
    }

    @Override
    public CompletableFuture<Boolean> playPause() {
        return supplyAsync(() -> {
            TrackInfo current = fetchCurrentlyPlaying(0);
            String path = current.isPlaying() ? "/me/player/pause" : "/me/player/play";
            return sendPlayerCommand("PUT", path, null);
        });
    }

    @Override
    public CompletableFuture<Boolean> nextTrack() {
        return playerCommand("POST", "/me/player/next", null);
    }

    @Override
    public CompletableFuture<Boolean> previousTrack() {
        return playerCommand("POST", "/me/player/previous", null);
    }

    @Override
    public CompletableFuture<RepeatMode> setRepeatMode(RepeatMode mode) {
        RepeatMode requested = mode == null ? RepeatMode.OFF : mode;
        return supplyAsync(() -> {
            // A device must be active before playback state can be written.
            ensureActiveDevice();
            boolean ok = sendPlayerCommand("PUT",
                    "/me/player/repeat?state=" + encode(requested.apiValue()), null);
            return ok ? requested : RepeatMode.OFF;
        });
    }

    @Override
    public boolean canControlRepeat() {
        return true;
    }

    @Override
    public CompletableFuture<Boolean> setShuffle(boolean enabled) {
        return supplyAsync(() -> {
            ensureActiveDevice();
            boolean ok = sendPlayerCommand("PUT",
                    "/me/player/shuffle?state=" + (enabled ? "true" : "false"), null);
            if (ok) {
                shuffleState = enabled;
            }
            return ok;
        });
    }

    @Override
    public boolean canControlShuffle() {
        return true;
    }

    @Override
    public boolean shuffleState() {
        return shuffleState;
    }

    @Override
    public CompletableFuture<Boolean> setVolume(int percent) {
        // The Web API clamps volume_percent to 0-100; there is no server side boost.
        int clamped = Math.max(0, Math.min(100, percent));
        return supplyAsync(() -> {
            ensureActiveDevice();
            boolean ok = sendPlayerCommand("PUT",
                    "/me/player/volume?volume_percent=" + clamped, null);
            if (ok) {
                volumePercent = clamped;
            }
            return ok;
        });
    }

    @Override
    public boolean canControlVolume() {
        return true;
    }

    @Override
    public int maxVolumePercent() {
        return 100;
    }

    @Override
    public int volumePercent() {
        return volumePercent;
    }

    @Override
    public CompletableFuture<Boolean> seek(long positionMs) {
        long clamped = Math.max(0L, positionMs);
        return supplyAsync(() -> {
            ensureActiveDevice();
            // The service skips ahead optimistically; this only performs the write.
            return sendPlayerCommand("PUT", "/me/player/seek?position_ms=" + clamped, null);
        });
    }

    @Override
    public boolean canControlSeek() {
        return true;
    }

    @Override
    public boolean isAuthenticated() {
        TokenSet tokens = config.tokens();
        return config.hasClientId() && tokens != null && (!tokens.accessToken().isBlank()
                || tokens.canRefresh());
    }

    @Override
    public boolean isAvailable() {
        return isAuthenticated() && consecutiveFailures.get() < 5;
    }

    @Override
    public String statusMessage() {
        if (!config.hasClientId()) {
            return "No Spotify Client ID configured.";
        }
        TokenSet tokens = config.tokens();
        if (tokens == null || (tokens.accessToken().isBlank() && !tokens.canRefresh())) {
            return "Not logged in.";
        }
        if (lastError != null) {
            return "Logged in (" + (tokens.isExpired() ? "refreshing token" : "token valid")
                    + ") - last error: " + lastError;
        }
        return "Logged in" + (tokens.isExpired() ? " (token will be refreshed on next request)." : ".");
    }

    @Override
    public void shutdown() {
        stopCallbackServer();
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------
    // OAuth 2.0 PKCE
    // ---------------------------------------------------------------------------------------------

    /** Result of an interactive login attempt. */
    public enum AuthResult {
        SUCCESS,
        NO_CLIENT_ID,
        ALREADY_RUNNING,
        BROWSER_FAILED,
        PORT_UNAVAILABLE,
        TIMEOUT,
        DENIED,
        EXCHANGE_FAILED
    }

    /**
     * Runs the full interactive PKCE authorization-code flow.
     *
     * <p>Safe to call from any thread: the calling thread blocks until the browser round trip
     * finishes (or times out), so callers should already be on a background thread or accept the
     * wait. The Minecraft client thread must not call this directly - {@code SpotifyService}
     * dispatches it onto its own executor.</p>
     *
     * @return the outcome of the attempt; tokens are written to the config on success
     */
    public AuthResult authorize() {
        if (!config.hasClientId()) {
            return AuthResult.NO_CLIENT_ID;
        }
        if (!authorizing.compareAndSet(false, true)) {
            return AuthResult.ALREADY_RUNNING;
        }

        String codeVerifier = randomUrlSafeString(64);
        String codeChallenge = sha256Base64Url(codeVerifier);
        String state = randomUrlSafeString(32);

        HttpServer server;
        CountDownLatch latch = new CountDownLatch(1);
        AuthCallback callback = new AuthCallback();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.redirectPort), 0);
        } catch (IOException exception) {
            LOGGER.error("Cannot bind OAuth callback port {}: {}", config.redirectPort,
                    exception.toString());
            authorizing.set(false);
            return AuthResult.PORT_UNAVAILABLE;
        }

        server.createContext("/callback", exchange -> handleCallback(exchange, state, callback, latch));
        server.setExecutor(executor);
        server.start();
        callbackServer = server;

        try {
            String authorizeUrl = AUTHORIZE_ENDPOINT
                    + "?client_id=" + encode(config.clientId())
                    + "&response_type=code"
                    + "&redirect_uri=" + encode(config.redirectUri())
                    + "&scope=" + encode(SCOPES)
                    + "&code_challenge_method=S256"
                    + "&code_challenge=" + encode(codeChallenge)
                    + "&state=" + encode(state)
                    + "&show_dialog=false";

            LOGGER.info("Opening Spotify authorization page (redirect URI {}).", config.redirectUri());
            if (!openInBrowser(authorizeUrl)) {
                LOGGER.error("Could not open a browser. Open this URL manually:\n{}", authorizeUrl);
                return AuthResult.BROWSER_FAILED;
            }

            if (!latch.await(AUTHORIZATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                LOGGER.error("Timed out after {} waiting for the Spotify callback.",
                        AUTHORIZATION_TIMEOUT);
                return AuthResult.TIMEOUT;
            }
            if (callback.error != null) {
                LOGGER.error("Spotify denied the authorization request: {} ({})", callback.error,
                        callback.errorDescription);
                return AuthResult.DENIED;
            }
            if (callback.code == null || callback.code.isBlank()) {
                LOGGER.error("Spotify callback did not contain an authorization code.");
                return AuthResult.EXCHANGE_FAILED;
            }

            TokenSet tokens = exchangeAuthorizationCode(callback.code, codeVerifier);
            if (tokens == null) {
                return AuthResult.EXCHANGE_FAILED;
            }
            config.setTokens(tokens);
            lastError = null;
            consecutiveFailures.set(0);
            LOGGER.info("Spotify login successful, scopes: {}", tokens.scope());
            return AuthResult.SUCCESS;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AuthResult.TIMEOUT;
        } finally {
            stopCallbackServer();
            authorizing.set(false);
        }
    }

    /** Clears the stored tokens, effectively logging the user out. */
    public void logout() {
        config.setTokens(null);
        consecutiveFailures.set(0);
        lastError = null;
        LOGGER.info("Spotify tokens cleared.");
    }

    /** {@code true} while {@link #authorize()} is waiting for the browser. */
    public boolean isAuthorizing() {
        return authorizing.get();
    }

    private void handleCallback(HttpExchange exchange, String expectedState, AuthCallback callback,
                                CountDownLatch latch) throws IOException {
        try {
            String query = exchange.getRequestURI().getRawQuery();
            LOGGER.debug("OAuth callback received: {}", query);
            if (query != null) {
                for (String pair : query.split("&")) {
                    int separator = pair.indexOf('=');
                    if (separator <= 0) {
                        continue;
                    }
                    String key = decode(pair.substring(0, separator));
                    String value = decode(pair.substring(separator + 1));
                    switch (key) {
                        case "code" -> callback.code = value;
                        case "state" -> callback.state = value;
                        case "error" -> callback.error = value;
                        case "error_description" -> callback.errorDescription = value;
                        default -> {
                            // Ignore unknown parameters.
                        }
                    }
                }
            }

            boolean stateOk = expectedState.equals(callback.state);
            if (!stateOk) {
                LOGGER.error("OAuth state mismatch - ignoring callback (possible CSRF attempt).");
                callback.error = "state_mismatch";
                callback.code = null;
            }

            String body = buildResultPage(stateOk && callback.error == null);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            // The server itself is stopped by authorize()'s own finally block once the latch trips,
            // so the response written above is always flushed before the socket closes.
            latch.countDown();
        }
    }

    private static String buildResultPage(boolean success) {
        String title = success ? "Spotify Control - login complete" : "Spotify Control - login failed";
        String message = success
                ? "Authorization successful. You can close this tab and return to Minecraft."
                : "Authorization failed or was denied. Please try again from the config screen.";
        String accent = success ? "#1ed760" : "#e8112d";
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<title>" + title + "</title></head>"
                + "<body style=\"margin:0;height:100vh;display:flex;align-items:center;"
                + "justify-content:center;background:#121212;color:#fff;"
                + "font-family:Segoe UI,Roboto,sans-serif\">"
                + "<div style=\"text-align:center;max-width:32rem;padding:2rem\">"
                + "<h1 style=\"color:" + accent + ";margin:0 0 1rem\">" + title + "</h1>"
                + "<p style=\"line-height:1.6;color:#b3b3b3\">" + message + "</p>"
                + "</div></body></html>";
    }

    private void stopCallbackServer() {
        HttpServer server = callbackServer;
        callbackServer = null;
        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception exception) {
                LOGGER.debug("Callback server stop failed: {}", exception.toString());
            }
        }
    }

    @Nullable
    private TokenSet exchangeAuthorizationCode(String code, String codeVerifier) {
        String form = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(config.redirectUri())
                + "&client_id=" + encode(config.clientId())
                + "&code_verifier=" + encode(codeVerifier);

        JsonObject response = postForm(form);
        if (response == null || !response.has("access_token")) {
            LOGGER.error("Token exchange failed.");
            return null;
        }
        String accessToken = response.get("access_token").getAsString();
        String refreshToken = response.has("refresh_token")
                ? response.get("refresh_token").getAsString()
                : "";
        long expiresIn = response.has("expires_in") ? response.get("expires_in").getAsLong() : 3600L;
        String scope = response.has("scope") ? response.get("scope").getAsString() : SCOPES;
        return TokenSet.of(accessToken, refreshToken, expiresIn, scope);
    }

    /**
     * Returns a usable access token, refreshing it first when it is about to expire.
     *
     * @return the bearer token, or {@code null} when the user has to log in again
     */
    @Nullable
    private String validAccessToken() {
        TokenSet tokens = config.tokens();
        if (tokens == null) {
            return null;
        }
        if (!tokens.isExpired()) {
            return tokens.accessToken();
        }
        if (!tokens.canRefresh()) {
            LOGGER.warn("Access token expired and no refresh token is stored; login required.");
            return null;
        }

        String form = "grant_type=refresh_token"
                + "&refresh_token=" + encode(tokens.refreshToken())
                + "&client_id=" + encode(config.clientId());
        JsonObject response = postForm(form);
        if (response == null || !response.has("access_token")) {
            LOGGER.error("Token refresh failed; login required.");
            lastError = "token refresh failed";
            return null;
        }
        String accessToken = response.get("access_token").getAsString();
        long expiresIn = response.has("expires_in") ? response.get("expires_in").getAsLong() : 3600L;
        String scope = response.has("scope") ? response.get("scope").getAsString() : tokens.scope();
        // Spotify omits refresh_token on refresh; keep the stored one.
        TokenSet refreshed = new TokenSet(accessToken, tokens.refreshToken(),
                System.currentTimeMillis() + Math.max(60L, expiresIn) * 1000L, scope);
        config.setTokens(refreshed);
        LOGGER.debug("Spotify access token refreshed.");
        return accessToken;
    }

    @Nullable
    private JsonObject postForm(String form) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                LOGGER.error("accounts.spotify.com returned HTTP {}: {}", response.statusCode(),
                        response.body());
                lastError = "HTTP " + response.statusCode();
                return null;
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception exception) {
            LOGGER.error("Token endpoint request failed: {}", exception.toString());
            lastError = exception.getClass().getSimpleName();
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Web API
    // ---------------------------------------------------------------------------------------------

    private CompletableFuture<Boolean> playerCommand(String method, String path, @Nullable String body) {
        return supplyAsync(() -> {
            ensureActiveDevice();
            return sendPlayerCommand(method, path, body);
        });
    }

    /**
     * Sends a player command and reports whether Spotify accepted it.
     *
     * <p>A 404 from {@code /me/player/*} means "no active device". Rather than failing silently we
     * probe {@code /me/player/devices} once and, if the user has exactly one client open, transfer
     * playback to it and retry - which is what makes the keybinds feel instant when the desktop app
     * has been idle.</p>
     */
    private boolean sendPlayerCommand(String method, String path, @Nullable String body) {
        HttpResponse<String> response = apiRequest(method, path, body);
        if (response == null) {
            return false;
        }
        int status = response.statusCode();
        if (status / 100 == 2) {
            consecutiveFailures.set(0);
            return true;
        }
        if (status == 404 && !path.startsWith("/me/player/devices")) {
            LOGGER.debug("Spotify reported no active device for {}; attempting recovery.", path);
            if (recoverSingleDevice()) {
                response = apiRequest(method, path, body);
                if (response != null && response.statusCode() / 100 == 2) {
                    consecutiveFailures.set(0);
                    return true;
                }
            }
        }
        if (status == 429) {
            LOGGER.warn("Spotify rate limited the request ({}).", path);
            lastError = "rate limited";
        } else if (status == 403) {
            LOGGER.warn("Spotify refused the request ({}): premium account required or restricted.",
                    path);
            lastError = "HTTP 403 (premium required?)";
        } else {
            LOGGER.warn("Spotify request {} {} failed with HTTP {}.", method, path, status);
            lastError = "HTTP " + status;
        }
        consecutiveFailures.incrementAndGet();
        return false;
    }

    /** Transfers playback to the only available device, if there is exactly one. */
    private boolean recoverSingleDevice() {
        JsonObject devices = apiGetJson("/me/player/devices");
        if (devices == null || !devices.has("devices")) {
            return false;
        }
        JsonArray array = devices.getAsJsonArray("devices");
        if (array.size() != 1) {
            return false;
        }
        JsonObject device = array.get(0).getAsJsonObject();
        if (!device.has("id") || device.get("id").isJsonNull()) {
            return false;
        }
        String deviceId = device.get("id").getAsString();
        LOGGER.info("Transferring Spotify playback to '{}'.", deviceId);
        HttpResponse<String> response = apiRequest("PUT", "/me/player",
                "{\"device_ids\":[\"" + deviceId + "\"],\"play\":false}");
        return response != null && response.statusCode() / 100 == 2;
    }

    private void ensureActiveDevice() {
        // Cheap no-op when a device is already active; only used as a hint before state writes.
        if (consecutiveFailures.get() > 0) {
            recoverSingleDevice();
        }
    }

    private TrackInfo fetchCurrentlyPlaying(int attempt) {
        JsonObject json = apiGetJson("/me/player");
        if (json != null && !json.has("item") && isAdvertisementType(json)) {
            // Spotify omits "item" for advertisement slots but still reports the playback state, which
            // is the only reliable ad signal the Web API offers.
            return TrackInfo.advertisement(isPlaying(json), deviceName(json));
        }
        if (json == null || json.size() == 0 || !json.has("item")
                || json.get("item").isJsonNull()) {
            return idleTrack();
        }
        try {
            JsonObject item = json.getAsJsonObject("item");
            String title = item.has("name") ? item.get("name").getAsString() : TrackInfo.UNKNOWN_TITLE;

            StringBuilder artists = new StringBuilder();
            if (item.has("artists") && item.get("artists").isJsonArray()) {
                for (JsonElement element : item.getAsJsonArray("artists")) {
                    JsonObject artist = element.getAsJsonObject();
                    if (!artist.has("name")) {
                        continue;
                    }
                    if (artists.length() > 0) {
                        artists.append(", ");
                    }
                    artists.append(artist.get("name").getAsString());
                }
            }

            String albumName = TrackInfo.UNKNOWN_ALBUM;
            String albumArtUrl = null;
            if (item.has("album") && item.get("album").isJsonObject()) {
                JsonObject album = item.getAsJsonObject("album");
                if (album.has("name")) {
                    albumName = album.get("name").getAsString();
                }
                if (album.has("images") && album.get("images").isJsonArray()) {
                    JsonArray images = album.getAsJsonArray("images");
                    // Spotify orders artwork largest first; prefer the medium (~300px) variant to
                    // keep texture memory and download size reasonable.
                    int index = images.size() >= 2 ? 1 : 0;
                    if (images.size() > 0) {
                        JsonObject image = images.get(index).getAsJsonObject();
                        if (image.has("url") && !image.get("url").isJsonNull()) {
                            albumArtUrl = image.get("url").getAsString();
                        }
                    }
                }
            }

            long durationMs = item.has("duration_ms") && !item.get("duration_ms").isJsonNull()
                    ? item.get("duration_ms").getAsLong() : 0L;
            long progressMs = json.has("progress_ms") && !json.get("progress_ms").isJsonNull()
                    ? json.get("progress_ms").getAsLong() : 0L;
            boolean isPlaying = json.has("is_playing") && !json.get("is_playing").isJsonNull()
                    && json.get("is_playing").getAsBoolean();
            RepeatMode repeatMode = json.has("repeat_state") && !json.get("repeat_state").isJsonNull()
                    ? RepeatMode.fromApiValue(json.get("repeat_state").getAsString())
                    : RepeatMode.OFF;
            String device = null;
            if (json.has("device") && json.get("device").isJsonObject()) {
                JsonObject deviceObject = json.getAsJsonObject("device");
                if (deviceObject.has("name") && !deviceObject.get("name").isJsonNull()) {
                    device = deviceObject.get("name").getAsString();
                }
                if (deviceObject.has("volume_percent")
                        && !deviceObject.get("volume_percent").isJsonNull()) {
                    volumePercent = deviceObject.get("volume_percent").getAsInt();
                }
            }
            if (json.has("shuffle_state") && !json.get("shuffle_state").isJsonNull()) {
                shuffleState = json.get("shuffle_state").getAsBoolean();
            }

            consecutiveFailures.set(0);
            lastError = null;
            TrackInfo snapshot = TrackInfo.of(title, artists.toString(), albumName, albumArtUrl,
                    durationMs, progressMs, isPlaying, repeatMode, device);
            return isAdvertisementType(json) ? snapshot.withAdvertisement(true) : snapshot;
        } catch (RuntimeException exception) {
            LOGGER.error("Malformed Spotify playback payload (attempt {}): {}", attempt,
                    exception.toString());
            lastError = "malformed payload";
            consecutiveFailures.incrementAndGet();
            return idleTrack();
        }
    }

    /** {@code true} when the payload explicitly labels the current slot as an advertisement. */
    private static boolean isAdvertisementType(JsonObject json) {
        return json.has("currently_playing_type") && !json.get("currently_playing_type").isJsonNull()
                && "ad".equalsIgnoreCase(json.get("currently_playing_type").getAsString());
    }

    private static boolean isPlaying(JsonObject json) {
        return json.has("is_playing") && !json.get("is_playing").isJsonNull()
                && json.get("is_playing").getAsBoolean();
    }

    @Nullable
    private static String deviceName(JsonObject json) {
        if (!json.has("device") || !json.get("device").isJsonObject()) {
            return null;
        }
        JsonObject deviceObject = json.getAsJsonObject("device");
        if (!deviceObject.has("name") || deviceObject.get("name").isJsonNull()) {
            return null;
        }
        return deviceObject.get("name").getAsString();
    }

    private TrackInfo idleTrack() {
        return TrackInfo.offlineSnapshot();
    }

    @Nullable
    private JsonObject apiGetJson(String path) {
        HttpResponse<String> response = apiRequest("GET", path, null);
        if (response == null) {
            return null;
        }
        int status = response.statusCode();
        if (status == 204 || status == 205 || response.body() == null || response.body().isBlank()) {
            return null;
        }
        if (status / 100 != 2) {
            if (status == 401) {
                LOGGER.warn("Spotify rejected the access token (HTTP 401); will refresh on next call.");
                lastError = "unauthorized";
            } else {
                LOGGER.debug("GET {} returned HTTP {}.", path, status);
                lastError = "HTTP " + status;
            }
            consecutiveFailures.incrementAndGet();
            return null;
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            LOGGER.error("Could not parse Spotify response for {}: {}", path, exception.toString());
            return null;
        }
    }

    @Nullable
    private HttpResponse<String> apiRequest(String method, String path, @Nullable String body) {
        String token = validAccessToken();
        if (token == null) {
            return null;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(API_BASE + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            builder.method(method.toUpperCase(Locale.ROOT), publisher);

            if (config.debugLogging) {
                LOGGER.info("Spotify API {} {} (body={})", method, path, body);
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (config.debugLogging) {
                LOGGER.info("Spotify API {} {} -> HTTP {}", method, path, response.statusCode());
            }
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            LOGGER.warn("Spotify API {} {} failed: {}", method, path, exception.toString());
            lastError = exception.getClass().getSimpleName();
            consecutiveFailures.incrementAndGet();
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private <T> CompletableFuture<T> supplyAsync(java.util.function.Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor).exceptionally(throwable -> {
            LOGGER.error("Spotify Web API task failed: {}", throwable.toString());
            return null;
        });
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** Generates a cryptographically random, URL-safe, unpadded string. */
    private static String randomUrlSafeString(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** {@code BASE64URL(SHA256(verifier))} used as the PKCE code challenge. */
    private static String sha256Base64Url(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required for PKCE but is unavailable", exception);
        }
    }

    private static boolean openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception exception) {
            LOGGER.warn("Desktop.browse failed ({}), trying OS specific fallbacks.", exception.toString());
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            ProcessBuilder builder;
            if (os.contains("win")) {
                builder = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else if (os.contains("mac")) {
                builder = new ProcessBuilder("open", url);
            } else {
                builder = new ProcessBuilder("xdg-open", url);
            }
            builder.redirectErrorStream(true).start();
            return true;
        } catch (Exception exception) {
            LOGGER.error("Could not launch a browser: {}", exception.toString());
            return false;
        }
    }

    /** Mutable holder filled in by the loopback callback handler. */
    private static final class AuthCallback {
        private volatile String code;
        private volatile String state;
        private volatile String error;
        private volatile String errorDescription;
    }

}
