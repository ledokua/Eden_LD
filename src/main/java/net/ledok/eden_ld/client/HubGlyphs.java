package net.ledok.eden_ld.client;

/**
 * Icons that sit inside text: characters Eden's font files ({@code assets/minecraft/font/default.json}
 * and {@code uniform.json}) draw as pictures — the LC coin, the hourglass, and the crossed swords beside the names of
 * players with PvP on (the Duels tab's icon, {@code PlayerRendererMixin}). Lang strings use the characters
 * directly ("%s" then the coin), so they work in chat and server messages too. They keep their own colors in
 * any text (FontRenderOutputMixin), like emojis. Plane-15 private use, outside Eden's emoji ranges.
 */
public final class HubGlyphs {

    public static final int LC = 0xFFF00;
    public static final int HOURGLASS = 0xFFF01;
    public static final int PVP = 0xFFF02;
    public static final String LC_TEXT = Character.toString(LC);
    public static final String HOURGLASS_TEXT = Character.toString(HOURGLASS);
    public static final String PVP_TEXT = Character.toString(PVP);

    private HubGlyphs() {
    }

    public static boolean isIcon(int codepoint) {
        return codepoint == LC || codepoint == HOURGLASS || codepoint == PVP;
    }
}
