package me.obvgreen.spotifycontrol.client.spotify;

import me.obvgreen.spotifycontrol.client.config.ModConfig;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Single front door for playback state and transport actions.
 *
 * <p>Owns both backends - the OAuth 2.0 PKCE Web API client and the native OS media controller - and
 * decides which one to use for every call:</p>
 *
 * <ol>
 *   <li>the Web API is used whenever the user is logged in and the client can reach Spotify;</li>
 *   <li>otherwise the native controller takes over when {@code allowLocalFallback} is enabled;</li>
 *   <li>otherwise {@link NullSpotifyController} answers so callers never see {@code null}.</li>
 * </ol>
 *
 * <p><b>Threading.</b> Every public method is safe to call from the Minecraft client thread: nothing
 * here blocks, all work is dispatched onto a single background executor and results are published
 * through a volatile snapshot plus a listener that is expected to hop back to the client thread.</p>
 */
public final class SpotifyService {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Service");
    /** Minimum gap between two identical transport actions, protects against key repeat floods. */
    private static final long ACTION_THROTTLE_MS = 250L;

    private final ModConfig config;
    private final SpotifyWebApiClient webApi;
    private final SpotifyLocalController local;
    private final ExecutorService worker;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean environmentUnavailable = new AtomicBoolean(false);
    private final AtomicLong lastActionAt = new AtomicLong(0L);
    private final AtomicLong lastTrackChangeAt = new AtomicLong(0L);
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private volatile TrackInfo current = TrackInfo.offlineSnapshot();
    private volatile SpotifyController active;
    @Nullable
    private volatile String lastActionError;
    @Nullable
    private volatile Consumer<TrackInfo> listener;
    @Nullable
    private volatile Future<?> pollTask;
    private volatile long lastPollAt;

    public SpotifyService(ModConfig config) {
        this.config = config;
        this.webApi = new SpotifyWebApiClient(config);
        this.local = new SpotifyLocalController(config);
        this.active = resolveController();
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SpotifyControl-Service");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SpotifyControl-Poll");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    /** Starts the polling loop. Safe to call more than once. */
    public void start() {
        if (shuttingDown.get()) {
            return;
        }
        if (pollTask == null) {
            pollTask = scheduler.scheduleWithFixedDelay(this::pollSafely, 0L, 500L, TimeUnit.MILLISECONDS);
        }
        LOGGER.info("Spotify service started with backend '{}'.", active.id());
    }

    /** Stops every background task and releases both backends. */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        Future<?> task = pollTask;
        if (task != null) {
            task.cancel(true);
        }
        scheduler.shutdownNow();
        worker.shutdownNow();
        try {
            webApi.shutdown();
        } catch (RuntimeException exception) {
            LOGGER.warn("Web API shutdown failed: {}", exception.toString());
        }
        try {
            local.shutdown();
        } catch (RuntimeException exception) {
            LOGGER.warn("Local controller shutdown failed: {}", exception.toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------

    /** Registers the sink that receives every new snapshot (invoked off the client thread). */
    public void setListener(@Nullable Consumer<TrackInfo> listener) {
        this.listener = listener;
        if (listener != null) {
            listener.accept(current);
        }
    }

    /** @return the most recent snapshot; never {@code null}. */
    public TrackInfo currentTrack() {
        return current;
    }

    /** @return the backend currently answering requests. */
    public SpotifyController activeController() {
        return active;
    }

    /** @return the Web API backend, exposed for the login/logout buttons of the config screen. */
    public SpotifyWebApiClient webApi() {
        return webApi;
    }

    /** @return the native backend, exposed for diagnostics. */
    public SpotifyLocalController local() {
        return local;
    }

    /** @return {@code true} while the login flow is waiting for the browser round trip. */
    public boolean isAuthorizing() {
        return webApi.isAuthorizing();
    }

    /** @return a human readable description of the current backend and its health. */
    public String statusLine() {
        SpotifyController controller = active;
        StringBuilder builder = new StringBuilder();
        builder.append("Backend: ").append(controller.displayName()).append(" [").append(controller.id())
                .append(']');
        builder.append(" - ").append(controller.statusMessage());
        if (environmentUnavailable.get()) {
            builder.append(" (no Spotify client detected)");
        }
        String error = lastActionError;
        if (error != null) {
            builder.append(" - last action failed: ").append(error);
        }
        return builder.toString();
    }

    /** Clears the recorded action error (used by the config screen after a retry). */
    public void clearActionError() {
        lastActionError = null;
    }

    // ---------------------------------------------------------------------------------------------
    // Polling
    // ---------------------------------------------------------------------------------------------

    /**
     * Forces an immediate refresh, ignoring the configured interval: the last-poll timestamp is
     * cleared so the next poll tick goes through, and the work is queued on the service worker.
     *
     * <p>Must only be called from outside the worker — worker tasks use {@link #refreshInline()}.</p>
     */
    public void refreshNow() {
        lastPollAt = 0L;
        worker.execute(this::pollSafely);
    }

    /** Resets the poll cadence and queries the active backend in place. Call only on the worker. */
    private void refreshInline() {
        lastPollAt = System.currentTimeMillis();
        refresh();
    }

    private void pollSafely() {
        if (shuttingDown.get()) {
            return;
        }
        long interval = config.pollIntervalMillis();
        long now = System.currentTimeMillis();
        if (lastPollAt != 0L && now - lastPollAt < interval) {
            return;
        }
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        lastPollAt = now;
        try {
            refresh();
        } catch (RuntimeException exception) {
            LOGGER.error("Unexpected polling failure: {}", exception.toString());
        } finally {
            polling.set(false);
        }
    }

    /**
     * Queries the active backend and publishes the result.
     *
     * <p>Cadence is owned entirely by the scheduler: {@link #pollSafely()} returns early until
     * {@link ModConfig#pollIntervalMillis()} has elapsed, and {@link #refreshNow()} is the explicit
     * "user asked for it" path that bypasses the interval.</p>
     */
    private void refresh() {
        if (shuttingDown.get()) {
            return;
        }
        SpotifyController controller = resolveController();
        active = controller;
        CompletableFuture<TrackInfo> future = controller.getCurrentlyPlayingTrack();
        TrackInfo snapshot;
        try {
            // The controllers already run on their own executors, so a bounded join here is safe:
            // this method itself never runs on the client thread.
            snapshot = future.get(15L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception exception) {
            LOGGER.debug("Polling '{}' failed: {}", controller.id(), exception.toString());
            snapshot = TrackInfo.offlineSnapshot();
        }
        if (snapshot == null) {
            snapshot = TrackInfo.offlineSnapshot();
        }
        publish(snapshot);
    }

    private void publish(TrackInfo snapshot) {
        TrackInfo adjusted = interpolate(snapshot);
        TrackInfo previous = current;
        current = adjusted;
        if (!adjusted.equals(previous)) {
            Consumer<TrackInfo> sink = listener;
            if (sink != null) {
                try {
                    sink.accept(adjusted);
                } catch (RuntimeException exception) {
                    LOGGER.error("Track listener threw: {}", exception.toString());
                }
            }
        }
    }

    /**
     * The Web API only reports progress every few seconds; between polls the HUD would freeze, so the
     * elapsed time since the snapshot is added while the track is playing.
     */
    private TrackInfo interpolate(TrackInfo snapshot) {
        if (!snapshot.isPlaying() || !snapshot.hasDuration()) {
            return snapshot;
        }
        long since = System.currentTimeMillis() - snapshot.lastUpdated();
        if (since <= 0L || since > 15_000L) {
            return snapshot;
        }
        long projected = snapshot.progressMs() + since;
        if (projected >= snapshot.durationMs()) {
            projected = snapshot.durationMs();
        }
        return snapshot.withProgress(projected, true);
    }

    // ---------------------------------------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------------------------------------

    /** Toggles play/pause. */
    public void togglePlayPause() {
        SpotifyController controller = active;
        dispatch("toggle", controller.playPause(), null);
    }

    /** Resumes playback. */
    public void play() {
        dispatch("play", active.play(), null);
    }

    /** Pauses playback. */
    public void pause() {
        dispatch("pause", active.pause(), null);
    }

    /** Skips forward. */
    public void next() {
        dispatch("next", active.nextTrack(), this::markTrackChange);
    }

    /** Skips backwards. */
    public void previous() {
        dispatch("previous", active.previousTrack(), this::markTrackChange);
    }

    /**
     * Cycles the repeat mode.
     *
     * @param local {@code true} when the cycle was initiated from the local keybind, which only
     *              advances the mode when the active backend can actually apply it
     */
    public void cycleRepeat() {
        SpotifyController controller = active;
        if (!controller.canControlRepeat()) {
            lastActionError = "repeat control needs Spotify login";
            LOGGER.info("Repeat toggle ignored: '{}' cannot change the repeat mode.", controller.id());
            return;
        }
        RepeatMode next = current.repeatMode().next();
        dispatch("repeat", controller.setRepeatMode(next).thenApply(mode -> mode != null), null);
    }

    /** Jumps straight to a repeat mode (used by the config screen and {@code /spotify repeat <mode>}). */
    public void setRepeatMode(RepeatMode mode) {
        SpotifyController controller = active;
        if (!controller.canControlRepeat()) {
            lastActionError = "repeat control needs Spotify login";
            return;
        }
        dispatch("repeat", controller.setRepeatMode(mode).thenApply(applied -> applied != null), null);
    }

    private void markTrackChange() {
        lastTrackChangeAt.set(System.currentTimeMillis());
        // Skip ahead optimistically so the HUD does not show the finished track for a whole interval.
        // Runs as one worker task on purpose: the worker is single threaded, so the snapshot update
        // and the follow-up query stay in order (and refresh() must not re-queue itself here).
        executorRun(() -> {
            TrackInfo snapshot = current;
            TrackInfo optimistic = snapshot.withProgress(0L, snapshot.isPlaying());
            publish(optimistic);
            refreshInline();
        });
    }

    private void executorRun(Runnable runnable) {
        if (!shuttingDown.get()) {
            worker.execute(runnable);
        }
    }

    private void dispatch(String name, CompletableFuture<Boolean> future, @Nullable Runnable onSuccess) {
        long now = System.currentTimeMillis();
        long previous = lastActionAt.get();
        if (now - previous < ACTION_THROTTLE_MS) {
            LOGGER.debug("Throttled '{}' action ({} ms since the previous one).", name, now - previous);
            return;
        }
        lastActionAt.set(now);
        CompletableFuture<Boolean> safe = future == null
                ? CompletableFuture.completedFuture(Boolean.FALSE)
                : future;
        safe.thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                lastActionError = null;
                if (onSuccess != null) {
                    onSuccess.run();
                } else {
                    // Give the player a moment to reflect the change, then re-read. This already runs
                    // on the worker, so call refresh() directly instead of re-queueing.
                    executorRun(() -> {
                        sleep(Math.min(400L, ACTION_THROTTLE_MS + 100L));
                        refreshInline();
                    });
                }
            } else {
                lastActionError = name + " rejected by the player";
                LOGGER.warn("Spotify action '{}' was not accepted.", name);
            }
        }).exceptionally(throwable -> {
            lastActionError = name + ": " + throwable.getMessage();
            LOGGER.error("Spotify action '{}' failed: {}", name, throwable.toString());
            return null;
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Authentication
    // ---------------------------------------------------------------------------------------------

    /**
     * Runs the PKCE flow on a background thread and refreshes the snapshot afterwards.
     *
     * @param onResult receives the login outcome; invoked off the client thread
     */
    public void login(Consumer<SpotifyWebApiClient.AuthResult> onResult) {
        if (webApi.isAuthorizing()) {
            onResult.accept(SpotifyWebApiClient.AuthResult.ALREADY_RUNNING);
            return;
        }
        worker.execute(() -> {
            SpotifyWebApiClient.AuthResult result = webApi.authorize();
            if (result == SpotifyWebApiClient.AuthResult.SUCCESS) {
                active = resolveController();
                environmentUnavailable.set(false);
                refreshInline();
            }
            onResult.accept(result);
        });
    }

    /** Clears the stored tokens on a background thread. */
    public void logout(Runnable onDone) {
        worker.execute(() -> {
            webApi.logout();
            active = resolveController();
            publish(TrackInfo.offlineSnapshot());
            refreshInline();
            onDone.run();
        });
    }

    /** @return the backend that should answer right now. */
    private SpotifyController resolveController() {
        if (config.tokens() != null && webApi.isAuthenticated()) {
            return webApi;
        }
        if (config.allowLocalFallback && local.isAvailable()) {
            return local;
        }
        return NullSpotifyController.INSTANCE;
    }

    /** Human readable summary of the transport keys, used by {@code /spotify status}. */
    public String describeActions() {
        SpotifyController controller = active;
        String repeat = controller.canControlRepeat() ? "repeat available" : "repeat unavailable";
        return String.format(Locale.ROOT, "%s (%s)", controller.displayName(), repeat);
    }
}
