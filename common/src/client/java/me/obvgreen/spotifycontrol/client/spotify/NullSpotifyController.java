package me.obvgreen.spotifycontrol.client.spotify;

import java.util.concurrent.CompletableFuture;

/**
 * Always-offline controller. Used when no backend can be constructed at all (for example an
 * unsupported OS with no native media bridge available) so that the rest of the mod never has to
 * deal with {@code null} controllers.
 */
public final class NullSpotifyController implements SpotifyController {

    public static final NullSpotifyController INSTANCE = new NullSpotifyController();

    private NullSpotifyController() {
    }

    @Override
    public String id() {
        return "offline";
    }

    @Override
    public String displayName() {
        return "Offline";
    }

    @Override
    public CompletableFuture<TrackInfo> getCurrentlyPlayingTrack() {
        return CompletableFuture.completedFuture(TrackInfo.offlineSnapshot());
    }

    @Override
    public CompletableFuture<Boolean> play() {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    @Override
    public CompletableFuture<Boolean> pause() {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    @Override
    public CompletableFuture<Boolean> playPause() {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    @Override
    public CompletableFuture<Boolean> nextTrack() {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    @Override
    public CompletableFuture<Boolean> previousTrack() {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    @Override
    public CompletableFuture<RepeatMode> setRepeatMode(RepeatMode mode) {
        return CompletableFuture.completedFuture(RepeatMode.OFF);
    }

    @Override
    public boolean canControlRepeat() {
        return false;
    }

    @Override
    public boolean isAuthenticated() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String statusMessage() {
        return "No Spotify backend available on this system.";
    }
}
