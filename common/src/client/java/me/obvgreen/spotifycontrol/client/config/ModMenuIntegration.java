package me.obvgreen.spotifycontrol.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.obvgreen.spotifycontrol.client.SpotifyControlClient;
import me.obvgreen.spotifycontrol.client.keybinds.SpotifyKeybinds;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsService;
import me.obvgreen.spotifycontrol.client.spotify.SpotifyLocalController;
import me.obvgreen.spotifycontrol.client.spotify.SpotifyService;
import me.obvgreen.spotifycontrol.client.spotify.SpotifyWebApiClient;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Mod Menu entrypoint that hands Cloth Config a screen mirroring every {@link ModConfig} field.
 *
 * <p>Colour entries use Cloth's alpha-aware colour picker: the plain {@code startColorField} silently
 * drops the alpha channel, which is exactly what stopped translucent HUD text and panels from being
 * configurable before.</p>
 *
 * <p>Every HUD element has its own sub-category. Each override field is prefilled with the value that
 * is actually in effect (the element's own setting, or the global fallback), so editing it writes an
 * explicit per-element value while leaving the field alone keeps inheriting the global one.</p>
 *
 * <p>Cloth's builder API has no generic "push button" entry, so the account and maintenance actions
 * are exposed as self-resetting toggles: flipping <em>Login</em> on starts the PKCE browser round trip
 * and the switch snaps back off, the same for <em>Logout</em>, the local-bridge probe and the lyrics
 * cache reload. Every toggle has an explicit no-op default so a stray click can never be persisted.</p>
 */
public final class ModMenuIntegration implements ModMenuApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/ModMenu");

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return this::buildScreen;
    }

    /**
     * Builds the Cloth Config screen for the given parent.
     *
     * <p>Exposed so the in-game console ({@code SpotifyGuiScreen}) can open the settings without
     * going through Mod Menu.</p>
     *
     * @param parent screen to return to, may be {@code null}
     * @return a freshly built configuration screen
     */
    public static Screen screen(@Nullable Screen parent) {
        return new ModMenuIntegration().buildScreen(parent);
    }

    private Screen buildScreen(Screen parent) {
        ModConfig config = SpotifyControlClient.configOrLoad();
        config.normalise();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("spotifycontrol.config.title"))
                .setSavingRunnable(config::save);

        ConfigEntryBuilder entries = builder.entryBuilder();

        buildHudCategory(builder.getOrCreateCategory(
                Component.translatable("spotifycontrol.config.category.hud")), entries, config);
        buildElementsCategory(builder.getOrCreateCategory(
                Component.translatable("spotifycontrol.config.category.elements")), entries, config);
        buildLyricsCategory(builder.getOrCreateCategory(
                Component.translatable("spotifycontrol.config.category.lyrics")), entries, config);
        buildAdsCategory(builder.getOrCreateCategory(
                Component.translatable("spotifycontrol.config.category.ads")), entries, config);
        buildApiCategory(builder.getOrCreateCategory(
                Component.translatable("spotifycontrol.config.category.api")), entries, config);
        buildKeybindCategory(builder.getOrCreateCategory(
                Component.translatable("spotifycontrol.config.category.keybinds")), entries);
        buildDebugCategory(builder.getOrCreateCategory(
                Component.translatable("spotifycontrol.config.category.debug")), entries, config);

        return builder.build();
    }

    // ---------------------------------------------------------------------------------------------
    // Categories
    // ---------------------------------------------------------------------------------------------

    private void buildHudCategory(ConfigCategory category, ConfigEntryBuilder entries, ModConfig config) {
        category.addEntry(toggle(entries, "spotifycontrol.config.hud.enabled", config.hudEnabled, true,
                value -> config.hudEnabled = value));

        category.addEntry(entries.startEnumSelector(Component.translatable("spotifycontrol.config.hud.anchor"),
                        ModConfig.Anchor.class, config.hudAnchor)
                .setDefaultValue(ModConfig.Anchor.TOP_LEFT)
                .setEnumNameProvider(anchor -> optionText(anchor))
                .setTooltip(Component.translatable("spotifycontrol.config.hud.anchor.tooltip"))
                .setSaveConsumer(value -> config.hudAnchor = value)
                .build());

        category.addEntry(intField(entries, "spotifycontrol.config.hud.offset_x", config.hudOffsetX, 6,
                -4000, 4000, value -> config.hudOffsetX = value));
        category.addEntry(intField(entries, "spotifycontrol.config.hud.offset_y", config.hudOffsetY, 6,
                -4000, 4000, value -> config.hudOffsetY = value));

        category.addEntry(entries.startFloatField(Component.translatable("spotifycontrol.config.hud.scale"),
                        config.hudScale)
                .setDefaultValue(1.0F)
                .setMin(0.25F)
                .setMax(4.0F)
                .setTooltip(Component.translatable("spotifycontrol.config.hud.scale.tooltip"))
                .setSaveConsumer(value -> config.hudScale = value)
                .build());

        category.addEntry(entries.startIntSlider(Component.translatable("spotifycontrol.config.hud.opacity"),
                        config.hudBackgroundOpacity, 0, 255)
                .setDefaultValue(110)
                .setTooltip(Component.translatable("spotifycontrol.config.hud.opacity.tooltip"))
                .setSaveConsumer(value -> config.hudBackgroundOpacity = value)
                .build());

        category.addEntry(entries.startIntSlider(Component.translatable("spotifycontrol.config.hud.corner_radius"),
                        config.hudCornerRadius, 0, 12)
                .setDefaultValue(3)
                .setTooltip(Component.translatable("spotifycontrol.config.hud.corner_radius.tooltip"))
                .setSaveConsumer(value -> config.hudCornerRadius = value)
                .build());

        category.addEntry(intField(entries, "spotifycontrol.config.hud.card_width", config.hudCardWidth, 160,
                80, 480, value -> config.hudCardWidth = value));

        category.addEntry(entries.startEnumSelector(Component.translatable("spotifycontrol.config.hud.panel_style"),
                        ModConfig.PanelStyle.class, config.hudPanelStyle)
                .setDefaultValue(ModConfig.PanelStyle.GRADIENT)
                .setEnumNameProvider(style -> optionText(style))
                .setTooltip(Component.translatable("spotifycontrol.config.hud.panel_style.tooltip"))
                .setSaveConsumer(value -> config.hudPanelStyle = value)
                .build());

        category.addEntry(entries.startEnumSelector(Component.translatable("spotifycontrol.config.hud.progress_style"),
                        ModConfig.ProgressStyle.class, config.hudProgressStyle)
                .setDefaultValue(ModConfig.ProgressStyle.SHADED_GRADIENT)
                .setEnumNameProvider(style -> optionText(style))
                .setTooltip(Component.translatable("spotifycontrol.config.hud.progress_style.tooltip"))
                .setSaveConsumer(value -> config.hudProgressStyle = value)
                .build());

        // Alpha-aware colour entries: without startAlphaColorField Cloth hides the A channel entirely.
        category.addEntry(color(entries, "spotifycontrol.config.hud.text_color", config.hudTextColor,
                0xFFFFFFFF, value -> config.hudTextColor = value));
        category.addEntry(color(entries, "spotifycontrol.config.hud.secondary_color", config.hudSecondaryTextColor,
                0xFFB8B8B8, value -> config.hudSecondaryTextColor = value));
        category.addEntry(color(entries, "spotifycontrol.config.hud.tertiary_color", config.hudTertiaryTextColor,
                0xFF8A8A8A, value -> config.hudTertiaryTextColor = value));
        category.addEntry(color(entries, "spotifycontrol.config.hud.accent_color", config.hudAccentColor,
                0xFF1DB954, value -> config.hudAccentColor = value));
        category.addEntry(color(entries, "spotifycontrol.config.hud.background_color", config.hudBackgroundColor,
                0xFF000000, value -> config.hudBackgroundColor = value));
        category.addEntry(color(entries, "spotifycontrol.config.hud.border_color", config.hudBorderColor,
                0x33FFFFFF, value -> config.hudBorderColor = value));

        category.addEntry(toggle(entries, "spotifycontrol.config.hud.text_shadow", config.hudTextShadow, true,
                value -> config.hudTextShadow = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.hud.show_album_art", config.hudShowAlbumArt, true,
                value -> config.hudShowAlbumArt = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.hud.show_progress", config.hudShowProgress, true,
                value -> config.hudShowProgress = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.hud.show_status_icon", config.hudShowStatusIcon,
                true, value -> config.hudShowStatusIcon = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.hud.show_album_name", config.hudShowAlbumName,
                false, value -> config.hudShowAlbumName = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.hud.show_time", config.hudShowTime, true,
                value -> config.hudShowTime = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.hud.show_device", config.hudShowDevice, false,
                value -> config.hudShowDevice = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.hud.show_repeat", config.hudShowRepeat, false,
                value -> config.hudShowRepeat = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.hud.show_play_pulse", config.hudShowPlayPulse,
                true, value -> config.hudShowPlayPulse = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.hud.marquee", config.hudMarquee, true,
                value -> config.hudMarquee = value));

        category.addEntry(entries.startFloatField(Component.translatable("spotifycontrol.config.hud.marquee_speed"),
                        config.hudMarqueeSpeed)
                .setDefaultValue(40.0F)
                .setMin(5.0F)
                .setMax(200.0F)
                .setTooltip(Component.translatable("spotifycontrol.config.hud.marquee_speed.tooltip"))
                .setSaveConsumer(value -> config.hudMarqueeSpeed = value)
                .build());
    }

    /** One sub-category per element, so each HUD piece can be placed and styled on its own. */
    private void buildElementsCategory(ConfigCategory category, ConfigEntryBuilder entries, ModConfig config) {
        category.addEntry(entries.startTextDescription(
                Component.translatable("spotifycontrol.config.elements.description")).build());
        for (HudElementType type : HudElementType.values()) {
            category.addEntry(buildElementSubCategory(entries, config, type));
        }
    }

    private AbstractConfigListEntry buildElementSubCategory(ConfigEntryBuilder entries, ModConfig config,
                                                            HudElementType type) {
        HudElementSettings settings = config.element(type);
        String base = "spotifycontrol.config.element." + type.name().toLowerCase(Locale.ROOT);

        SubCategoryBuilder sub = entries.startSubCategory(
                Component.translatable(type.translationKey()));
        sub.setExpanded(false);

        sub.add(toggle(entries, base + ".enabled", settings.enabled, type.defaultEnabled(),
                value -> settings.enabled = value));

        sub.add(entries.startEnumSelector(Component.translatable(base + ".anchor"), ModConfig.Anchor.class,
                        settings.resolveAnchor(type.defaultAnchor()))
                .setDefaultValue(type.defaultAnchor())
                .setEnumNameProvider(anchor -> optionText(anchor))
                .setTooltip(Component.translatable(base + ".anchor.tooltip"))
                .setSaveConsumer(value -> settings.anchor = value)
                .build());

        sub.add(intField(entries, base + ".offset_x", settings.resolveOffsetX(config.hudOffsetX),
                config.hudOffsetX, -4000, 4000, value -> settings.offsetX = value));
        sub.add(intField(entries, base + ".offset_y", settings.resolveOffsetY(config.hudOffsetY),
                config.hudOffsetY, -4000, 4000, value -> settings.offsetY = value));

        sub.add(entries.startFloatField(Component.translatable(base + ".scale"), settings.resolveScale(config.hudScale))
                .setDefaultValue(config.hudScale)
                .setMin(0.25F)
                .setMax(4.0F)
                .setTooltip(Component.translatable(base + ".scale.tooltip"))
                .setSaveConsumer(value -> settings.scale = value)
                .build());

        int defaultSize = type == HudElementType.CARD ? config.hudCardWidth : type.defaultSize();
        sub.add(entries.startIntSlider(Component.translatable(base + ".size"),
                        settings.size > 0 ? settings.size : defaultSize, 16, 600)
                .setDefaultValue(defaultSize)
                .setTooltip(Component.translatable(base + ".size.tooltip"))
                .setSaveConsumer(value -> settings.size = value)
                .build());

        sub.add(color(entries, base + ".text_color",
                settings.textColor != null ? settings.textColor : config.hudTextColor,
                ColorUtil.parseArgb(config.hudTextColor, 0xFFFFFFFF),
                value -> settings.textColor = value));

        sub.add(entries.startIntSlider(Component.translatable(base + ".background_opacity"),
                        settings.resolveBackgroundOpacity(config.hudBackgroundOpacity), 0, 255)
                .setDefaultValue(config.hudBackgroundOpacity)
                .setTooltip(Component.translatable(base + ".background_opacity.tooltip"))
                .setSaveConsumer(value -> settings.backgroundOpacity = value)
                .build());

        sub.add(toggle(entries, base + ".text_shadow", settings.resolveTextShadow(config.hudTextShadow),
                config.hudTextShadow, value -> settings.textShadow = value));
        sub.add(toggle(entries, base + ".marquee", settings.resolveMarquee(type.defaultMarquee()),
                type.defaultMarquee(), value -> settings.marquee = value));

        if (type == HudElementType.LYRICS) {
            sub.add(entries.startIntSlider(Component.translatable(base + ".lyrics_lines"),
                            settings.resolveLyricsLines(config.lyricsLines), 1, 11)
                    .setDefaultValue(config.lyricsLines)
                    .setTooltip(Component.translatable(base + ".lyrics_lines.tooltip"))
                    .setSaveConsumer(value -> settings.lyricsLines = value)
                    .build());
        }

        sub.add(entries.startBooleanToggle(Component.translatable(base + ".reset"), false)
                .setDefaultValue(false)
                .setTooltip(Component.translatable(base + ".reset.tooltip"))
                .setSaveConsumer(pressed -> {
                    if (pressed) {
                        resetElement(config, type);
                    }
                })
                .build());

        return sub.build();
    }

    /** Drops every per-element override so the element follows the global HUD settings again. */
    private static void resetElement(ModConfig config, HudElementType type) {
        HudElementSettings defaults = HudElementSettings.defaults(type);
        HudElementSettings settings = config.element(type);
        settings.enabled = defaults.enabled;
        settings.anchor = null;
        settings.offsetX = null;
        settings.offsetY = null;
        settings.scale = null;
        settings.size = 0;
        settings.textColor = null;
        settings.backgroundOpacity = null;
        settings.textShadow = null;
        settings.marquee = null;
        settings.lyricsLines = 0;
        LOGGER.info("HUD element {} reset to defaults.", type);
    }

    private void buildLyricsCategory(ConfigCategory category, ConfigEntryBuilder entries, ModConfig config) {
        category.addEntry(toggle(entries, "spotifycontrol.config.lyrics.enabled", config.lyricsEnabled, true,
                value -> config.lyricsEnabled = value));

        category.addEntry(entries.startEnumSelector(Component.translatable("spotifycontrol.config.lyrics.source"),
                        ModConfig.LyricsSource.class, config.lyricsSource)
                .setDefaultValue(ModConfig.LyricsSource.LRCLIB)
                .setEnumNameProvider(source -> optionText(source))
                .setTooltip(Component.translatable("spotifycontrol.config.lyrics.source.tooltip"))
                .setSaveConsumer(value -> config.lyricsSource = value)
                .build());

        category.addEntry(entries.startIntSlider(Component.translatable("spotifycontrol.config.lyrics.offset"),
                        config.lyricsOffsetMs, -30000, 30000)
                .setDefaultValue(0)
                .setTooltip(Component.translatable("spotifycontrol.config.lyrics.offset.tooltip"))
                .setSaveConsumer(value -> config.lyricsOffsetMs = value)
                .build());

        category.addEntry(entries.startIntSlider(Component.translatable("spotifycontrol.config.lyrics.lines"),
                        config.lyricsLines, 1, 11)
                .setDefaultValue(3)
                .setTooltip(Component.translatable("spotifycontrol.config.lyrics.lines.tooltip"))
                .setSaveConsumer(value -> config.lyricsLines = value)
                .build());

        category.addEntry(toggle(entries, "spotifycontrol.config.lyrics.karaoke", config.lyricsKaraoke, true,
                value -> config.lyricsKaraoke = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.lyrics.uppercase", config.lyricsUppercase, false,
                value -> config.lyricsUppercase = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.lyrics.placeholder", config.lyricsShowPlaceholder,
                true, value -> config.lyricsShowPlaceholder = value));

        category.addEntry(color(entries, "spotifycontrol.config.lyrics.text_color", config.lyricsTextColor,
                0xFFB8B8B8, value -> config.lyricsTextColor = value));
        category.addEntry(color(entries, "spotifycontrol.config.lyrics.active_color", config.lyricsActiveColor,
                0xFFFFFFFF, value -> config.lyricsActiveColor = value));

        category.addEntry(entries.startBooleanToggle(
                        Component.translatable("spotifycontrol.config.lyrics.reload"), false)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("spotifycontrol.config.lyrics.reload.tooltip"))
                .setSaveConsumer(this::onLyricsReloadToggle)
                .build());
    }

    private void buildApiCategory(ConfigCategory category, ConfigEntryBuilder entries, ModConfig config) {
        category.addEntry(entries.startTextDescription(Component.literal(statusText())).build());

        category.addEntry(entries.startStrField(Component.translatable("spotifycontrol.config.api.client_id"),
                        config.clientId == null ? "" : config.clientId)
                .setDefaultValue("")
                .setTooltip(Component.translatable("spotifycontrol.config.api.client_id.tooltip"))
                .setSaveConsumer(value -> config.clientId = value.isBlank() ? null : value.trim())
                .build());

        category.addEntry(entries.startIntField(Component.translatable("spotifycontrol.config.api.redirect_port"),
                        config.redirectPort)
                .setDefaultValue(8888)
                .setMin(1024)
                .setMax(65535)
                .setTooltip(Component.translatable("spotifycontrol.config.api.redirect_port.tooltip"))
                .setSaveConsumer(value -> config.redirectPort = value)
                .build());

        category.addEntry(entries.startIntField(Component.translatable("spotifycontrol.config.api.poll_interval"),
                        config.pollIntervalSeconds)
                .setDefaultValue(3)
                .setMin(1)
                .setMax(60)
                .setTooltip(Component.translatable("spotifycontrol.config.api.poll_interval.tooltip"))
                .setSaveConsumer(value -> config.pollIntervalSeconds = value)
                .build());

        category.addEntry(toggle(entries, "spotifycontrol.config.api.local_fallback", config.allowLocalFallback,
                true, value -> config.allowLocalFallback = value));

        category.addEntry(entries.startBooleanToggle(Component.translatable("spotifycontrol.config.api.login"), false)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("spotifycontrol.config.api.login.tooltip"))
                .setSaveConsumer(this::onLoginToggle)
                .build());

        category.addEntry(entries.startBooleanToggle(Component.translatable("spotifycontrol.config.api.logout"), false)
                .setDefaultValue(false)
                .setSaveConsumer(this::onLogoutToggle)
                .build());

        category.addEntry(entries.startBooleanToggle(
                        Component.translatable("spotifycontrol.config.api.open_dashboard"), false)
                .setDefaultValue(false)
                .setSaveConsumer(this::onOpenDashboardToggle)
                .build());
    }

    private void buildAdsCategory(ConfigCategory category, ConfigEntryBuilder entries, ModConfig config) {
        category.addEntry(toggle(entries, "spotifycontrol.config.ads.enabled", config.adSkipEnabled, false,
                value -> config.adSkipEnabled = value));

        category.addEntry(entries.startEnumSelector(Component.translatable("spotifycontrol.config.ads.trigger"),
                        ModConfig.AdTrigger.class, config.adSkipTrigger)
                .setDefaultValue(ModConfig.AdTrigger.UNKNOWN_ARTIST)
                .setEnumNameProvider(trigger -> optionText(trigger))
                .setTooltip(Component.translatable("spotifycontrol.config.ads.trigger.tooltip"))
                .setSaveConsumer(value -> config.adSkipTrigger = value)
                .build());

        category.addEntry(entries.startStrField(Component.translatable("spotifycontrol.config.ads.keywords"),
                        config.adSkipKeywords == null ? ModConfig.DEFAULT_AD_KEYWORDS : config.adSkipKeywords)
                .setDefaultValue(ModConfig.DEFAULT_AD_KEYWORDS)
                .setTooltip(Component.translatable("spotifycontrol.config.ads.keywords.tooltip"))
                .setSaveConsumer(value -> config.adSkipKeywords = value.trim())
                .build());

        category.addEntry(entries.startIntSlider(Component.translatable("spotifycontrol.config.ads.cooldown"),
                        config.adSkipCooldownSeconds, 15, 3600)
                .setDefaultValue(90)
                .setTooltip(Component.translatable("spotifycontrol.config.ads.cooldown.tooltip"))
                .setSaveConsumer(value -> config.adSkipCooldownSeconds = value)
                .build());

        category.addEntry(entries.startIntSlider(Component.translatable("spotifycontrol.config.ads.max_per_hour"),
                        config.adSkipMaxPerHour, 1, 30)
                .setDefaultValue(10)
                .setTooltip(Component.translatable("spotifycontrol.config.ads.max_per_hour.tooltip"))
                .setSaveConsumer(value -> config.adSkipMaxPerHour = value)
                .build());

        category.addEntry(entries.startIntSlider(Component.translatable("spotifycontrol.config.ads.restart_delay"),
                        config.adSkipRestartDelayMs, 500, 15000)
                .setDefaultValue(4000)
                .setTooltip(Component.translatable("spotifycontrol.config.ads.restart_delay.tooltip"))
                .setSaveConsumer(value -> config.adSkipRestartDelayMs = value)
                .build());

        category.addEntry(toggle(entries, "spotifycontrol.config.ads.notify", config.adSkipNotify, true,
                value -> config.adSkipNotify = value));
        category.addEntry(toggle(entries, "spotifycontrol.config.ads.resume", config.adSkipResumePlayback, true,
                value -> config.adSkipResumePlayback = value));

        category.addEntry(entries.startBooleanToggle(
                        Component.translatable("spotifycontrol.config.ads.test_restart"), false)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("spotifycontrol.config.ads.test_restart.tooltip"))
                .setSaveConsumer(this::onTestRestartToggle)
                .build());
    }

    private void buildKeybindCategory(ConfigCategory category, ConfigEntryBuilder entries) {
        category.addEntry(entries.startTextDescription(
                Component.translatable("spotifycontrol.config.keybinds.description")).build());
        SpotifyKeybinds keybinds = SpotifyControlClient.keybinds();
        if (keybinds == null) {
            return;
        }
        category.addEntry(entries.fillKeybindingField(
                Component.translatable("spotifycontrol.config.keybinds.play_pause"),
                keybinds.playPauseKey()).build());
        category.addEntry(entries.fillKeybindingField(
                Component.translatable("spotifycontrol.config.keybinds.next"),
                keybinds.nextKey()).build());
        category.addEntry(entries.fillKeybindingField(
                Component.translatable("spotifycontrol.config.keybinds.previous"),
                keybinds.previousKey()).build());
        category.addEntry(entries.fillKeybindingField(
                Component.translatable("spotifycontrol.config.keybinds.repeat"),
                keybinds.repeatKey()).build());
        category.addEntry(entries.fillKeybindingField(
                Component.translatable("spotifycontrol.config.keybinds.toggle_hud"),
                keybinds.toggleHudKey()).build());
        category.addEntry(entries.fillKeybindingField(
                Component.translatable("spotifycontrol.config.keybinds.toggle_lyrics"),
                keybinds.toggleLyricsKey()).build());
    }

    private void buildDebugCategory(ConfigCategory category, ConfigEntryBuilder entries, ModConfig config) {
        category.addEntry(toggle(entries, "spotifycontrol.config.debug.enabled", config.debugLogging, false,
                value -> config.debugLogging = value));

        category.addEntry(entries.startBooleanToggle(
                        Component.translatable("spotifycontrol.config.debug.test_local"), false)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("spotifycontrol.config.debug.test_local.tooltip"))
                .setSaveConsumer(this::onTestLocalToggle)
                .build());
    }

    // ---------------------------------------------------------------------------------------------
    // Entry helpers
    // ---------------------------------------------------------------------------------------------

    private static AbstractConfigListEntry toggle(ConfigEntryBuilder entries, String key, boolean value,
                                                  boolean defaultValue,
                                                  java.util.function.Consumer<Boolean> save) {
        return entries.startBooleanToggle(Component.translatable(key), value)
                .setDefaultValue(defaultValue)
                .setTooltip(Component.translatable(key + ".tooltip"))
                .setSaveConsumer(save::accept)
                .build();
    }

    private static AbstractConfigListEntry intField(ConfigEntryBuilder entries, String key, int value,
                                                    int defaultValue, int min, int max,
                                                    java.util.function.Consumer<Integer> save) {
        return entries.startIntField(Component.translatable(key), value)
                .setDefaultValue(defaultValue)
                .setMin(min)
                .setMax(max)
                .setTooltip(Component.translatable(key + ".tooltip"))
                .setSaveConsumer(save::accept)
                .build();
    }

    /**
     * Alpha-aware colour entry.
     *
     * <p>{@code startAlphaColorField} is the point of this helper: the non-alpha variant clamps the
     * colour to opaque, so translucent HUD text and panels could never be configured.</p>
     */
    private static AbstractConfigListEntry color(ConfigEntryBuilder entries, String key, String current,
                                                 int fallback,
                                                 java.util.function.Consumer<String> save) {
        int argb = ColorUtil.parseArgb(current, fallback);
        return entries.startAlphaColorField(Component.translatable(key), argb)
                .setDefaultValue(fallback)
                .setTooltip(Component.translatable(key + ".tooltip"))
                .setSaveConsumer(value -> save.accept(ColorUtil.toHex(value)))
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private void onLoginToggle(boolean pressed) {
        if (!pressed) {
            return;
        }
        SpotifyService service = SpotifyControlClient.service();
        if (service == null) {
            return;
        }
        service.login(result -> LOGGER.info("Login finished: {}", describe(result)));
    }

    private void onLogoutToggle(boolean pressed) {
        if (!pressed) {
            return;
        }
        SpotifyService service = SpotifyControlClient.service();
        if (service == null) {
            return;
        }
        service.logout(() -> LOGGER.info("Logged out; backend is now {}", service.activeController().displayName()));
    }

    private void onLyricsReloadToggle(boolean pressed) {
        if (!pressed) {
            return;
        }
        LyricsService lyrics = SpotifyControlClient.lyrics();
        if (lyrics == null) {
            return;
        }
        lyrics.reload();
        LOGGER.info("Lyrics reload requested; {} sheet(s) cached.", lyrics.cachedSheets());
    }

    private void onOpenDashboardToggle(boolean pressed) {
        if (!pressed) {
            return;
        }
        Screen current = Minecraft.getInstance().screen;
        ConfirmLinkScreen.confirmLinkNow(current, "https://developer.spotify.com/dashboard", true);
    }

    private void onTestLocalToggle(boolean pressed) {
        if (!pressed) {
            return;
        }
        SpotifyService service = SpotifyControlClient.service();
        if (service == null) {
            return;
        }
        SpotifyLocalController local = service.local();
        LOGGER.info("Local bridge probe -> {}", local.debugQuery("track"));
    }

    private void onTestRestartToggle(boolean pressed) {
        if (!pressed) {
            return;
        }
        SpotifyService service = SpotifyControlClient.service();
        if (service == null) {
            return;
        }
        service.restartSpotifyNow();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Localised name for any enum that implements {@link TranslatableOption}. */
    private static Component optionText(Enum<?> option) {
        if (option instanceof TranslatableOption translatable) {
            Component translated = Component.translatable(translatable.translationKey());
            if (!translated.getString().equals(translatable.translationKey())) {
                return translated;
            }
            return Component.literal(translatable.displayName());
        }
        return Component.literal(String.valueOf(option));
    }

    private static String statusText() {
        SpotifyService service = SpotifyControlClient.service();
        if (service == null) {
            return "Status: not initialised";
        }
        if (service.isAuthorizing()) {
            return "Status: " + Component.translatable("spotifycontrol.config.api.status.pending").getString();
        }
        boolean authenticated = service.webApi().isAuthenticated();
        String key = authenticated
                ? "spotifycontrol.config.api.status.logged_in"
                : "spotifycontrol.config.api.status.logged_out";
        return "Status: " + Component.translatable(key).getString()
                + " | Backend: " + service.activeController().displayName();
    }

    private static String describe(SpotifyWebApiClient.AuthResult result) {
        return switch (result) {
            case SUCCESS -> "success";
            case NO_CLIENT_ID -> "no Client ID configured";
            case ALREADY_RUNNING -> "authorization already in progress";
            case BROWSER_FAILED -> "could not open the browser";
            case PORT_UNAVAILABLE -> "loopback callback port unavailable";
            case TIMEOUT -> "authorization timed out";
            case DENIED -> "authorization denied";
            case EXCHANGE_FAILED -> "token exchange failed";
        };
    }
}
