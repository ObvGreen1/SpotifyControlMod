package me.obvgreen.spotifycontrol.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import me.obvgreen.spotifycontrol.client.SpotifyControlClient;
import me.obvgreen.spotifycontrol.client.config.HudElementType;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
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
 * </pre>
 */
public final class SpotifyCommand {

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
                    .then(ClientCommandManager.literal("status").executes(context -> {
                        status(context);
                        return 1;
                    }));
            dispatcher.register(root);
        });
        LOGGER.info("Registered /{} client command.", ROOT);
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
