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
 *   <li><b>Windows</b> - a long lived PowerShell bridge process that (a) synthesises the global
 *       {@code VK_MEDIA_*} transport keys through {@code user32.dll}, which every media application
 *       including Spotify honours, and (b) reads the window title of the running player so the HUD
 *       still has something to show before the user authenticates.</li>
 *   <li><b>Linux</b> - MPRIS over D-Bus ({@code dbus-send}) for metadata plus
 *       {@code playerctl} for transport control.</li>
 *   <li><b>macOS</b> - AppleScript against the Spotify application when {@code osascript} exists.</li>
 * </ul>
 *
 * <p>This backend needs no login at all, which makes it the perfect companion to the PKCE flow: it
 * keeps the HUD alive (and the keybinds working) when the user never authenticates or when the access
 * token could not be refreshed.</p>
 *
 * <p>The PowerShell script is deliberately written in PowerShell 5.1 compatible syntax and only uses
 * {@code Add-Type} for a plain {@code DllImport} helper - no WinRT metadata reference is required,
 * which is important because the compiler bundled with Windows PowerShell cannot reference
 * {@code Windows.Media.winmd}.</p>
 */
public final class SpotifyLocalController implements SpotifyController {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Local");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(12);
    /** How metadata is sourced; transport control works in all three modes. */
    private static final int MODE_WINDOWS = 1;
    private static final int MODE_LINUX = 2;
    private static final int MODE_MAC = 3;

    private final ModConfig config;
    private final ExecutorService executor;
    private final ReentrantLock bridgeLock = new ReentrantLock();
    private final int mode;

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
                return RepeatMode.fromApiValue(response.substring("mode:".length()));
            }
            return RepeatMode.OFF;
        });
    }

    @Override
    public boolean canControlRepeat() {
        // Synthesised media keys expose no repeat control on any platform.
        return false;
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
            return TrackInfo.of(title, artist, album, artUrl, durationMs, progressMs, playing, repeat,
                    device);
        } catch (RuntimeException exception) {
            LOGGER.error("Malformed native media payload: {} ({})", json, exception.toString());
            return null;
        }
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
     * <p>Only {@code Add-Type} with a plain {@code DllImport} helper is used, because the C# compiler
     * bundled with Windows PowerShell has no reference to {@code Windows.Media.winmd} and therefore
     * cannot compile WinRT (SMTC) interop.</p>
     */
    private static final String BRIDGE_SCRIPT = String.join("\n",
            "# Spotify Control - native media bridge (auto-generated, safe to delete).",
            "$ErrorActionPreference = 'Continue'",
            "$ProgressPreference = 'SilentlyContinue'",
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
            "$OutputEncoding = [System.Text.Encoding]::UTF8",
            "$MARK = '@@SC@@'",
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
            "        return [pscustomobject]@{",
            "            title = $track; artist = $artist; album = $album; artUrl = $null;",
            "            durationMs = 0; progressMs = 0; isPlaying = ($status -match 'Playing'); repeat = 'off';",
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
            "        return [pscustomobject]@{",
            "            title = $track; artist = $artist; album = $album; artUrl = $art;",
            "            durationMs = 0; progressMs = 0; isPlaying = ($state -match 'playing'); repeat = 'off';",
            "            source = 'AppleScript'",
            "        }",
            "    } catch {",
            "        return $null",
            "    }",
            "}",
            "",
            "function Get-Track {",
            "    $window = Get-WindowTrack",
            "    if ($null -ne $window) { return $window }",
            "    $mpris = Get-Mpris",
            "    if ($null -ne $mpris) { return $mpris }",
            "    return Get-MacMedia",
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
            "            # The synthesised media keys expose no repeat control on any platform.",
            "            Write-Output ($MARK + 'mode:off')",
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
        return List.of("Windows (synthesised media keys + window title)",
                "Linux (MPRIS + playerctl)", "macOS (AppleScript)");
    }
}
