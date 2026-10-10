package me.obvgreen.spotifycontrol.client.gui.hud;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import me.obvgreen.spotifycontrol.client.config.HudElementType;

/** Registry of the element renderers, in draw order. */
public final class HudElements {

    private HudElements() {
    }

    /** @return a fresh renderer for every element type, ordered back to front. */
    public static List<HudElementRenderer> createAll() {
        return List.of(
                new CardHudElement(),
                new MiniHudElement(),
                new ProgressHudElement(),
                new ArtHudElement(),
                new LyricsHudElement());
    }

    /** @return the renderers keyed by element, for previews and the config screen. */
    public static Map<HudElementType, HudElementRenderer> byType() {
        Map<HudElementType, HudElementRenderer> map = new EnumMap<>(HudElementType.class);
        for (HudElementRenderer renderer : createAll()) {
            map.put(renderer.type(), renderer);
        }
        return map;
    }
}
