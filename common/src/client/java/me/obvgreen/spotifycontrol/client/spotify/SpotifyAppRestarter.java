package me.obvgreen.spotifycontrol.client.spotify;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Closes and reopens the local Spotify desktop client.
 *
 * <p>This is the mechanism behind ad skipping: Spotify has no public API that can skip an
 * advertisement, but killing the client and starting it again drops the ad slot and lets playback
 * resume where the track list left off. Every method blocks the calling thread, so callers must run
 * it on a background executor, never on the render or client thread.</p>
 *
 * <p>Platform support mirrors {@link SpotifyLocalController}: Windows uses {@code taskkill} plus the
 * {@code spotify:} URI handler (which works for both the standalone and the Microsoft Store build),
 * macOS quits the app through AppleScript and reopens it with {@code open -a}, and Linux kills the
 * process and relaunches whichever of {@code spotify}, Flatpak, Snap or {@code xdg-open} is present.</p>
 */
public final class SpotifyAppRestarter {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/AdSkip");

    private static final int MODE_WINDOWS = 1;
    private static final int MODE_LINUX = 2;
    private static final int MODE_MAC = 3;
    private static final int MODE_UNSUPPORTED = 0;

    /** How long a kill or probe command may take before it is abandoned. */
    private static final long KILL_TIMEOUT_MS = 10_000L;
    /** How long to wait for Spotify to actually disappear after a graceful quit. */
    private static final long QUIT_GRACE_MS = 4_000L;

    private final int mode;

    public SpotifyAppRestarter() {
        this.mode = detectMode();
    }

    /** @return {@code true} when this operating system has a supported restart recipe. */
    public boolean supported() {
        return mode != MODE_UNSUPPORTED;
    }

    /** @return a short platform label used in logs and {@code /spotify adskip}. */
    public String platform() {
        return switch (mode) {
            case MODE_WINDOWS -> "Windows";
            case MODE_LINUX -> "Linux";
            case MODE_MAC -> "macOS";
            default -> "unsupported";
        };
    }

    /**
     * Performs a full close/reopen cycle.
     *
     * @param delayMs grace period between the kill and the relaunch
     * @return {@code true} when an existing Spotify client was closed and a new one was started
     */
    public boolean restart(long delayMs) {
        if (!supported()) {
            LOGGER.warn("Ad skip: restarting Spotify is not supported on this platform.");
            return false;
        }
        if (!isClientRunning()) {
            // Nothing local to restart: playback is on another device (phone, speaker, web player).
            LOGGER.info("Ad skip: no local Spotify client found; leaving playback alone.");
            return false;
        }
        if (!kill()) {
            LOGGER.warn("Ad skip: could not close Spotify ({}); aborting restart.", platform());
            return false;
        }
        sleep(Math.max(0L, delayMs));
        if (!launch()) {
            LOGGER.warn("Ad skip: Spotify was closed but could not be started again ({}).", platform());
            return false;
        }
        LOGGER.info("Ad skip: Spotify restarted on {}.", platform());
        return true;
    }

    /** @return {@code true} when a local Spotify client process exists. */
    public boolean isClientRunning() {
        return switch (mode) {
            case MODE_WINDOWS -> probe(KILL_TIMEOUT_MS, "tasklist", "/FI", "IMAGENAME eq Spotify.exe", "/NH")
                    .outputContains("spotify.exe");
            case MODE_LINUX -> run(KILL_TIMEOUT_MS, "pgrep", "-x", "spotify") == 0
                    || run(KILL_TIMEOUT_MS, "pgrep", "-f", "com.spotify.Client") == 0;
            case MODE_MAC -> run(KILL_TIMEOUT_MS, "pgrep", "-x", "Spotify") == 0;
            default -> false;
        };
    }

    /** @return {@code true} when the client was terminated (a graceful quit counts as well). */
    public boolean kill() {
        return switch (mode) {
            case MODE_WINDOWS -> run(KILL_TIMEOUT_MS, "taskkill", "/F", "/T", "/IM", "Spotify.exe") == 0;
            case MODE_LINUX -> run(KILL_TIMEOUT_MS, "pkill", "-x", "spotify") == 0
                    || run(KILL_TIMEOUT_MS, "pkill", "-f", "com.spotify.Client") == 0;
            case MODE_MAC -> killMac();
            default -> false;
        };
    }

    /** @return {@code true} when a launch command was handed to the OS. */
    public boolean launch() {
        return switch (mode) {
            // The spotify: URI handler is registered by both the standalone and the Store build.
            case MODE_WINDOWS -> spawn("rundll32", "url.dll,FileProtocolHandler", "spotify:");
            case MODE_LINUX -> spawn("setsid", "spotify")
                    || spawn("flatpak", "run", "com.spotify.Client")
                    || spawn("snap", "run", "spotify")
                    || spawn("xdg-open", "spotify:");
            case MODE_MAC -> spawn("open", "-a", "Spotify");
            default -> false;
        };
    }

    private boolean killMac() {
        // Ask nicely first so Spotify flushes its state, then force it if it is still hanging around.
        boolean quitRequested = run(KILL_TIMEOUT_MS, "osascript", "-e", "quit app \"Spotify\"") == 0;
        long deadline = System.currentTimeMillis() + QUIT_GRACE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (run(KILL_TIMEOUT_MS, "pgrep", "-x", "Spotify") != 0) {
                return true;
            }
            sleep(250L);
        }
        if (!quitRequested) {
            LOGGER.debug("Ad skip: graceful quit of Spotify was not accepted; forcing.");
        }
        return run(KILL_TIMEOUT_MS, "pkill", "-x", "Spotify") == 0;
    }

    private static int detectMode() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return MODE_WINDOWS;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return MODE_MAC;
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("bsd")) {
            return MODE_LINUX;
        }
        return MODE_UNSUPPORTED;
    }

    /** Runs a command to completion and returns its exit code, or {@code -1} on failure/timeout. */
    private static int run(long timeoutMs, String... command) {
        return probe(timeoutMs, command).exitCode();
    }

    /**
     * Runs a command, capturing its combined output.
     *
     * <p>{@code tasklist} always exits with {@code 0}, so the Windows process check has to inspect the
     * output instead. Only small, bounded commands go through here; the output is read after the
     * process exited, which is safe for outputs well below the pipe buffer.</p>
     */
    private static Probe probe(long timeoutMs, String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                LOGGER.debug("Ad skip: '{}' timed out.", String.join(" ", command));
                process.destroyForcibly();
                return new Probe(-1, null);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Probe(process.exitValue(), output);
        } catch (IOException exception) {
            LOGGER.debug("Ad skip: '{}' failed: {}", String.join(" ", command), exception.toString());
            return new Probe(-1, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Probe(-1, null);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Starts a detached command and stops tracking it; suitable for long-lived GUI processes. */
    private static boolean spawn(String... command) {
        try {
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException exception) {
            LOGGER.debug("Ad skip: could not launch '{}': {}", String.join(" ", command),
                    exception.toString());
            return false;
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Exit code plus captured output, only used by the Windows process probe. */
    private record Probe(int exitCode, @Nullable String output) {

        boolean outputContains(String needle) {
            return output != null && output.toLowerCase(Locale.ROOT).contains(needle);
        }
    }
}
