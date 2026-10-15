package me.obvgreen.spotifycontrol.client.spotify;

import me.obvgreen.spotifycontrol.client.config.ModConfig;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
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
    /** Rolling window used by the ad-skip rate limit. */
    private static final long AD_SKIP_WINDOW_MS = 3_600_000L;
    /** Time Spotify needs after a relaunch before its media session is usable again. */
    private static final long AD_SKIP_RELAUNCH_GRACE_MS = 4_000L;
    /** How long resume-after-restart keeps polling for the relaunched session. */
    private static final long AD_SKIP_RESUME_WINDOW_MS = 20_000L;
    /** Poll interval used while waiting for the relaunched session (and the zombie device to expire). */
    private static final long AD_SKIP_RESUME_POLL_MS = 1_000L;
    /** Per-attempt timeout for the resume transport action. */
    private static final long AD_SKIP_RESUME_ACTION_TIMEOUT_MS = 3_000L;
    /** Minimum gap between two "ad skip did nothing" log lines, so a stuck state cannot spam. */
    private static final long AD_SKIP_NOTICE_INTERVAL_MS = 60_000L;

    private final ModConfig config;
    private final SpotifyWebApiClient webApi;
    private final SpotifyLocalController local;
    private final SpotifyAppRestarter restarter = new SpotifyAppRestarter();
    private final ExecutorService worker;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean environmentUnavailable = new AtomicBoolean(false);
    private final AtomicLong lastActionAt = new AtomicLong(0L);
    private final AtomicLong lastTrackChangeAt = new AtomicLong(0L);
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /** Guards against stacking restarts when several polls report the same advertisement. */
    private final AtomicBoolean adSkipInFlight = new AtomicBoolean(false);
    /** Timestamps of the recent ad-skip restarts, oldest first; guarded by {@link #adSkipHistoryLock}. */
    private final Deque<Long> adSkipHistory = new ArrayDeque<>();
    private final Object adSkipHistoryLock = new Object();

    private volatile TrackInfo current = TrackInfo.offlineSnapshot();
    private volatile SpotifyController active;
    private volatile long lastAdSkipAt;
    private volatile long lastAdSkipNoticeAt;
    @Nullable
    private volatile String lastActionError;
    @Nullable
    private volatile Consumer<TrackInfo> listener;
    @Nullable
    private volatile Consumer<String> notifier;
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

    /**
     * Registers the sink used for short user-facing notices (ad-skip activity).
     *
     * <p>The service deliberately keeps this as a plain consumer so the {@code spotify} package never
     * has to reference Minecraft classes; the caller wraps chat delivery in
     * {@code MinecraftClient.getInstance().execute(...)}.</p>
     */
    public void setNotifier(@Nullable Consumer<String> notifier) {
        this.notifier = notifier;
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

    /**
     * @return the failure recorded by the most recent control action, or {@code null} when the last
     *         action succeeded. Callers that report command results read this right after dispatching,
     *         before the next action clears it.
     */
    @Nullable
    public String actionError() {
        return lastActionError;
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
            considerAdSkip(adjusted);
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
    // Ad skipping
    // ---------------------------------------------------------------------------------------------

    /**
     * Reacts to a freshly published snapshot that looks like an advertisement.
     *
     * <p>Spotify cannot skip ads through any API, so the only lever is closing and reopening the
     * desktop client - the ad slot lives in the player session and does not survive that. The work is
     * handed to the worker because killing and launching a process blocks, and the
     * {@link #adSkipInFlight} gate makes sure a burst of polls (or the Web API and the native bridge
     * agreeing on the same ad) still produces exactly one restart.</p>
     */
    private void considerAdSkip(TrackInfo snapshot) {
        if (shuttingDown.get() || !config.adSkipEnabled) {
            return;
        }
        if (!AdDetector.isAd(snapshot, config)) {
            return;
        }
        if (!adSkipInFlight.compareAndSet(false, true)) {
            return;
        }
        executorRun(() -> {
            try {
                performAdSkip(false);
            } finally {
                adSkipInFlight.set(false);
            }
        });
    }

    /**
     * Skips the advertisement that is playing right now.
     *
     * @param forced {@code true} for the manual "restart Spotify now" buttons, which ignore the
     *               cooldown and the hourly budget but still record the restart
     * @return {@code true} when the restart was actually started
     */
    private boolean performAdSkip(boolean forced) {
        if (!restarter.supported()) {
            noticeOnce("Ad skip: restarting Spotify is not supported on " + restarter.platform() + ".");
            return false;
        }
        long now = System.currentTimeMillis();
        if (!forced) {
            long cooldown = config.adSkipCooldownMillis();
            long since = now - lastAdSkipAt;
            if (lastAdSkipAt != 0L && since < cooldown) {
                noticeOnce(String.format(Locale.ROOT,
                        "Ad skip: cooling down for another %d s.", (cooldown - since + 999L) / 1000L));
                return false;
            }
            int max = config.adSkipMaxPerHour;
            int used = skipsInLastHour(now);
            if (used >= max) {
                noticeOnce(String.format(Locale.ROOT,
                        "Ad skip: hourly limit reached (%d/%d).", used, max));
                return false;
            }
        }
        if (!restarter.isClientRunning()) {
            // Playback is on another device (phone, speaker, web player): there is nothing to restart.
            noticeOnce("Ad skip: no local Spotify client to restart.");
            return false;
        }

        recordAdSkip(now);
        lastAdSkipAt = now;
        TrackInfo ad = current;
        LOGGER.info("Ad skip: advertisement detected ('{}' / '{}'); restarting Spotify.", ad.title(),
                ad.artist());
        notifyUser("Ad detected, restarting Spotify...");

        if (!restarter.restart(config.adSkipRestartDelay())) {
            noticeOnce("Ad skip: Spotify could not be restarted; see the log for details.");
            return false;
        }

        // Give the new process time to register its media session, then re-read the player state.
        sleep(AD_SKIP_RELAUNCH_GRACE_MS);
        refreshInline();

        if (config.adSkipResumePlayback) {
            resumeAfterRestart();
        }
        notifyUser("Ad skipped via Spotify restart.");
        return true;
    }

    /**
     * Resumes playback after a forced restart.
     *
     * <p>A straight "press play once" does not work here for two reasons:</p>
     *
     * <ol>
     *   <li>The Web API keeps reporting the dead session for a while after the client is killed, and
     *       that stale snapshot usually says {@code is_playing = true}, so a single state check
     *       wrongly concludes the client auto-resumed and never presses play.</li>
     *   <li>The native bridge's media key is a <i>toggle</i>: pressing it while Spotify actually did
     *       auto-resume would pause the track again.</li>
     * </ol>
     *
     * <p>So this polls until the newly relaunched session appears, and while it looks paused it
     * re-issues the Web API {@code play} (which is idempotent - a no-op on an already playing
     * session, so repeating it is safe). The native toggle key is only pressed as a last resort
     * when there is no Web API session at all, exactly once.</p>
     */
    private void resumeAfterRestart() {
        long deadline = System.currentTimeMillis() + AD_SKIP_RESUME_WINDOW_MS;
        while (!shuttingDown.get() && System.currentTimeMillis() < deadline) {
            sleep(AD_SKIP_RESUME_POLL_MS);
            refreshInline();
            TrackInfo after = current;
            if (after.offline() || after.advertisement()) {
                // The relaunched client is not visible from the API yet (reconnecting, zombie device
                // still expiring) - keep polling.
                continue;
            }
            if (after.isPlaying()) {
                // Spotify came back playing by itself; nothing to do. Reaching this point with the
                // Web API active also proves the zombie session has expired.
                return;
            }
            if (webApi.isAuthenticated()) {
                // Resumable and paused. Web API play never toggles, so repeating it is harmless.
                await(webApi.play(), AD_SKIP_RESUME_ACTION_TIMEOUT_MS);
                continue;
            }
            // No Web API session: fall back to the native media key. It is a toggle, so press it
            // exactly once and stop - re-checking would risk toggling a session that did resume.
            if (local.isAvailable()) {
                await(local.play(), AD_SKIP_RESUME_ACTION_TIMEOUT_MS);
            }
            return;
        }
        // The whole window elapsed without the session becoming playable.
        LOGGER.info("Ad skip: Spotify did not resume within {} ms; leaving it paused.",
                AD_SKIP_RESUME_WINDOW_MS);
    }

    /**
     * Restarts Spotify on demand, used by {@code /spotify adskip now} and the config screen button.
     *
     * @return {@code true} when the restart was queued
     */
    public boolean restartSpotifyNow() {
        if (shuttingDown.get()) {
            return false;
        }
        if (!adSkipInFlight.compareAndSet(false, true)) {
            return false;
        }
        executorRun(() -> {
            try {
                performAdSkip(true);
            } finally {
                adSkipInFlight.set(false);
            }
        });
        return true;
    }

    /** @return {@code true} while an ad-skip restart is queued or running. */
    public boolean isAdSkipInFlight() {
        return adSkipInFlight.get();
    }

    /** @return a one-line summary of the ad-skip configuration and usage, for {@code /spotify adskip}. */
    public String adSkipStatus() {
        StringBuilder builder = new StringBuilder();
        builder.append(config.adSkipEnabled ? "enabled" : "disabled");
        if (!restarter.supported()) {
            builder.append(" (restart unsupported on ").append(restarter.platform()).append(')');
        }
        builder.append(" - trigger: ").append(config.adSkipTrigger.displayName());
        builder.append(" - cooldown: ").append(config.adSkipCooldownSeconds).append('s');
        builder.append(" - restarts this hour: ").append(skipsInLastHour(System.currentTimeMillis()))
                .append('/').append(config.adSkipMaxPerHour);
        if (adSkipInFlight.get()) {
            builder.append(" - restart in progress");
        }
        return builder.toString();
    }

    private int skipsInLastHour(long now) {
        long cutoff = now - AD_SKIP_WINDOW_MS;
        synchronized (adSkipHistoryLock) {
            while (!adSkipHistory.isEmpty() && adSkipHistory.peekFirst() < cutoff) {
                adSkipHistory.pollFirst();
            }
            return adSkipHistory.size();
        }
    }

    private void recordAdSkip(long now) {
        long cutoff = now - AD_SKIP_WINDOW_MS;
        synchronized (adSkipHistoryLock) {
            adSkipHistory.addLast(now);
            while (!adSkipHistory.isEmpty() && adSkipHistory.peekFirst() < cutoff) {
                adSkipHistory.pollFirst();
            }
        }
    }

    /** Logs an ad-skip explanation at most once a minute, so a stuck state cannot flood the log. */
    private void noticeOnce(String message) {
        long now = System.currentTimeMillis();
        if (now - lastAdSkipNoticeAt < AD_SKIP_NOTICE_INTERVAL_MS) {
            return;
        }
        lastAdSkipNoticeAt = now;
        LOGGER.info(message);
    }

    private void notifyUser(String message) {
        if (!config.adSkipNotify) {
            return;
        }
        Consumer<String> sink = notifier;
        if (sink == null) {
            return;
        }
        try {
            sink.accept(message);
        } catch (RuntimeException exception) {
            LOGGER.debug("Ad-skip notifier threw: {}", exception.toString());
        }
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

    /** Turns shuffle on or off on the active backend. */
    public void setShuffle(boolean enabled) {
        SpotifyController controller = active;
        if (!controller.canControlShuffle()) {
            lastActionError = "shuffle control is not available on backend '" + controller.id() + "'";
            LOGGER.info("Shuffle change ignored: '{}' cannot toggle shuffle.", controller.id());
            return;
        }
        dispatch("shuffle", controller.setShuffle(enabled), null);
    }

    /** Flips the shuffle state. */
    public void toggleShuffle() {
        setShuffle(!shuffleEnabled());
    }

    /** {@code true} when the active backend can toggle shuffle. */
    public boolean canControlShuffle() {
        return active.canControlShuffle();
    }

    /** @return the best known shuffle state for the active backend. */
    public boolean shuffleEnabled() {
        return active.shuffleState();
    }

    /**
     * Sets the absolute playback volume.
     *
     * <p>{@code percent} is clamped to the active backend's ceiling. Values above 100 are only real
     * where the OS mixer allows amplification (Linux sink input volume); the Spotify Web API and the
     * Windows per-application mixer stop at 100 %.</p>
     */
    public void setVolume(int percent) {
        SpotifyController controller = active;
        if (!controller.canControlVolume()) {
            lastActionError = "volume control is not available on backend '" + controller.id() + "'";
            LOGGER.info("Volume change ignored: '{}' cannot change the volume.", controller.id());
            return;
        }
        int clamped = Math.max(0, Math.min(controller.maxVolumePercent(), percent));
        dispatch("volume", controller.setVolume(clamped), null);
    }

    /** {@code true} when the active backend can change the volume. */
    public boolean canControlVolume() {
        return active.canControlVolume();
    }

    /** @return the highest gain the active backend can apply ({@code 100} without amplification). */
    public int maxVolumePercent() {
        return active.maxVolumePercent();
    }

    /** @return last known volume in percent, or {@code -1} while unknown. */
    public int volumePercent() {
        return active.volumePercent();
    }

    /**
     * Re-reads the volume in the background so a freshly opened GUI shows the real value.
     *
     * <p>Does nothing when the active backend cannot control the volume; the read itself never
     * touches the caller's thread.</p>
     */
    public void refreshVolume() {
        if (!canControlVolume()) {
            return;
        }
        executorRun(() -> active.refreshVolume());
    }

    /**
     * Jumps to an absolute position inside the current track.
     *
     * <p>The snapshot is moved immediately so the HUD and the GUI do not snap back to the old
     * position while the seek is in flight.</p>
     */
    public void seek(long positionMs) {
        SpotifyController controller = active;
        if (!controller.canControlSeek()) {
            lastActionError = "seeking is not available on backend '" + controller.id() + "'";
            LOGGER.info("Seek ignored: '{}' cannot seek.", controller.id());
            return;
        }
        TrackInfo snapshot = current;
        long target = Math.max(0L, positionMs);
        if (snapshot.hasDuration()) {
            target = Math.min(target, snapshot.durationMs());
        }
        long clamped = target;
        dispatch("seek", controller.seek(clamped), () -> executorRun(() -> {
            TrackInfo latest = current;
            publish(latest.withProgress(clamped, latest.isPlaying()));
        }));
    }

    /** {@code true} when the active backend can seek inside the current track. */
    public boolean canControlSeek() {
        return active.canControlSeek();
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

    /** Blocks the calling (worker) thread on the transport action and reports its outcome. */
    private static boolean await(CompletableFuture<Boolean> future, long timeoutMs) {
        if (future == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(future.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (Exception exception) {
            LOGGER.debug("Ad-skip resume action failed: {}", exception.toString());
            return false;
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
