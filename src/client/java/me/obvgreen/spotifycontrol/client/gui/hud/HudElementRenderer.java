package me.obvgreen.spotifycontrol.client.gui.hud;

import me.obvgreen.spotifycontrol.client.config.HudElementType;

/**
 * Renders one {@link HudElementType}.
 *
 * <p>An element measures itself first and is then drawn at its own origin: the overlay takes care of
 * anchoring, offsetting and scaling, so implementations only ever draw inside
 * {@code (0, 0) - (width, height)} in local units.</p>
 */
public interface HudElementRenderer {

    /** @return the element type this renderer is responsible for. */
    HudElementType type();

    /** @return the element width in local units for the given frame. */
    int measureWidth(HudRenderContext context, HudLayout layout);

    /** @return the element height in local units for the given frame. */
    int measureHeight(HudRenderContext context, HudLayout layout);

    /**
     * Draws the element.
     *
     * @param context frame context
     * @param layout  resolved settings
     * @param width   measured width, identical to {@link #measureWidth}
     * @param height  measured height, identical to {@link #measureHeight}
     */
    void render(HudRenderContext context, HudLayout layout, int width, int height);

    /** @return {@code true} when the element should be drawn for this frame. */
    default boolean visible(HudRenderContext context, HudLayout layout) {
        return true;
    }
}
