package me.obvgreen.spotifycontrol.client.config;

import org.jetbrains.annotations.Nullable;

/**
 * Persisted OAuth 2.0 token bundle produced by the PKCE authorization-code flow.
 *
 * <p>Only values that Spotify itself returns are stored: the access token, the long lived refresh
 * token, its absolute expiry timestamp and the scopes that were granted. No client secret exists in
 * this flow, so there is nothing else to protect.</p>
 *
 * @param accessToken  short lived bearer token
 * @param refreshToken long lived refresh token
 * @param expiresAt    epoch milliseconds at which {@code accessToken} stops being valid
 * @param scope        space separated granted scope string
 */
public record TokenSet(
        String accessToken,
        String refreshToken,
        long expiresAt,
        String scope
) {

    /** Seconds of slack subtracted from {@link #expiresAt} to avoid mid-request expiry. */
    private static final long EXPIRY_SLACK_MS = 60_000L;

    public TokenSet {
        accessToken = accessToken == null ? "" : accessToken;
        refreshToken = refreshToken == null ? "" : refreshToken;
        scope = scope == null ? "" : scope;
    }

    /** Builds a bundle from an absolute expiry timestamp. */
    public static TokenSet of(String accessToken, @Nullable String refreshToken, long expiresInSeconds,
                              @Nullable String scope) {
        long expiresAt = System.currentTimeMillis() + Math.max(0L, expiresInSeconds) * 1000L;
        return new TokenSet(accessToken, refreshToken == null ? "" : refreshToken, expiresAt, scope);
    }

    /** {@code true} when the access token is missing, empty or about to expire. */
    public boolean isExpired() {
        return accessToken.isBlank() || System.currentTimeMillis() + EXPIRY_SLACK_MS >= expiresAt;
    }

    /** {@code true} when the bundle can be renewed without user interaction. */
    public boolean canRefresh() {
        return !refreshToken.isBlank();
    }

    /** @return a copy with a refreshed access token, keeping the existing refresh token. */
    public TokenSet refreshed(String newAccessToken, long expiresInSeconds, @Nullable String newScope) {
        String effectiveRefresh = newAccessToken == null ? refreshToken : refreshToken;
        return new TokenSet(
                newAccessToken == null ? accessToken : newAccessToken,
                effectiveRefresh,
                System.currentTimeMillis() + Math.max(0L, expiresInSeconds) * 1000L,
                newScope == null || newScope.isBlank() ? scope : newScope
        );
    }
}
