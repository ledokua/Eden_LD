package net.ledok.eden_ld.api.client;

import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.theme.HubThemes;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.Theme;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/**
 * The hub theme the player picked (parchment, dark, overgrown, stone tablet, a resource pack's), for other mods' screens
 * that want to look like the hub — Duels_LD's stake screens. Client only, render thread; source-stable since 1.3.13.
 * Read it when a screen opens, and rebuild when {@link #id()} changes while it's open.
 */
public final class EdenHubThemeApi {

    private EdenHubThemeApi() {
    }

    /** The theme's VectorLib {@link Theme}: palette tokens, widget skins, text shadow. */
    public static Theme theme() {
        return EdenParchment.THEME;
    }

    /**
     * The hub's shell background: the theme's nine-slice panel with its tint and pattern, for a rect. Leave about 8 px
     * of padding inside it for the border art.
     */
    public static Function<Rect, CanvasNode> panel() {
        return EdenParchment.panel();
    }

    /** The selected theme's id; it changes when the player picks another theme. */
    public static ResourceLocation id() {
        return HubThemes.selected();
    }
}
