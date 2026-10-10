package me.obvgreen.spotifycontrol.client.spotify;

import java.util.concurrent.CompletableFuture;

/**
 * Backend contract for everything that can drive Spotify.
 *
 * <p>Two implementations ship with the mod: {@link SpotifyWebApiClient} (OAuth 2.0 PKCE + Web API)
 * and {@link SpotifyLocalController} (native OS media sessions). {@link SpotifyService} owns both and
 * picks the active one at runtime.</p>
 *
 * <p><b>Threading:</b> every method may be invoked from the render thread but must never block it
 * for longer than a trivial computation. Network and native calls happen on background executors and
 * are reported back through the returned {@link CompletableFuture}.</p>
 */
public interface SpotifyController {

    /** Stable identifier used in logs and the {@code /spotify status} output. */
    String id();

    /** Human readable backend name. */
    String displayName();

    /**
     * Fetches the currently playing track.
     *
     * @return a future completing with a snapshot; never completes exceptionally, implementations
     *         return {@link TrackInfo#offline()} instead so the HUD can degrade gracefully.
     */
    CompletableFuture<TrackInfo> getCurrentlyPlayingTrack();

    /** Resumes playback. */
    CompletableFuture<Boolean> play();

    /** Pauses playback. */
    CompletableFuture<Boolean> pause();

    /** Toggles between play and pause. */
    CompletableFuture<Boolean> playPause();

    /** Skips to the next track. */
    CompletableFuture<Boolean> nextTrack();

    /** Returns to the previous track. */
    CompletableFuture<Boolean> previousTrack();

    /** Switches the repeat mode and reports the mode that was actually applied. */
    CompletableFuture<RepeatMode> setRepeatMode(RepeatMode mode);

    /** {@code true} when the backend can actually change the repeat mode. */
    default boolean canControlRepeat() {
        return true;
    }

    /** {@code true} when the backend is authenticated (Web API) or when a native session exists. */
    boolean isAuthenticated();

    /** {@code true} when the backend can currently reach a Spotify client. */
    boolean isAvailable();

    /** Short status string for the config screen. */
    String statusMessage();

    /** Releases any resources (HTTP servers, native handles) held by this controller. */
    default void shutdown() {
        // Optional.
    }
}
