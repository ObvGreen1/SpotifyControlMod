package me.obvgreen.spotifycontrol.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import me.obvgreen.spotifycontrol.client.SpotifyControlClient;
import me.obvgreen.spotifycontrol.client.config.HudElementType;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
import me.obvgreen.spotifycontrol.client.gui.SpotifyGuiScreen;
import me.obvgreen.spotifycontrol.client.gui.hud.HudLayout;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsService;
import me.obvgreen.spotifycontrol.client.spotify.RepeatMode;
import me.obvgreen.spotifycontrol.client.spotify.SpotifyService;
import me.obvgreen.spotifycontrol.client.spotify.SpotifyWebApiClient;
import me.obvgreen.spotifycontrol.client.spotify.TrackInfo;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Registers the {@code /spotify} client command.
 *
 * <p>Every subcommand returns immediately: transport actions are dispatched to
 * {@link SpotifyService}, which owns the background executors. Nothing here performs network or
 * process work on the game thread.</p>
 *
 * <pre>
 * /spotify play|pause|toggle|next|skip|prev|repeat [off|all|one]|auth [login|logout]|hud toggle|status
 * /spotify adskip [on|off|toggle|now]
 * </pre>
 */
public final class SpotifyCommand {

    /** Shown when the console cannot be opened because the client service is not up yet. */
    private static final String CONSOLE_UNAVAILABLE =
            "The Spotify console is not available yet; try again in a moment.";

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Command");

    /** Literal used for the root command. */
    public static final String ROOT = "spotify";

    private final SpotifyService service;
    private final ModConfig config;

    public SpotifyCommand(SpotifyService service, ModConfig config) {
        this.service = service;
        this.config = config;
    }

    /** Subscribes the registration callback. Called once during client init. */
    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal(ROOT)
                    .executes(context -> {
                        status(context);
                        return 1;
                    })
                    .then(ClientCommandManager.literal("play").executes(context -> {
                        service.play();
                        feedback(context, "Resuming playback");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("pause").executes(context -> {
                        service.pause();
                        feedback(context, "Pausing playback");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("toggle").executes(context -> {
                        service.togglePlayPause();
                        feedback(context, "Toggled play/pause");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("next").executes(context -> {
                        service.next();
                        feedback(context, "Skipping to the next track");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("skip").executes(context -> {
                        service.next();
                        feedback(context, "Skipping to the next track");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("prev").executes(context -> {
                        service.previous();
                        feedback(context, "Going back to the previous track");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("previous").executes(context -> {
                        service.previous();
                        feedback(context, "Going back to the previous track");
                        return 1;
                    }))
                    .then(buildRepeat())
                    .then(buildAuth())
                    .then(buildMenu())
                    .then(ClientCommandManager.literal("gui").executes(this::openMenu))
                    .then(buildShuffle())
                    .then(buildVolume())
                    .then(buildSeek())
                    .then(ClientCommandManager.literal("hud")
                            .executes(context -> {
                                toggleHud(context);
                                return 1;
                            })
                            .then(ClientCommandManager.literal("toggle").executes(context -> {
                                toggleHud(context);
                                return 1;
                            }))
                            .then(ClientCommandManager.literal("on").executes(context -> {
                                setHud(context, true);
                                return 1;
                            }))
                            .then(ClientCommandManager.literal("off").executes(context -> {
                                setHud(context, false);
                                return 1;
                            }))
                            .then(buildHudElement()))
                    .then(buildLyrics())
                    .then(buildAdSkip())
                    .then(ClientCommandManager.literal("status").executes(context -> {
                        status(context);
                        return 1;
                    }));
            dispatcher.register(root);
        });
        LOGGER.info("Registered /{} client command.", ROOT);
    }

    /**
     * {@code /spotify menu} (aliases {@code /spotify gui} and {@code /spotify menu gui}) opens the
     * in-game control screen.
     *
     * <p>The bare {@code /spotify} root keeps printing the status line, so the console always has an
     * explicit entry point; the screen itself is a thin front end over the same service calls the
     * other subcommands use.</p>
     */
    private LiteralArgumentBuilder<FabricClientCommandSource> buildMenu() {
        return ClientCommandManager.literal("menu")
                .executes(this::openMenu)
                .then(ClientCommandManager.literal("gui").executes(this::openMenu));
    }

    /**
     * Queues the console screen for the next client tick.
     *
     * <p>{@code ChatScreen} closes itself right after the command it sent returns, and that close
     * replaces whatever screen the command set. Setting the console inline therefore flashed and
     * vanished: the chat closed and nothing else appeared. Queueing the open on the client executor
     * runs it once the chat screen is really gone, so the panel stays up. The parent screen is read
     * at that later point, not now, because the chat is still the current screen here.</p>
     */
    private int openMenu(CommandContext<FabricClientCommandSource> context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (SpotifyControlClient.service() == null) {
            error(context, CONSOLE_UNAVAILABLE);
            return 0;
        }
        FabricClientCommandSource source = context.getSource();
        client.execute(() -> {
            if (!SpotifyGuiScreen.open(client.currentScreen)) {
                source.sendError(Text.literal(CONSOLE_UNAVAILABLE).formatted(Formatting.RED));
            }
        });
        return 1;
    }

    /** {@code /spotify shuffle [on|off|toggle]} - reports the state when called bare. */
    private LiteralArgumentBuilder<FabricClientCommandSource> buildShuffle() {
        return ClientCommandManager.literal("shuffle")
                .executes(context -> {
                    reportShuffle(context);
                    return 1;
                })
                .then(ClientCommandManager.literal("on").executes(context -> {
                    setShuffle(context, true);
                    return 1;
                }))
                .then(ClientCommandManager.literal("off").executes(context -> {
                    setShuffle(context, false);
                    return 1;
                }))
                .then(ClientCommandManager.literal("toggle").executes(context -> {
                    setShuffle(context, !service.shuffleEnabled());
                    return 1;
                }));
    }

    private void reportShuffle(CommandContext<FabricClientCommandSource> context) {
        if (!service.canControlShuffle()) {
            error(context, "Shuffle control is not available on the '"
                    + service.activeController().id() + "' backend.");
            return;
        }
        feedback(context, "Shuffle is " + (service.shuffleEnabled() ? "on" : "off") + ".");
    }

    private void setShuffle(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        service.setShuffle(enabled);
        if (service.actionError() == null) {
            feedback(context, "Shuffle set to " + (enabled ? "on" : "off") + ".");
        } else {
            error(context, service.actionError());
        }
    }

    /** {@code /spotify volume <0-200>} and {@code /spotify volume} to report the current level. */
    private LiteralArgumentBuilder<FabricClientCommandSource> buildVolume() {
        return ClientCommandManager.literal("volume")
                .executes(context -> {
                    if (!service.canControlVolume()) {
                        error(context, "Volume control is not available on the '"
                                + service.activeController().id() + "' backend.");
                        return 1;
                    }
                    int current = service.volumePercent();
                    feedback(context, current < 0
                            ? "Volume is unknown (max " + service.maxVolumePercent() + "%)."
                            : "Volume is " + current + "% (max " + service.maxVolumePercent() + "%).");
                    return 1;
                })
                .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 200))
                        .executes(context -> {
                            int percent = IntegerArgumentType.getInteger(context, "percent");
                            service.setVolume(percent);
                            if (service.actionError() == null) {
                                int applied = service.volumePercent();
                                feedback(context, applied < 0
                                        ? "Volume set to " + percent + "%."
                                        : "Volume is now " + applied
                                        + "% (max " + service.maxVolumePercent() + "%).");
                            } else {
                                error(context, service.actionError());
                            }
                            return 1;
                        }));
    }

    /** {@code /spotify seek <seconds>} jumps to an absolute position in the current track. */
    private LiteralArgumentBuilder<FabricClientCommandSource> buildSeek() {
        return ClientCommandManager.literal("seek")
                .executes(context -> {
                    error(context, "Usage: /spotify seek <seconds>");
                    return 1;
                })
                .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(0, 86400))
                        .executes(context -> {
                            int seconds = IntegerArgumentType.getInteger(context, "seconds");
                            service.seek(seconds * 1000L);
                            if (service.actionError() == null) {
                                feedback(context, "Seeking to " + seconds + "s.");
                            } else {
                                error(context, service.actionError());
                            }
                            return 1;
                        }));
    }

    /**
     * {@code /spotify adskip} reports the current state, {@code on}/{@code off}/{@code toggle} flip
     * the feature and {@code now} restarts Spotify immediately.
     */
    private LiteralArgumentBuilder<FabricClientCommandSource> buildAdSkip() {
        return ClientCommandManager.literal("adskip")
                .executes(context -> {
                    feedback(context, "Ad skip: " + service.adSkipStatus());
                    return 1;
                })
                .then(ClientCommandManager.literal("on").executes(context -> {
                    config.adSkipEnabled = true;
                    SpotifyControlClient.persistConfig();
                    feedback(context, "Ad skip enabled - unknown-artist tracks will restart Spotify");
                    return 1;
                }))
                .then(ClientCommandManager.literal("off").executes(context -> {
                    config.adSkipEnabled = false;
                    SpotifyControlClient.persistConfig();
                    feedback(context, "Ad skip disabled");
                    return 1;
                }))
                .then(ClientCommandManager.literal("toggle").executes(context -> {
                    config.adSkipEnabled = !config.adSkipEnabled;
                    SpotifyControlClient.persistConfig();
                    feedback(context, "Ad skip " + (config.adSkipEnabled ? "enabled" : "disabled"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("now").executes(context -> {
                    if (service.restartSpotifyNow()) {
                        feedback(context, "Restarting Spotify now");
                    } else {
                        feedback(context, "Restart already in progress");
                    }
                    return 1;
                }));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> buildRepeat() {
        LiteralArgumentBuilder<FabricClientCommandSource> repeat = ClientCommandManager.literal("repeat")
                .executes(context -> {
                    service.cycleRepeat();
                    feedback(context, "Cycling the repeat mode");
                    return 1;
                });
        for (String alias : new String[]{"off", "all", "one", "context", "track"}) {
            repeat = repeat.then(ClientCommandManager.literal(alias).executes(context -> {
                applyRepeat(context, alias);
                return 1;
            }));
        }
        repeat = repeat.then(ClientCommandManager.argument("mode", StringArgumentType.word())
                .suggests((context, builder) -> {
                    builder.suggest("off");
                    builder.suggest("all");
                    builder.suggest("one");
                    return builder.buildFuture();
                })
                .executes(context -> {
                    applyRepeat(context, StringArgumentType.getString(context, "mode"));
                    return 1;
                }));
        return repeat;
    }

    /**
     * {@code /spotify hud element [name] [on|off|toggle]} and {@code /spotify hud element} to list.
     */
    private LiteralArgumentBuilder<FabricClientCommandSource> buildHudElement() {
        LiteralArgumentBuilder<FabricClientCommandSource> element = ClientCommandManager.literal("element")
                .executes(context -> {
                    listElements(context);
                    return 1;
                });
        RequiredArgumentBuilder<FabricClientCommandSource, String> byName =
                ClientCommandManager.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (HudElementType type : HudElementType.values()) {
                                builder.suggest(type.name().toLowerCase(Locale.ROOT));
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            flipElement(context, StringArgumentType.getString(context, "name"));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("on").executes(context -> {
                            setElement(context, StringArgumentType.getString(context, "name"),
                                    ElementAction.ON);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            setElement(context, StringArgumentType.getString(context, "name"),
                                    ElementAction.OFF);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("toggle").executes(context -> {
                            setElement(context, StringArgumentType.getString(context, "name"),
                                    ElementAction.TOGGLE);
                            return 1;
                        }));
        return element.then(byName);
    }

    /** {@code /spotify lyrics [on|off|toggle|reload|offset [ms]]}. */
    private LiteralArgumentBuilder<FabricClientCommandSource> buildLyrics() {
        return ClientCommandManager.literal("lyrics")
                .executes(context -> {
                    toggleLyrics(context);
                    return 1;
                })
                .then(ClientCommandManager.literal("toggle").executes(context -> {
                    toggleLyrics(context);
                    return 1;
                }))
                .then(ClientCommandManager.literal("on").executes(context -> {
                    setLyrics(context, true);
                    return 1;
                }))
                .then(ClientCommandManager.literal("off").executes(context -> {
                    setLyrics(context, false);
                    return 1;
                }))
                .then(ClientCommandManager.literal("reload").executes(context -> {
                    reloadLyrics(context);
                    return 1;
                }))
                .then(ClientCommandManager.literal("offset")
                        .executes(context -> {
                            feedback(context, "Lyrics offset is " + config.lyricsOffsetMs + " ms");
                            return 1;
                        })
                        .then(ClientCommandManager.argument("millis",
                                        IntegerArgumentType.integer(-30000, 30000))
                                .executes(context -> {
                                    setLyricsOffset(context,
                                            IntegerArgumentType.getInteger(context, "millis"));
                                    return 1;
                                })));
    }

    /** {@code /spotify auth login} and {@code /spotify auth logout}. */
    private LiteralArgumentBuilder<FabricClientCommandSource> buildAuth() {        return ClientCommandManager.literal("auth")
                .executes(this::login)
                .then(ClientCommandManager.literal("login").executes(this::login))
                .then(ClientCommandManager.literal("logout").executes(this::logout));
    }

    private int login(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Text.literal(
                        "Opening the Spotify authorization page in your browser...")
                .formatted(Formatting.AQUA));
        service.login(result -> {
            String message = "Spotify login: " + describe(result);
            Formatting colour = result == SpotifyWebApiClient.AuthResult.SUCCESS
                    ? Formatting.GREEN
                    : Formatting.RED;
            MinecraftClient.getInstance().execute(() -> context.getSource()
                    .sendFeedback(Text.literal(message).formatted(colour)));
        });
        return 1;
    }

    private int logout(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("Clearing stored Spotify credentials...")
                .formatted(Formatting.AQUA));
        service.logout(() -> MinecraftClient.getInstance().execute(() -> context.getSource()
                .sendFeedback(Text.literal("Spotify credentials cleared.").formatted(Formatting.GREEN))));
        return 1;
    }

    /** Maps an authorization outcome onto a short, user-facing phrase. */
    private static String describe(SpotifyWebApiClient.AuthResult result) {
        return switch (result) {
            case SUCCESS -> "logged in.";
            case NO_CLIENT_ID -> "no Client ID configured - add one in the Mod Menu config screen.";
            case ALREADY_RUNNING -> "authorization is already in progress.";
            case BROWSER_FAILED -> "could not open a browser; the URL was printed to the game log.";
            case PORT_UNAVAILABLE -> "the loopback callback port is already in use.";
            case TIMEOUT -> "timed out waiting for the browser callback.";
            case DENIED -> "the request was denied.";
            case EXCHANGE_FAILED -> "the token exchange failed.";
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------------------------------------

    private void applyRepeat(CommandContext<FabricClientCommandSource> context, String raw) {
        RepeatMode mode = parseRepeat(raw);
        if (mode == null) {
            error(context, "Unknown repeat mode '" + raw + "'. Use off, all or one.");
            return;
        }
        service.setRepeatMode(mode);
        feedback(context, "Repeat mode set to " + mode.displayName());
    }

    private void toggleHud(CommandContext<FabricClientCommandSource> context) {
        setHud(context, !config.hudEnabled);
    }

    private void setHud(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        config.hudEnabled = enabled;
        SpotifyControlClient.persistConfig();
        feedback(context, "HUD " + (enabled ? "enabled" : "disabled"));
    }

    private void status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        TrackInfo track = service.currentTrack();
        source.sendFeedback(Text.literal("Spotify Control").formatted(Formatting.AQUA)
                .append(Text.literal(" - " + service.statusLine()).formatted(Formatting.GRAY)));
        if (track.offline()) {
            source.sendFeedback(Text.literal("Nothing is playing (Spotify unreachable).")
                    .formatted(Formatting.RED));
        } else {
            String state = track.isPlaying() ? "Playing" : "Paused";
            source.sendFeedback(Text.literal(state + ": " + track.title() + " - " + track.artist())
                    .formatted(track.isPlaying() ? Formatting.GREEN : Formatting.YELLOW));
            if (track.hasDuration()) {
                source.sendFeedback(Text.literal("Position: " + track.formattedProgress() + " / "
                        + track.formattedDuration() + " - repeat " + track.repeatMode().displayName())
                        .formatted(Formatting.GRAY));
            }
        }
        source.sendFeedback(Text.literal("HUD is " + (config.hudEnabled ? "enabled" : "disabled")
                + "; lyrics " + (config.lyricsEnabled ? "on" : "off")
                + "; transport: " + service.describeActions()).formatted(Formatting.DARK_GRAY));
    }

    // ---------------------------------------------------------------------------------------------
    // Lyrics and per-element HUD commands
    // ---------------------------------------------------------------------------------------------

    private void toggleLyrics(CommandContext<FabricClientCommandSource> context) {
        config.toggleLyrics();
        SpotifyControlClient.persistConfig();
        feedback(context, "HUD lyrics " + (config.lyricsEnabled ? "enabled" : "disabled"));
    }

    private void setLyrics(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        config.lyricsEnabled = enabled;
        SpotifyControlClient.persistConfig();
        feedback(context, "HUD lyrics " + (enabled ? "enabled" : "disabled"));
    }

    private void setLyricsOffset(CommandContext<FabricClientCommandSource> context, int millis) {
        config.lyricsOffsetMs = millis;
        config.normalise();
        SpotifyControlClient.persistConfig();
        feedback(context, "Lyrics offset set to " + config.lyricsOffsetMs + " ms");
    }

    private void reloadLyrics(CommandContext<FabricClientCommandSource> context) {
        LyricsService lyrics = SpotifyControlClient.lyrics();
        if (lyrics == null) {
            error(context, "Lyrics service is not available");
            return;
        }
        lyrics.reload();
        feedback(context, "Lyrics cache cleared; re-fetching (" + lyrics.cachedSheets() + " sheet(s) held)");
    }

    /** Prints every element with its enable state, anchor and effective offsets. */
    private void listElements(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Text.literal("HUD elements").formatted(Formatting.AQUA));
        for (HudElementType type : HudElementType.values()) {
            HudLayout layout = HudLayout.resolve(config, type);
            source.sendFeedback(Text.literal(" - " + type.displayName()
                            + ": " + (config.isElementVisible(type) ? "shown" : "hidden")
                            + ", " + layout.anchor().displayName()
                            + " @ " + layout.offsetX() + "," + layout.offsetY()
                            + ", scale " + String.format(Locale.ROOT, "%.2f", layout.scale()))
                    .formatted(config.isElementVisible(type) ? Formatting.GRAY : Formatting.DARK_GRAY));
        }
        source.sendFeedback(Text.literal("Usage: /spotify hud element <card|mini|progress|art|lyrics> [on|off|toggle]")
                .formatted(Formatting.DARK_GRAY));
    }

    private void flipElement(CommandContext<FabricClientCommandSource> context, String rawName) {
        setElement(context, rawName, ElementAction.TOGGLE);
    }

    private void setElement(CommandContext<FabricClientCommandSource> context, String rawName,
                            ElementAction action) {
        HudElementType type = HudElementType.fromName(rawName, null);
        if (type == null) {
            error(context, "Unknown HUD element '" + rawName + "'");
            return;
        }
        boolean enabled = switch (action) {
            case ON -> true;
            case OFF -> false;
            case TOGGLE -> !config.isElementVisible(type);
        };
        config.element(type).enabled = enabled;
        SpotifyControlClient.persistConfig();
        feedback(context, type.displayName() + " is now " + (enabled ? "shown" : "hidden"));
    }

    /** What a {@code /spotify hud element} invocation should do with the stored flag. */
    private enum ElementAction {
        ON,
        OFF,
        TOGGLE
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static void feedback(CommandContext<FabricClientCommandSource> context, String message) {
        context.getSource().sendFeedback(Text.literal(message).formatted(Formatting.GREEN));
    }

    private static void error(CommandContext<FabricClientCommandSource> context, String message) {
        context.getSource().sendError(Text.literal(message).formatted(Formatting.RED));
    }

    /** Accepts the API spellings as well as the friendlier {@code all}/{@code one} aliases. */
    private static RepeatMode parseRepeat(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "off", "none" -> {
                return RepeatMode.OFF;
            }
            case "all", "context", "playlist" -> {
                return RepeatMode.CONTEXT;
            }
            case "one", "single", "track" -> {
                return RepeatMode.TRACK;
            }
            default -> {
                return null;
            }
        }
    }
}
