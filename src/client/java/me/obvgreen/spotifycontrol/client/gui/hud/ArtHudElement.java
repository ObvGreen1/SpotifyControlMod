package me.obvgreen.spotifycontrol.client.gui.hud;

import me.obvgreen.spotifycontrol.client.config.HudElementType;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;

/**
 * Album artwork on its own, for players who want the cover in a corner and everything else elsewhere.
 *
 * <p>A placeholder tile is drawn while the texture is still downloading so the element does not jump
 * around when the artwork finally arrives.</p>
 */
public final class ArtHudElement implements HudElementRenderer {

    @Override
    public HudElementType type() {
        return HudElementType.ART;
    }

    @Override
    public int measureWidth(HudRenderContext context, HudLayout layout) {
        return Math.max(16, layout.size());
    }

    @Override
    public int measureHeight(HudRenderContext context, HudLayout layout) {
        return Math.max(16, layout.size());
    }

    @Override
    public boolean visible(HudRenderContext context, HudLayout layout) {
        return context.hasTrack();
    }

    @Override
    public void render(HudRenderContext context, HudLayout layout, int width, int height) {
        int radius = Math.max(0, Math.min(layout.cornerRadius(), Math.min(width, height) / 4));
        int background = layout.backgroundScaled(0.8F);
        if (background != 0) {
            HudPainter.fillRounded(context.context(), 0, 0, width, height, radius, background);
        }
        Identifier texture = context.albumArt();
        if (texture == null) {
            HudPainter.fillRounded(context.context(), 2, 2, width - 2, height - 2, radius,
                    HudRenderContext.alpha(0xFFFFFFFF, 0.12F));
        } else {
            // Standalone RGBA artwork, so the plain textured GUI pipeline is the correct one.
            context.context().drawTexture(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0F, 0.0F, width, height,
                    width, height);
        }
        HudPainter.strokeRounded(context.context(), 0, 0, width, height, radius,
                HudRenderContext.alpha(0xFFFFFFFF, 0.28F));
    }
}
