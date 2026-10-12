package me.obvgreen.spotifycontrol.client.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Secondary / fallback backend: drives whatever media session the operating system already exposes.
 *
 * <ul>
 *   <li><b>Windows</b> - a long lived PowerShell bridge process that reads the System Media
 *       Transport Controls (SMTC) session for the Spotify app, which supplies title, artist, album,
 *       artwork thumbnail, duration, playback position, shuffle/repeat state and the matching
 *       shuffle/repeat/seek controls. It also synthesises the global {@code VK_MEDIA_*} transport
 *       keys through {@code user32.dll}, and falls back to the player's window title when no SMTC
 *       session exists yet.</li>
 *   <li><b>Linux</b> - MPRIS over D-Bus ({@code dbus-send}) for metadata, duration, position and
 *       loop status, plus {@code playerctl} for transport control.</li>
 *   <li><b>macOS</b> - AppleScript against the Spotify application when {@code osascript} exists.</li>
 * </ul>
 *
 * <p>This backend needs no login at all, which makes it the perfect companion to the PKCE flow: it
 * keeps the HUD alive (and the keybinds working) when the user never authenticates or when the access
 * token could not be refreshed.</p>
 *
 * <p>The PowerShell script is deliberately written in PowerShell 5.1 compatible syntax. For SMTC it
 * relies on the WinRT projection that {@code System.Runtime.WindowsRuntime} provides, bridging
 * {@code IAsyncOperation<T>} to a task through reflection; that path needs no compiled reference to
 * {@code Windows.Media.winmd}, which the compiler bundled with Windows PowerShell cannot supply.</p>
 */
public final class SpotifyLocalController implements SpotifyController {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Local");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(12);
    /** How metadata is sourced; transport control works in all three modes. */
    private static final int MODE_WINDOWS = 1;
    private static final int MODE_LINUX = 2;
    private static final int MODE_MAC = 3;
    /**
     * Highest gain the local backend will attempt on Linux. PulseAudio/PipeWire accept sink-input
     * volumes well past unity gain; every other platform stops at 100%.
     */
    private static final int MAX_BOOST_PERCENT = 200;

    private final ModConfig config;
    private final ExecutorService executor;
    private final ReentrantLock bridgeLock = new ReentrantLock();
    private final int mode;

    /** Last volume the bridge reported or accepted, or {@code -1} while unknown. */
    private volatile int volumePercent = -1;
    /** Last shuffle state the bridge accepted. */
    private volatile boolean shuffleState;

    /**
     * How long a locally accepted shuffle/repeat change outranks the polled session state.
     *
     * <p>Windows refreshes the SMTC shuffle and repeat properties a few seconds after they change,
     * so polls arriving in that window would otherwise flip the GUI label back to the old value
     * before settling on the new one.</p>
     */
    private static final long ASSUMED_STATE_MS = 6000L;

    /** Wall-clock stamp of the last locally accepted shuffle change, or {@code 0} when none happened. */
    private volatile long shuffleAssumedAt;
    /** Wall-clock stamp of the last locally accepted repeat change, or {@code 0} when none happened. */
    private volatile long repeatAssumedAt;
    /** Repeat mode the user last requested, honoured until the polled property catches up. */
    private volatile RepeatMode repeatAssumed = RepeatMode.OFF;

    /**
     * Capability flags reported by the Windows SMTC session on the most recent poll.
     *
     * <p>SMTC is the only Windows surface that exposes shuffle, repeat and absolute seek, and it is
     * also the only one that reports a track duration. The flags are captured while parsing a poll
     * response and read from the render thread, so they are plain {@code volatile} fields: no WinRT
     * call is ever made outside the bridge worker.</p>
     */
    private volatile boolean smtcAvailable;
    private volatile boolean smtcCanSeek;
    private volatile boolean smtcCanShuffle;
    private volatile boolean smtcCanRepeat;

    @Nullable
    private NativeMediaBridge bridge;
    @Nullable
    private volatile String lastError;

    public SpotifyLocalController(ModConfig config) {
        this.config = config;
        this.mode = detectMode();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SpotifyControl-Local");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ---------------------------------------------------------------------------------------------
    // SpotifyController
    // ---------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return "local";
    }

    @Override
    public String displayName() {
        return "Native OS media session (" + platformName() + ")";
    }

    @Override
    public CompletableFuture<TrackInfo> getCurrentlyPlayingTrack() {
        return supplyAsync(() -> {
            String json = sendCommand("get");
            if (json == null || json.equals("null") || json.isBlank()) {
                lastError = "no media session";
                return TrackInfo.offlineSnapshot();
            }
            TrackInfo parsed = parseTrack(json);
            if (parsed == null) {
                lastError = "unparsable media payload";
                return TrackInfo.offlineSnapshot();
            }
            lastError = null;
            return parsed;
        });
    }

    @Override
    public CompletableFuture<Boolean> play() {
        return supplyAsync(() -> control("play"));
    }

    @Override
    public CompletableFuture<Boolean> pause() {
        return supplyAsync(() -> control("pause"));
    }

    @Override
    public CompletableFuture<Boolean> playPause() {
        // The synthesised media key is a toggle by nature, so this is always exact.
        return supplyAsync(() -> control("toggle"));
    }

    @Override
    public CompletableFuture<Boolean> nextTrack() {
        return supplyAsync(() -> control("next"));
    }

    @Override
    public CompletableFuture<Boolean> previousTrack() {
        return supplyAsync(() -> control("previous"));
    }

    @Override
    public CompletableFuture<RepeatMode> setRepeatMode(RepeatMode mode) {
        RepeatMode requested = mode == null ? RepeatMode.OFF : mode;
        String wire = switch (requested) {
            case OFF -> "repeat off";
            case CONTEXT -> "repeat context";
            case TRACK -> "repeat track";
        };
        return supplyAsync(() -> {
            String response = sendCommand(wire);
            if (response != null && response.startsWith("mode:")) {
                RepeatMode applied = RepeatMode.fromApiValue(response.substring("mode:".length()));
                repeatAssumed = applied;
                repeatAssumedAt = System.currentTimeMillis();
                return applied;
            }
            return RepeatMode.OFF;
        });
    }

    @Override
    public boolean canControlRepeat() {
        if (mode == MODE_WINDOWS) {
            return smtcAvailable && smtcCanRepeat;
        }
        // MPRIS has a writable LoopStatus, and Spotify's AppleScript dictionary exposes `repeating`.
        return mode == MODE_LINUX || mode == MODE_MAC;
    }

    @Override
    public CompletableFuture<Boolean> setShuffle(boolean enabled) {
        if (!canControlShuffle()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        return supplyAsync(() -> {
            String response = sendCommand("shuffle " + (enabled ? "on" : "off"));
            boolean ok = "ok".equals(response);
            if (ok) {
                shuffleState = enabled;
                shuffleAssumedAt = System.currentTimeMillis();
                lastError = null;
            } else {
                lastError = "shuffle rejected";
            }
            return ok;
        });
    }

    @Override
    public boolean canControlShuffle() {
        if (mode == MODE_WINDOWS) {
            return smtcAvailable && smtcCanShuffle;
        }
        // MPRIS has a writable Shuffle property, and Spotify's AppleScript dictionary exposes
        // `shuffling`.
        return mode == MODE_LINUX || mode == MODE_MAC;
    }

    @Override
    public boolean shuffleState() {
        return shuffleState;
    }

    @Override
    public CompletableFuture<Boolean> setVolume(int percent) {
        if (!canControlVolume()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        int requested = Math.max(0, Math.min(maxVolumePercent(), percent));
        return supplyAsync(() -> {
            String response = sendCommand("volume " + requested);
            int applied = parseVolumeResponse(response);
            if (applied < 0) {
                lastError = "volume rejected";
                return Boolean.FALSE;
            }
            volumePercent = applied;
            lastError = null;
            return Boolean.TRUE;
        });
    }

    @Override
    public boolean canControlVolume() {
        // Windows gets per-application volume through the Core Audio session API, Linux through
        // playerctl/pactl and macOS through Spotify's AppleScript `sound volume` property.
        return mode != 0;
    }

    @Override
    public int maxVolumePercent() {
        // Only a PulseAudio/PipeWire sink input can be pushed past unity gain.
        return mode == MODE_LINUX ? MAX_BOOST_PERCENT : 100;
    }

    @Override
    public int volumePercent() {
        return volumePercent;
    }

    @Override
    public CompletableFuture<Integer> refreshVolume() {
        if (!canControlVolume()) {
            return CompletableFuture.completedFuture(-1);
        }
        return supplyAsync(() -> {
            int applied = parseVolumeResponse(sendCommand("volume get"));
            if (applied < 0) {
                lastError = "volume query failed";
                return -1;
            }
            volumePercent = applied;
            lastError = null;
            return applied;
        });
    }

    @Override
    public CompletableFuture<Boolean> seek(long positionMs) {
        if (!canControlSeek()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        long requested = Math.max(0L, positionMs);
        return supplyAsync(() -> {
            String response = sendCommand("seek " + requested);
            boolean ok = "ok".equals(response);
            if (!ok) {
                lastError = "seek rejected";
            }
            return ok;
        });
    }

    @Override
    public boolean canControlSeek() {
        if (mode == MODE_WINDOWS) {
            return smtcAvailable && smtcCanSeek;
        }
        // playerctl sets `position` and Spotify's AppleScript dictionary exposes `player position`.
        return mode == MODE_LINUX || mode == MODE_MAC;
    }

    /** Reads the numeric part of a {@code volume:<percent>} bridge reply, or {@code -1}. */
    private static int parseVolumeResponse(@Nullable String response) {
        if (response == null || !response.startsWith("volume:")) {
            return -1;
        }
        try {
            return Integer.parseInt(response.substring("volume:".length()).trim());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    @Override
    public boolean isAuthenticated() {
        // No authentication is required for native media control.
        return true;
    }

    @Override
    public boolean isAvailable() {
        return mode != 0;
    }

    @Override
    public String statusMessage() {
        if (mode == 0) {
            return "Native media backend unavailable on " + platformName() + ".";
        }
        if (lastError != null) {
            return "Native bridge ready (" + platformName() + ") - last status: " + lastError;
        }
        return "Native bridge ready (" + platformName() + ").";
    }

    @Override
    public void shutdown() {
        bridgeLock.lock();
        try {
            if (bridge != null) {
                bridge.close();
                bridge = null;
            }
        } finally {
            bridgeLock.unlock();
        }
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------
    // Bridge plumbing
    // ---------------------------------------------------------------------------------------------

    /** Sends a command and returns the payload after the wire marker, or {@code null} on failure. */
    @Nullable
    private String sendCommand(String argument) {
        bridgeLock.lock();
        try {
            NativeMediaBridge active = ensureBridge();
            if (active == null) {
                return null;
            }
            String response = active.request(argument, COMMAND_TIMEOUT);
            if (response == null) {
                LOGGER.debug("Native bridge gave no answer for '{}'.", argument);
                // The bridge may have died; drop it so the next call restarts it.
                active.close();
                bridge = null;
            }
            return response;
        } finally {
            bridgeLock.unlock();
        }
    }

    private boolean control(String action) {
        String response = sendCommand(action);
        boolean ok = "ok".equals(response);
        if (!ok) {
            LOGGER.debug("Native media command '{}' returned '{}'.", action, response);
            lastError = "command rejected";
        }
        return ok;
    }

    @Nullable
    private NativeMediaBridge ensureBridge() {
        NativeMediaBridge existing = bridge;
        if (existing != null && existing.isAlive()) {
            return existing;
        }
        if (existing != null) {
            existing.close();
            bridge = null;
        }
        if (mode == 0) {
            return null;
        }
        try {
            NativeMediaBridge created = NativeMediaBridge.start(config.debugLogging);
            bridge = created;
            return created;
        } catch (IOException exception) {
            LOGGER.error("Could not start the native media bridge: {}", exception.toString());
            lastError = "bridge start failed";
            return null;
        }
    }

    /** Converts the bridge's JSON payload into a {@link TrackInfo}. */
    @Nullable
    private TrackInfo parseTrack(String json) {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            String title = optionalString(object, "title");
            String artist = optionalString(object, "artist");
            String album = optionalString(object, "album");
            String artUrl = optionalString(object, "artUrl");
            long durationMs = optionalLong(object, "durationMs");
            long progressMs = optionalLong(object, "progressMs");
            boolean playing = object.has("isPlaying") && !object.get("isPlaying").isJsonNull()
                    && object.get("isPlaying").getAsBoolean();
            RepeatMode repeat = object.has("repeat") && !object.get("repeat").isJsonNull()
                    ? RepeatMode.fromApiValue(object.get("repeat").getAsString())
                    : RepeatMode.OFF;
            String device = optionalString(object, "source");
            if (device == null) {
                device = platformName() + " media session";
            }
            captureCapabilities(object);
            RepeatMode settled = pendingRepeat();
            if (settled != null) {
                repeat = settled;
            }
            return TrackInfo.of(title, artist, album, artUrl, durationMs, progressMs, playing, repeat,
                    device);
        } catch (RuntimeException exception) {
            LOGGER.error("Malformed native media payload: {} ({})", json, exception.toString());
            return null;
        }
    }

    /**
     * Mirrors the capability flags and shuffle state of an SMTC poll into the cached fields read by
     * {@link #canControlSeek()}, {@link #canControlShuffle()}, {@link #canControlRepeat()} and
     * {@link #shuffleState()}.
     *
     * <p>A payload from any other source (MPRIS, AppleScript, the window-title fallback) clears
     * {@link #smtcAvailable}, so Windows falls back to "no shuffle/repeat/seek" instead of
     * advertising a control the bridge cannot reach.</p>
     */
    private void captureCapabilities(JsonObject object) {
        JsonObject smtc = object.has("smtc") && object.get("smtc").isJsonObject()
                ? object.getAsJsonObject("smtc")
                : null;
        if (smtc == null) {
            smtcAvailable = false;
            smtcCanSeek = false;
            smtcCanShuffle = false;
            smtcCanRepeat = false;
            return;
        }
        smtcAvailable = true;
        smtcCanSeek = smtc.has("canSeek") && !smtc.get("canSeek").isJsonNull()
                && smtc.get("canSeek").getAsBoolean();
        smtcCanShuffle = smtc.has("canShuffle") && !smtc.get("canShuffle").isJsonNull()
                && smtc.get("canShuffle").getAsBoolean();
        smtcCanRepeat = smtc.has("canRepeat") && !smtc.get("canRepeat").isJsonNull()
                && smtc.get("canRepeat").getAsBoolean();
        if (smtc.has("shuffle") && !smtc.get("shuffle").isJsonNull()
                && !assumedRecently(shuffleAssumedAt)) {
            shuffleState = smtc.get("shuffle").getAsBoolean();
        }
    }

    /**
     * Windows republishes {@code IsShuffleActive} and {@code AutoRepeatMode} a few seconds after they
     * change, so the polls that land in that window still carry the previous value. Returns the mode
     * the user just asked for until the session property has had time to catch up.
     *
     * @return the pending repeat mode, or {@code null} when the polled value is authoritative
     */
    @Nullable
    private RepeatMode pendingRepeat() {
        return assumedRecently(repeatAssumedAt) ? repeatAssumed : null;
    }

    /** @return {@code true} while a local write is still waiting for the session property to catch up */
    private static boolean assumedRecently(long stamp) {
        return stamp > 0L && System.currentTimeMillis() - stamp < ASSUMED_STATE_MS;
    }

    @Nullable
    private static String optionalString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        String value = object.get(key).getAsString();
        return value.isBlank() ? null : value;
    }

    private static long optionalLong(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException exception) {
            return 0L;
        }
    }

    private <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor).exceptionally(throwable -> {
            LOGGER.error("Native media task failed: {}", throwable.toString());
            return null;
        });
    }

    private static int detectMode() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return MODE_WINDOWS;
        }
        if (os.contains("mac")) {
            return MODE_MAC;
        }
        if (os.contains("linux")) {
            return MODE_LINUX;
        }
        return 0;
    }

    private static String platformName() {
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "Windows";
        }
        if (os.contains("mac")) {
            return "macOS";
        }
        if (os.contains("linux")) {
            return "Linux";
        }
        return os;
    }

    // ---------------------------------------------------------------------------------------------
    // Long-lived PowerShell bridge (Windows)
    // ---------------------------------------------------------------------------------------------

    /**
     * Wraps the long-lived {@code MediaBridge.ps1} PowerShell process.
     *
     * <p>The script is written to a temporary file and launched with {@code -File} so its location is
     * available to {@code $PSScriptRoot}. Communication is strictly synchronous and serialized by the
     * owning controller's lock: one command line in, one JSON line out.</p>
     */
    private static final class NativeMediaBridge implements AutoCloseable {

        /** Marker prefixed to script output so PowerShell banners can be skipped safely. */
        private static final String WIRE_PREFIX = "@@SC@@";

        private final Process process;
        private final BufferedReader stdout;
        private final BufferedWriter stdin;
        private final boolean debug;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private NativeMediaBridge(Process process, boolean debug) {
            this.process = process;
            this.debug = debug;
            this.stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.stdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        static NativeMediaBridge start(boolean debug) throws IOException {
            Path script = writeBridgeScript();
            ProcessBuilder builder = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", script.toAbsolutePath().toString());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            NativeMediaBridge bridge = new NativeMediaBridge(process, debug);
            // Give the pipeline a moment to spin up so the first real command is not lost.
            bridge.waitUntilReady(Duration.ofSeconds(20));
            return bridge;
        }

        /** Writes (and caches) the PowerShell bridge script in the game's temporary directory. */
        private static Path writeBridgeScript() throws IOException {
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "spotifycontrol");
            Files.createDirectories(directory);
            Path script = directory.resolve("MediaBridge.ps1");
            Files.writeString(script, BRIDGE_SCRIPT, StandardCharsets.UTF_8);
            return script;
        }

        /** Blocks until the script prints its readiness payload. */
        private void waitUntilReady(Duration timeout) throws IOException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                String line = readLine(deadline);
                if (line == null) {
                    throw new IOException("Native media bridge exited during startup.");
                }
                if (line.contains("\"ready\"")) {
                    if (debug) {
                        LOGGER.info("Native media bridge ready: {}", line);
                    }
                    return;
                }
            }
            throw new IOException("Native media bridge did not report readiness in time.");
        }

        @Nullable
        String request(String argument, Duration timeout) {
            if (closed.get() || !process.isAlive()) {
                return null;
            }
            try {
                stdin.write(argument);
                stdin.write('\n');
                stdin.flush();
            } catch (IOException exception) {
                LOGGER.error("Could not write to the native media bridge: {}", exception.toString());
                return null;
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            String line = readLine(deadline);
            if (debug && line != null) {
                LOGGER.info("Native media bridge '{}' -> {}", argument, line);
            }
            return line;
        }

        @Nullable
        private String readLine(long deadlineNanos) {
            try {
                while (System.nanoTime() < deadlineNanos) {
                    long remainingMs = Math.max(50L,
                            Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis());
                    if (stdout.ready()) {
                        String line = stdout.readLine();
                        if (line == null) {
                            return null;
                        }
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) {
                            continue;
                        }
                        int marker = trimmed.indexOf(WIRE_PREFIX);
                        if (marker < 0) {
                            if (debug) {
                                LOGGER.info("Native bridge chatter: {}", trimmed);
                            }
                            continue;
                        }
                        return trimmed.substring(marker + WIRE_PREFIX.length());
                    }
                    Thread.sleep(Math.min(25L, Math.max(1L, remainingMs)));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                LOGGER.debug("Native bridge read failed: {}", exception.toString());
            }
            return null;
        }

        boolean isAlive() {
            return !closed.get() && process.isAlive();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                stdin.write("quit\n");
                stdin.flush();
            } catch (IOException ignored) {
                // Process may already be gone.
            }
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            try {
                stdin.close();
                stdout.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
        }
    }

    /**
     * PowerShell bridge: synthesised transport keys plus window-title metadata on Windows, MPRIS on
     * Linux and AppleScript on macOS, all speaking the {@code @@SC@@<json>} line protocol.
     *
     * <p>Windows metadata and the shuffle/repeat/seek controls come from the SMTC session through the
     * WinRT projection in {@code System.Runtime.WindowsRuntime}; the transport keys and the
     * window-title fallback only use {@code Add-Type} with a plain {@code DllImport} helper, which is
     * all the C# compiler bundled with Windows PowerShell can build without a
     * {@code Windows.Media.winmd} reference.</p>
     */
    private static final String BRIDGE_SCRIPT = String.join("\n",
            "# Spotify Control - native media bridge (auto-generated, safe to delete).",
            "$ErrorActionPreference = 'Continue'",
            "$ProgressPreference = 'SilentlyContinue'",
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
            "$OutputEncoding = [System.Text.Encoding]::UTF8",
            "$MARK = '@@SC@@'",
            "",
            "# --- SMTC (Windows System Media Transport Controls) -----------------------",
            "# Reachable from Windows PowerShell 5.1 through the WinRT projection: no C# reference to",
            "# Windows.Media.winmd is needed, only System.Runtime.WindowsRuntime plus the AsTask bridge.",
            "$smtcReady = $false",
            "$smtcAsTask = $null",
            "$smtcMgrType = $null",
            "try {",
            "    Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null",
            "    $smtcAsTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {",
            "        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'",
            "    })[0]",
            "    $smtcMgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]",
            "    $smtcReady = ($null -ne $smtcAsTask -and $null -ne $smtcMgrType)",
            "} catch { $smtcReady = $false }",
            "",
            "function Await-Op($op, $type) {",
            "    if (-not $smtcReady) { return $null }",
            "    $m = $smtcAsTask.MakeGenericMethod($type)",
            "    $t = $m.Invoke($null, @($op))",
            "    $t.Wait(-1) | Out-Null",
            "    return $t.Result",
            "}",
            "",
            "function Get-SmtcSession {",
            "    if (-not $smtcReady) { return $null }",
            "    try {",
            "        $mgr = Await-Op ($smtcMgrType::RequestAsync()) $smtcMgrType",
            "        if ($null -eq $mgr) { return $null }",
            "        foreach ($s in @($mgr.GetSessions())) {",
            "            if ($s.SourceAppUserModelId -match 'Spotify') { return $s }",
            "        }",
            "        return $null",
            "    } catch { return $null }",
            "}",
            "",
            "function Get-SmtcTrack {",
            "    $session = Get-SmtcSession",
            "    if ($null -eq $session) { return $null }",
            "    try {",
            "        $propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime]",
            "        $props = Await-Op ($session.TryGetMediaPropertiesAsync()) $propsType",
            "        $tl = $session.GetTimelineProperties()",
            "        $pb = $session.GetPlaybackInfo()",
            "        $ctl = $pb.Controls",
            "        $artUrl = $null",
            "        if ($props.Thumbnail -ne $null) {",
            "            try {",
            "                $dir = Join-Path $env:LOCALAPPDATA 'spotifycontrol-art'",
            "                if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }",
            "                # Deterministic per-track name: the same track always maps to the same file, so",
            "                # the mod's texture cache keys stay stable across polls.",
            "                $key = [string]$props.Title + '|' + [string]$props.Artist + '|' + [string]$props.AlbumTitle",
            "                $md5 = [System.Security.Cryptography.MD5]::Create()",
            "                $digest = $md5.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($key))",
            "                $name = ([System.BitConverter]::ToString($digest) -replace '-', '').ToLowerInvariant() + '.png'",
            "                $target = Join-Path $dir $name",
            "                # The poll runs twice a second; only extract the stream when the file is missing.",
            "                if (-not (Test-Path $target) -or (Get-Item $target).Length -eq 0) {",
            "                    $rasType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType=WindowsRuntime]",
            "                    $ras = Await-Op ($props.Thumbnail.OpenReadAsync()) $rasType",
            "                    $mi = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [type[]]@([Windows.Storage.Streams.IRandomAccessStream]))",
            "                    if ($mi -ne $null) {",
            "                        $net = $mi.Invoke($null, @($ras))",
            "                        $fs = [System.IO.File]::Create($target)",
            "                        $net.CopyTo($fs)",
            "                        $fs.Close()",
            "                        $net.Dispose()",
            "                    }",
            "                    # Prune thumbnails of long-gone tracks so the folder cannot grow forever.",
            "                    try {",
            "                        $cutoff = (Get-Date).AddMinutes(-30)",
            "                        Get-ChildItem -Path $dir -Filter '*.png' -ErrorAction SilentlyContinue |",
            "                            Where-Object { $_.Name -ne $name -and $_.LastWriteTime -lt $cutoff } |",
            "                            Remove-Item -Force -ErrorAction SilentlyContinue",
            "                    } catch { }",
            "                }",
            "                if (Test-Path $target) { $artUrl = 'file:///' + ($target -replace '\\\\', '/') }",
            "            } catch { }",
            "        }",
            "        $repeat = 'off'",
            "        # Compared by name: the projected enum type has no resolvable type literal in this host.",
            "        $rm = [string]$pb.AutoRepeatMode",
            "        if ($rm -eq 'List') { $repeat = 'context' }",
            "        if ($rm -eq 'Track') { $repeat = 'track' }",
            "        return [pscustomobject]@{",
            "            title = $props.Title; artist = $props.Artist; album = $props.AlbumTitle; artUrl = $artUrl;",
            "            durationMs = [long]$tl.EndTime.TotalMilliseconds; progressMs = [long]$tl.Position.TotalMilliseconds;",
            "            isPlaying = ($pb.PlaybackStatus -eq [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus]::Playing);",
            "            repeat = $repeat; source = 'SMTC';",
            "            smtc = [pscustomobject]@{ canSeek = [bool]$ctl.IsPlaybackPositionEnabled; canShuffle = [bool]$ctl.IsShuffleEnabled; canRepeat = [bool]$ctl.IsRepeatEnabled; shuffle = [bool]$pb.IsShuffleActive }",
            "        }",
            "    } catch { return $null }",
            "}",
            "",
            "function Write-Wire($Payload) {",
            "    if ($null -eq $Payload) {",
            "        Write-Output ($MARK + 'null')",
            "    } else {",
            "        Write-Output ($MARK + ($Payload | ConvertTo-Json -Compress -Depth 4))",
            "    }",
            "}",
            "",
            "# --- global transport keys (Windows) --------------------------------------",
            "$keySource = @'",
            "using System;",
            "using System.Runtime.InteropServices;",
            "public static class ScMediaKeys {",
            "    [DllImport(\"user32.dll\", SetLastError = true)]",
            "    private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);",
            "    public static void Press(byte vk) {",
            "        keybd_event(vk, 0, 0, UIntPtr.Zero);",
            "        System.Threading.Thread.Sleep(30);",
            "        keybd_event(vk, 0, 2, UIntPtr.Zero);",
            "    }",
            "}",
            "'@",
            "$keysReady = $false",
            "try {",
            "    Add-Type -TypeDefinition $keySource -Language CSharp -ErrorAction Stop | Out-Null",
            "    $keysReady = $true",
            "} catch {",
            "    $keysReady = $false",
            "}",
            "",
            "# --- per-application volume (Windows Core Audio) ---------------------------",
            "# Spotify exposes no repeat/seek/shuffle through the media keys, but its audio session can",
            "# still be attenuated directly. ISimpleAudioVolume caps at unity gain, so 100% is the limit.",
            "$volumeSource = @'",
            "using System;",
            "using System.Runtime.InteropServices;",
            "public static class ScVolume {",
            "    [ComImport, Guid(\"BCDE0395-E52F-467C-8E3D-C4579291692E\")]",
            "    private class MMDeviceEnumerator { }",
            "    internal enum EDataFlow { eRender = 0, eCapture = 1, eAll = 2 }",
            "    internal enum ERole { eConsole = 0, eMultimedia = 1, eCommunications = 2 }",
            "    [ComImport, Guid(\"A95664D2-9614-4F35-A746-DE8DB63617E6\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "    private interface IMMDeviceEnumerator {",
            "        int EnumAudioEndpoints(EDataFlow dataFlow, int stateMask, out IMMDeviceCollection devices);",
            "        int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice endpoint);",
            "        int GetDevice(string id, out IMMDevice device);",
            "        int RegisterEndpointNotificationCallback(IntPtr client);",
            "        int UnregisterEndpointNotificationCallback(IntPtr client);",
            "    }",
            "    [ComImport, Guid(\"0BD7A1BE-7A1A-44DB-8397-CC5392387B5E\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "    private interface IMMDeviceCollection {",
            "        int GetCount(out int count);",
            "        int Item(int index, out IMMDevice device);",
            "    }",
            "    [ComImport, Guid(\"D666063F-1587-4E43-81F1-B948E807363F\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "    private interface IMMDevice {",
            "        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object instance);",
            "        int OpenPropertyStore(int access, out IntPtr properties);",
            "        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);",
            "        int GetState(out int state);",
            "    }",
            "    [ComImport, Guid(\"77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "    private interface IAudioSessionManager2 {",
            "        int GetAudioSessionControl(ref Guid sessionGuid, int streamFlags, out IntPtr sessionControl);",
            "        int GetSimpleAudioVolume(ref Guid sessionGuid, int streamFlags, out ISimpleAudioVolume volume);",
            "        int GetSessionEnumerator(out IAudioSessionEnumerator sessionEnum);",
            "        int RegisterSessionNotification(IntPtr notification);",
            "        int UnregisterSessionNotification(IntPtr notification);",
            "        int RegisterDuckNotification(string sessionId, IntPtr notification);",
            "        int UnregisterDuckNotification(IntPtr notification);",
            "    }",
            "    [ComImport, Guid(\"E2F5BB11-0570-40CA-ACDD-3AA01277DEE8\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "    private interface IAudioSessionEnumerator {",
            "        int GetCount(out int count);",
            "        int GetSession(int index, out IAudioSessionControl session);",
            "    }",
            "    [ComImport, Guid(\"F4B1A599-7266-4319-A8CA-E70ACB11E8CD\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "    private interface IAudioSessionControl {",
            "        int GetState(out int state);",
            "        int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);",
            "        int SetDisplayName(string name, ref Guid eventContext);",
            "        int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);",
            "        int SetIconPath(string path, ref Guid eventContext);",
            "        int GetGroupingParam(out Guid grouping);",
            "        int SetGroupingParam(ref Guid grouping, ref Guid eventContext);",
            "        int RegisterAudioSessionNotification(IntPtr client);",
            "        int UnregisterAudioSessionNotification(IntPtr client);",
            "    }",
            "    [ComImport, Guid(\"BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "    private interface IAudioSessionControl2 {",
            "        int GetState(out int state);",
            "        int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);",
            "        int SetDisplayName(string name, ref Guid eventContext);",
            "        int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);",
            "        int SetIconPath(string path, ref Guid eventContext);",
            "        int GetGroupingParam(out Guid grouping);",
            "        int SetGroupingParam(ref Guid grouping, ref Guid eventContext);",
            "        int RegisterAudioSessionNotification(IntPtr client);",
            "        int UnregisterAudioSessionNotification(IntPtr client);",
            "        int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);",
            "        int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);",
            "        int GetProcessId(out int pid);",
            "        int IsSystemSoundsSession();",
            "        int SetDuckingPreference(bool optOut);",
            "    }",
            "    [ComImport, Guid(\"87CE5498-68D6-44E5-9215-6DA47EF883D8\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "    private interface ISimpleAudioVolume {",
            "        int SetMasterVolume(float level, ref Guid eventContext);",
            "        int GetMasterVolume(out float level);",
            "        int SetMute(bool mute, ref Guid eventContext);",
            "        int GetMute(out bool mute);",
            "    }",
            "    private static ISimpleAudioVolume Find(int pid) {",
            "        IMMDeviceEnumerator enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumerator());",
            "        IMMDevice device;",
            "        if (enumerator.GetDefaultAudioEndpoint(EDataFlow.eRender, ERole.eMultimedia, out device) != 0) { return null; }",
            "        Guid iid = typeof(IAudioSessionManager2).GUID;",
            "        object managerObject;",
            "        if (device.Activate(ref iid, 23, IntPtr.Zero, out managerObject) != 0) { return null; }",
            "        IAudioSessionManager2 manager = (IAudioSessionManager2)managerObject;",
            "        IAudioSessionEnumerator sessions;",
            "        if (manager.GetSessionEnumerator(out sessions) != 0) { return null; }",
            "        int count;",
            "        if (sessions.GetCount(out count) != 0) { return null; }",
            "        for (int i = 0; i < count; i++) {",
            "            IAudioSessionControl control;",
            "            if (sessions.GetSession(i, out control) != 0) { continue; }",
            "            IAudioSessionControl2 control2 = control as IAudioSessionControl2;",
            "            if (control2 == null) { continue; }",
            "            int sessionPid;",
            "            if (control2.GetProcessId(out sessionPid) != 0) { continue; }",
            "            if (sessionPid != pid) { continue; }",
            "            return control as ISimpleAudioVolume;",
            "        }",
            "        return null;",
            "    }",
            "    public static bool SetVolume(int pid, float level) {",
            "        ISimpleAudioVolume volume = Find(pid);",
            "        if (volume == null) { return false; }",
            "        Guid context = Guid.Empty;",
            "        return volume.SetMasterVolume(level, ref context) == 0;",
            "    }",
            "    public static double GetVolume(int pid) {",
            "        ISimpleAudioVolume volume = Find(pid);",
            "        if (volume == null) { return -1.0; }",
            "        float level;",
            "        if (volume.GetMasterVolume(out level) != 0) { return -1.0; }",
            "        return (double)level * 100.0;",
            "    }",
            "}",
            "'@",
            "$volumeReady = $false",
            "try {",
            "    Add-Type -TypeDefinition $volumeSource -Language CSharp -ErrorAction Stop | Out-Null",
            "    $volumeReady = $true",
            "} catch {",
            "    $volumeReady = $false",
            "}",
            "",
            "function Get-SpotifyProcessIds {",
            "    $ids = @()",
            "    foreach ($name in @('Spotify', 'SpotifyAB.SpotifyMusic')) {",
            "        try { $ids += @(Get-Process -Name $name -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id) } catch { }",
            "    }",
            "    return $ids",
            "}",
            "",
            "function Get-WindowsVolume {",
            "    if (-not $volumeReady) { return $null }",
            "    foreach ($id in (Get-SpotifyProcessIds)) {",
            "        try {",
            "            $level = [ScVolume]::GetVolume([int]$id)",
            "            if ($level -ge 0) { return [int][Math]::Round($level) }",
            "        } catch { }",
            "    }",
            "    return $null",
            "}",
            "",
            "function Set-WindowsVolume([int]$percent) {",
            "    if (-not $volumeReady) { return $null }",
            "    $level = [float]($percent / 100.0)",
            "    foreach ($id in (Get-SpotifyProcessIds)) {",
            "        try {",
            "            if ([ScVolume]::SetVolume([int]$id, $level)) { return [int][Math]::Round($level * 100.0) }",
            "        } catch { }",
            "    }",
            "    return $null",
            "}",
            "",
            "function Get-LinuxSinkInput {",
            "    if (-not (Get-Command pactl -ErrorAction SilentlyContinue)) { return $null }",
            "    try {",
            "        $text = ((pactl list sink-inputs 2>$null) -join \"`n\")",
            "        foreach ($block in ($text -split '(?m)^Sink Input #')) {",
            "            if ($block -match 'application\\.name\\s*=\\s*\"([^\"]*)\"') {",
            "                if ($Matches[1] -match 'potify') {",
            "                    $lines = $block -split \"`n\"",
            "                    return [int]$lines[0].Trim()",
            "                }",
            "            }",
            "        }",
            "    } catch { }",
            "    return $null",
            "}",
            "",
            "function Set-PactlVolume([int]$percent) {",
            "    $index = Get-LinuxSinkInput",
            "    if ($null -eq $index) { return $null }",
            "    try {",
            "        & pactl set-sink-input-volume $index \"$percent%\" 2>$null | Out-Null",
            "        if ($LASTEXITCODE -eq 0) { return $percent }",
            "    } catch { }",
            "    return $null",
            "}",
            "",
            "function Set-LinuxVolume([int]$percent) {",
            "    if ($percent -gt 100) {",
            "        # Unity gain and above is only reachable through the sink input volume.",
            "        return Set-PactlVolume $percent",
            "    }",
            "    if (Get-Command playerctl -ErrorAction SilentlyContinue) {",
            "        try {",
            "            & playerctl volume ([string]([double]$percent / 100.0)) 2>$null | Out-Null",
            "            if ($LASTEXITCODE -eq 0) { return $percent }",
            "        } catch { }",
            "    }",
            "    return Set-PactlVolume $percent",
            "}",
            "",
            "function Get-LinuxVolume {",
            "    if (Get-Command playerctl -ErrorAction SilentlyContinue) {",
            "        try {",
            "            $value = (& playerctl volume 2>$null | Select-Object -First 1)",
            "            if (-not [string]::IsNullOrWhiteSpace($value)) { return [int][Math]::Round(([double]$value) * 100.0) }",
            "        } catch { }",
            "    }",
            "    return $null",
            "}",
            "",
            "function Get-Volume {",
            "    $windows = Get-WindowsVolume",
            "    if ($null -ne $windows) { return $windows }",
            "    return Get-LinuxVolume",
            "}",
            "",
            "function Invoke-Volume([int]$percent) {",
            "    $windows = Set-WindowsVolume $percent",
            "    if ($null -ne $windows) { return $windows }",
            "    $linux = Set-LinuxVolume $percent",
            "    if ($null -ne $linux) { return $linux }",
            "    if (Get-Command osascript -ErrorAction SilentlyContinue) {",
            "        try {",
            "            $clamped = [int][Math]::Min(100, [Math]::Max(0, $percent))",
            "            osascript -e \"tell application \\\"Spotify\\\" to set sound volume to $clamped\" 2>$null | Out-Null",
            "            return $clamped",
            "        } catch { }",
            "    }",
            "    return $null",
            "}",
            "",
            "function Invoke-SmtcShuffle([string]$state) {",
            "    $session = Get-SmtcSession",
            "    if ($null -eq $session) { return $false }",
            "    try {",
            "        if (-not $session.GetPlaybackInfo().Controls.IsShuffleEnabled) { return $false }",
            "        return [bool](Await-Op ($session.TryChangeShuffleActiveAsync($state -eq 'on')) ([bool]))",
            "    } catch { return $false }",
            "}",
            "",
            "function Invoke-SmtcRepeat([string]$mode) {",
            "    $session = Get-SmtcSession",
            "    if ($null -eq $session) { return $null }",
            "    try {",
            "        if (-not $session.GetPlaybackInfo().Controls.IsRepeatEnabled) { return $null }",
            "        # The projected enum lives in the Windows.Media namespace, and a bare type literal for it",
            "        # does not resolve in Windows PowerShell, so the value is built from the enum instance the",
            "        # session already returned (None = 0, Track = 1, List = 2).",
            "        $code = 0",
            "        if ($mode -eq 'context') { $code = 2 }",
            "        if ($mode -eq 'track') { $code = 1 }",
            "        $current = $session.GetPlaybackInfo().AutoRepeatMode",
            "        if ($null -eq $current) { return $null }",
            "        $target = [System.Enum]::ToObject($current.GetType(), $code)",
            "        if ($null -eq $target) { return $null }",
            "        if (-not (Await-Op ($session.TryChangeAutoRepeatModeAsync($target)) ([bool]))) { return $null }",
            "        # Answered from the request, not from a fresh read: Windows refreshes the session property a",
            "        # few seconds late, so re-reading would report the previous mode to the UI.",
            "        if ($code -eq 2) { return 'context' }",
            "        if ($code -eq 1) { return 'track' }",
            "        return 'off'",
            "    } catch { return $null }",
            "}",
            "",
            "function Invoke-SmtcSeek([long]$milliseconds) {",
            "    $session = Get-SmtcSession",
            "    if ($null -eq $session) { return $false }",
            "    try {",
            "        if (-not $session.GetPlaybackInfo().Controls.IsPlaybackPositionEnabled) { return $false }",
            "        $tl = $session.GetTimelineProperties()",
            "        $ticks = [long]($milliseconds * 10000)",
            "        $start = [long]$tl.StartTime.Ticks",
            "        $end = [long]$tl.EndTime.Ticks",
            "        if ($end -gt $start) {",
            "            if ($ticks -lt $start) { $ticks = $start }",
            "            if ($ticks -ge $end) { $ticks = $end - 1 }",
            "        }",
            "        return [bool](Await-Op ($session.TryChangePlaybackPositionAsync($ticks)) ([bool]))",
            "    } catch { return $false }",
            "}",
            "",
            "function Invoke-Shuffle([string]$state) {",
            "    if ($state -ne 'on' -and $state -ne 'off') { return 'fail' }",
            "    if (Invoke-SmtcShuffle $state) { return 'ok' }",
            "    if (Get-Command playerctl -ErrorAction SilentlyContinue) {",
            "        try {",
            "            & playerctl shuffle $state 2>$null | Out-Null",
            "            if ($LASTEXITCODE -eq 0) { return 'ok' }",
            "        } catch { }",
            "    }",
            "    if (Get-Command osascript -ErrorAction SilentlyContinue) {",
            "        try {",
            "            $flag = if ($state -eq 'on') { 'true' } else { 'false' }",
            "            osascript -e \"tell application \\\"Spotify\\\" to set shuffling to $flag\" 2>$null | Out-Null",
            "            return 'ok'",
            "        } catch { }",
            "    }",
            "    return 'fail'",
            "}",
            "",
            "function Invoke-Seek([long]$milliseconds) {",
            "    if (Invoke-SmtcSeek $milliseconds) { return 'ok' }",
            "    $seconds = [Math]::Round($milliseconds / 1000.0, 3)",
            "    if (Get-Command playerctl -ErrorAction SilentlyContinue) {",
            "        try {",
            "            & playerctl position ([string]$seconds) 2>$null | Out-Null",
            "            if ($LASTEXITCODE -eq 0) { return 'ok' }",
            "        } catch { }",
            "    }",
            "    if (Get-Command osascript -ErrorAction SilentlyContinue) {",
            "        try {",
            "            osascript -e \"tell application \\\"Spotify\\\" to set player position to $seconds\" 2>$null | Out-Null",
            "            return 'ok'",
            "        } catch { }",
            "    }",
            "    return 'fail'",
            "}",
            "",
            "function Invoke-MediaKey([string]$action) {",
            "    if (-not $keysReady) { return 'fail' }",
            "    switch ($action) {",
            "        'play'     { [ScMediaKeys]::Press(0xB3) }",
            "        'pause'    { [ScMediaKeys]::Press(0xB3) }",
            "        'toggle'   { [ScMediaKeys]::Press(0xB3) }",
            "        'next'     { [ScMediaKeys]::Press(0xB0) }",
            "        'previous' { [ScMediaKeys]::Press(0xB1) }",
            "        'stop'     { [ScMediaKeys]::Press(0xB2) }",
            "        default    { return 'fail' }",
            "    }",
            "    return 'ok'",
            "}",
            "",
            "# --- metadata -------------------------------------------------------------",
            "function Split-TrackTitle([string]$text) {",
            "    if ([string]::IsNullOrWhiteSpace($text)) { return $null }",
            "    $clean = $text.Trim()",
            "    foreach ($suffix in @(' - Microsoft\\u200b Edge', ' - Google Chrome', ' - Mozilla Firefox', ' - Opera', ' - Brave')) {",
            "        if ($clean.EndsWith($suffix)) { $clean = $clean.Substring(0, $clean.Length - $suffix.Length) }",
            "    }",
            "    if ($clean -match '^Spotify') { return $null }",
            "    $candidates = @()",
            "    foreach ($sep in @(' - ', ' \u2013 ', ' \u2014 ', ' | ')) {",
            "        if ($clean.Contains($sep)) {",
            "            $idx = $clean.IndexOf($sep)",
            "            $left = $clean.Substring(0, $idx).Trim()",
            "            $right = $clean.Substring($idx + $sep.Length).Trim()",
            "            if ($left.Length -gt 0 -and $right.Length -gt 0) {",
            "                $candidates += [pscustomobject]@{ artist = $left; title = $right }",
            "            }",
            "        }",
            "    }",
            "    if ($candidates.Count -gt 0) { return $candidates[0] }",
            "    return [pscustomobject]@{ artist = $null; title = $clean }",
            "}",
            "",
            "function Get-WindowTrack {",
            "    $smtc = Get-SmtcTrack",
            "    if ($null -ne $smtc) { return $smtc }",
            "    foreach ($name in @('Spotify', 'SpotifyAB.SpotifyMusic')) {",
            "        $procs = @()",
            "        try { $procs = @(Get-Process -Name $name -ErrorAction SilentlyContinue) } catch { $procs = @() }",
            "        foreach ($p in $procs) {",
            "            $title = $null",
            "            try { $title = $p.MainWindowTitle } catch { $title = $null }",
            "            if (-not [string]::IsNullOrWhiteSpace($title)) {",
            "                $split = Split-TrackTitle $title",
            "                if ($null -ne $split) {",
            "                    return [pscustomobject]@{",
            "                        title = $split.title; artist = $split.artist; album = $null; artUrl = $null;",
            "                        durationMs = 0; progressMs = 0; isPlaying = $true; repeat = 'off';",
            "                        source = 'Spotify window'",
            "                    }",
            "                }",
            "            }",
            "        }",
            "    }",
            "    return $null",
            "}",
            "",
            "function Get-Mpris {",
            "    if (-not (Get-Command dbus-send -ErrorAction SilentlyContinue)) { return $null }",
            "    try {",
            "        $lines = @(dbus-send --print-reply --dest=org.mpris.MediaPlayer2.spotify /org/mpris/MediaPlayer2 org.freedesktop.DBus.Properties.Get string:'org.mpris.MediaPlayer2.Player' string:'Metadata' 2>$null)",
            "        if ($lines.Count -eq 0) { return $null }",
            "        $text = $lines -join \"`n\"",
            "        $artist = $null; $track = $null; $album = $null",
            "        if ($text -match 'xesam:artist\\s*\\n\\s*(string\\s+\"([^\"]*)\"|variant\\s+array\\s*\\[\\s*\\n\\s*string\\s+\"([^\"]*)\")') {",
            "            if ($Matches[2]) { $artist = $Matches[2] } else { $artist = $Matches[3] }",
            "        }",
            "        if ($text -match 'xesam:title\\s*\\n\\s*(variant\\s+)?string\\s+\"([^\"]*)\"') { $track = $Matches[2] }",
            "        if ($text -match 'xesam:album\\s*\\n\\s*(variant\\s+)?string\\s+\"([^\"]*)\"') { $album = $Matches[2] }",
            "        if ([string]::IsNullOrWhiteSpace($track)) { return $null }",
            "        $status = ''",
            "        try { $status = (@(dbus-send --print-reply --dest=org.mpris.MediaPlayer2.spotify /org/mpris/MediaPlayer2 org.freedesktop.DBus.Properties.Get string:'org.mpris.MediaPlayer2.Player' string:'PlaybackStatus' 2>$null) -join '') } catch { $status = '' }",
            "        # mpris:length is microseconds; Position is a separate property the poll reads too.",
            "        $durationMs = [long]0",
            "        if ($text -match 'mpris:length\\s*\\n\\s*(variant\\s+)?(uint64|int64|x|t)?\\s*([0-9]+)') {",
            "            $durationMs = [long]([double]$Matches[3] / 1000.0)",
            "        }",
            "        $positionMs = [long]0",
            "        try {",
            "            $posLines = @(dbus-send --print-reply --dest=org.mpris.MediaPlayer2.spotify /org/mpris/MediaPlayer2 org.freedesktop.DBus.Properties.Get string:'org.mpris.MediaPlayer2.Player' string:'Position' 2>$null)",
            "            $posText = $posLines -join \"`n\"",
            "            if ($posText -match '(int64|uint64|int32)\\s+([0-9]+)') { $positionMs = [long]([double]$Matches[2] / 1000.0) }",
            "        } catch { $positionMs = [long]0 }",
            "        $artUrl = $null",
            "        if ($text -match 'mpris:artUrl\\s*\\n\\s*(variant\\s+)?string\\s+\"([^\"]*)\"') { $artUrl = $Matches[2] }",
            "        $repeat = 'off'",
            "        try {",
            "            $loop = (@(dbus-send --print-reply --dest=org.mpris.MediaPlayer2.spotify /org/mpris/MediaPlayer2 org.freedesktop.DBus.Properties.Get string:'org.mpris.MediaPlayer2.Player' string:'LoopStatus' 2>$null) -join '')",
            "            if ($loop -match 'Playlist') { $repeat = 'context' }",
            "            elseif ($loop -match 'Track') { $repeat = 'track' }",
            "        } catch { $repeat = 'off' }",
            "        return [pscustomobject]@{",
            "            title = $track; artist = $artist; album = $album; artUrl = $artUrl;",
            "            durationMs = $durationMs; progressMs = $positionMs; isPlaying = ($status -match 'Playing'); repeat = $repeat;",
            "            source = 'MPRIS'",
            "        }",
            "    } catch {",
            "        return $null",
            "    }",
            "}",
            "",
            "function Get-MacMedia {",
            "    if (-not (Get-Command osascript -ErrorAction SilentlyContinue)) { return $null }",
            "    try {",
            "        $track = (osascript -e 'tell application \"Spotify\" to get name of current track' 2>$null)",
            "        if ([string]::IsNullOrWhiteSpace($track)) { return $null }",
            "        $artist = (osascript -e 'tell application \"Spotify\" to get artist of current track' 2>$null)",
            "        $album = (osascript -e 'tell application \"Spotify\" to get album of current track' 2>$null)",
            "        $art = (osascript -e 'tell application \"Spotify\" to get artwork url of current track' 2>$null)",
            "        $state = (osascript -e 'tell application \"Spotify\" to get player state' 2>$null)",
            "        # Spotify's AppleScript dictionary exposes both in seconds.",
            "        $durationMs = [long]0",
            "        $positionMs = [long]0",
            "        try {",
            "            $d = (osascript -e 'tell application \"Spotify\" to get duration of current track' 2>$null)",
            "            $p = (osascript -e 'tell application \"Spotify\" to get player position' 2>$null)",
            "            $parsed = [double]0",
            "            if ([double]::TryParse(([string]$d).Trim(), [ref]$parsed)) { $durationMs = [long]($parsed * 1000.0) }",
            "            if ([double]::TryParse(([string]$p).Trim(), [ref]$parsed)) { $positionMs = [long]($parsed * 1000.0) }",
            "        } catch { }",
            "        $repeat = 'off'",
            "        try {",
            "            $rep = (osascript -e 'tell application \"Spotify\" to get repeating' 2>$null)",
            "            if (([string]$rep).Trim() -eq 'true') { $repeat = 'context' }",
            "        } catch { $repeat = 'off' }",
            "        return [pscustomobject]@{",
            "            title = $track; artist = $artist; album = $album; artUrl = $art;",
            "            durationMs = $durationMs; progressMs = $positionMs; isPlaying = ($state -match 'playing'); repeat = $repeat;",
            "            source = 'AppleScript'",
            "        }",
            "    } catch {",
            "        return $null",
            "    }",
            "}",
            "",
            "function Get-Track {",
            "    # SMTC first: on Windows it is the only source that carries the track length, the playback",
            "    # position, the album art and the shuffle/repeat capability flags. The other probes are",
            "    # lossy fallbacks for platforms without a Windows media session.",
            "    $smtc = Get-SmtcTrack",
            "    if ($null -ne $smtc) { return $smtc }",
            "    $mpris = Get-Mpris",
            "    if ($null -ne $mpris) { return $mpris }",
            "    $mac = Get-MacMedia",
            "    if ($null -ne $mac) { return $mac }",
            "    return Get-WindowTrack",
            "}",
            "",
            "function Invoke-Control([string]$action) {",
            "    $result = Invoke-MediaKey $action",
            "    if ($result -eq 'ok') { return 'ok' }",
            "    if (Get-Command playerctl -ErrorAction SilentlyContinue) {",
            "        try {",
            "            $mapped = if ($action -eq 'toggle') { 'play-pause' } else { $action }",
            "            & playerctl $mapped 2>$null | Out-Null",
            "            return 'ok'",
            "        } catch { }",
            "    }",
            "    if (Get-Command osascript -ErrorAction SilentlyContinue) {",
            "        try {",
            "            switch ($action) {",
            "                'play'     { osascript -e 'tell application \"Spotify\" to play' | Out-Null }",
            "                'pause'    { osascript -e 'tell application \"Spotify\" to pause' | Out-Null }",
            "                'toggle'   { osascript -e 'tell application \"Spotify\" to playpause' | Out-Null }",
            "                'next'     { osascript -e 'tell application \"Spotify\" to next track' | Out-Null }",
            "                'previous' { osascript -e 'tell application \"Spotify\" to previous track' | Out-Null }",
            "                default    { return 'fail' }",
            "            }",
            "            return 'ok'",
            "        } catch { }",
            "    }",
            "    return 'fail'",
            "}",
            "",
            "Write-Wire @{ status = 'ready'; keys = $keysReady }",
            "",
            "while ($true) {",
            "    $line = [Console]::In.ReadLine()",
            "    if ($null -eq $line) { break }",
            "    $line = $line.Trim()",
            "    if ($line.Length -eq 0) { continue }",
            "    $parts = $line.Split(' ', 2)",
            "    $verb = $parts[0].ToLowerInvariant()",
            "    $arg = ''",
            "    if ($parts.Length -gt 1) { $arg = $parts[1].Trim().ToLowerInvariant() }",
            "    switch ($verb) {",
            "        'get'      { Write-Wire (Get-Track) }",
            "        'track'    { Write-Wire (Get-Track) }",
            "        'play'     { Write-Output ($MARK + (Invoke-Control 'play')) }",
            "        'pause'    { Write-Output ($MARK + (Invoke-Control 'pause')) }",
            "        'toggle'   { Write-Output ($MARK + (Invoke-Control 'toggle')) }",
            "        'next'     { Write-Output ($MARK + (Invoke-Control 'next')) }",
            "        'previous' { Write-Output ($MARK + (Invoke-Control 'previous')) }",
            "        'repeat'   {",
            "            $mode = 'off'",
            "            $sm = Invoke-SmtcRepeat $arg",
            "            if ($null -ne $sm) { $mode = $sm }",
            "            elseif ($arg -eq 'off' -or $arg -eq 'context' -or $arg -eq 'track') {",
            "                if (Get-Command playerctl -ErrorAction SilentlyContinue) {",
            "                    try {",
            "                        $mapped = if ($arg -eq 'context') { 'playlist' } elseif ($arg -eq 'track') { 'track' } else { 'none' }",
            "                        & playerctl loop $mapped 2>$null | Out-Null",
            "                        if ($LASTEXITCODE -eq 0) { $mode = $arg }",
            "                    } catch { }",
            "                }",
            "            }",
            "            Write-Output ($MARK + ('mode:' + $mode))",
            "        }",
            "        'volume'   {",
            "            if ($arg.Length -eq 0 -or $arg -eq 'get') {",
            "                $current = Get-Volume",
            "                if ($null -eq $current) { Write-Output ($MARK + 'volume:unknown') }",
            "                else { Write-Output ($MARK + ('volume:' + $current)) }",
            "            } else {",
            "                $requested = 0",
            "                if ([int]::TryParse($arg, [ref]$requested)) {",
            "                    $applied = Invoke-Volume $requested",
            "                    if ($null -eq $applied) { Write-Output ($MARK + 'volume:fail') }",
            "                    else { Write-Output ($MARK + ('volume:' + $applied)) }",
            "                } else {",
            "                    Write-Output ($MARK + 'volume:fail')",
            "                }",
            "            }",
            "        }",
            "        'shuffle'  { Write-Output ($MARK + (Invoke-Shuffle $arg)) }",
            "        'seek'     {",
            "            $requested = [long]0",
            "            if ([long]::TryParse($arg, [ref]$requested)) {",
            "                Write-Output ($MARK + (Invoke-Seek $requested))",
            "            } else {",
            "                Write-Output ($MARK + 'fail')",
            "            }",
            "        }",
            "        'quit'     { break }",
            "        default    { Write-Output ($MARK + 'fail') }",
            "    }",
            "    if ($verb -eq 'quit') { break }",
            "}",
            "");

    /** Exposed for the config screen's diagnostics button: run one raw bridge command. */
    @Nullable
    public String debugQuery(String argument) {
        return sendCommand(argument);
    }

    /** Exposed for diagnostics: the platform specific support summary. */
    public static List<String> supportedPlatforms() {
        return List.of("Windows (SMTC session + synthesised media keys)",
                "Linux (MPRIS + playerctl)", "macOS (AppleScript)");
    }
}
