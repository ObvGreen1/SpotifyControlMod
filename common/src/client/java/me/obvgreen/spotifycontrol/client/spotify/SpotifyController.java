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

    /**
     * Turns shuffle on or off.
     *
     * @return a future completing with {@code true} when the backend accepted the change
     */
    default CompletableFuture<Boolean> setShuffle(boolean enabled) {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    /** {@code true} when the backend can actually change the shuffle state. */
    default boolean canControlShuffle() {
        return false;
    }

    /** @return the last known shuffle state, or {@code false} when the backend does not report one. */
    default boolean shuffleState() {
        return false;
    }

    /**
     * Sets the playback volume.
     *
     * <p>{@code percent} is an absolute gain, not a delta. Values above 100 are a <em>boost</em> and
     * are only honoured by backends that can amplify: implementations clamp to
     * {@link #maxVolumePercent()} rather than pretending the boost was applied.</p>
     *
     * @return a future completing with {@code true} when the backend accepted the change
     */
    default CompletableFuture<Boolean> setVolume(int percent) {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    /** {@code true} when the backend can change the volume at all. */
    default boolean canControlVolume() {
        return false;
    }

    /**
     * Highest gain this backend can apply, {@code 100} when it cannot amplify.
     *
     * <p>Only OS level volume control can exceed 100% (PulseAudio/PipeWire sink input volume); the
     * Spotify Web API and the Windows per-application mixer both stop at 100%.</p>
     */
    default int maxVolumePercent() {
        return 100;
    }

    /** @return the last known volume in percent, or {@code -1} when the backend does not report one. */
    default int volumePercent() {
        return -1;
    }

    /**
     * Re-reads the player volume from the platform.
     *
     * <p>Backends that learn the volume from their normal playback poll can ignore this; the native
     * bridge has to ask explicitly, which is why the call exists at all.</p>
     *
     * @return a future completing with the freshly read percentage, or {@code -1} when unknown
     */
    default CompletableFuture<Integer> refreshVolume() {
        return CompletableFuture.completedFuture(volumePercent());
    }

    /**
     * Seeks inside the current track.
     *
     * @param positionMs absolute position from the start of the track, clamped by the implementation
     * @return a future completing with {@code true} when the backend accepted the seek
     */
    default CompletableFuture<Boolean> seek(long positionMs) {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    /** {@code true} when the backend can jump inside the current track. */
    default boolean canControlSeek() {
        return false;
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
