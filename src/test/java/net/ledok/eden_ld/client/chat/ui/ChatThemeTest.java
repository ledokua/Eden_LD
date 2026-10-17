package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.client.theme.HubTheme;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chat's colors on the four built-in themes (palettes as in their theme.json files). */
class ChatThemeTest {

    private static HubTheme.Palette palette(int panel, int panel2, int hairline, int ink, int inkDim, int accent,
                                            int warn) {
        HubTheme.Palette p = HubTheme.Palette.PARCHMENT;
        return new HubTheme.Palette(panel, panel2, hairline, p.hairlineHi(), p.rowBg(), p.rowBgAlt(), ink, p.inkMid(),
                inkDim, accent, p.good(), warn, p.danger(), p.barTrack(), p.barFill(), p.buttonText(),
                p.buttonTextHover(), p.buttonTextDisabled(), p.panelTint(), p.notification());
    }

    private static final HubTheme.Palette DARK = palette(0xFF26282F, 0xFF2E3038, 0xFF4A4D5A, 0xFFE8E6E1, 0xFF8C8A85,
            0xFFD8A657, 0xFFE0B050);
    private static final HubTheme.Palette OVERGROWN = palette(0xFF26302A, 0xFF2C3830, 0xFF55644F, 0xFFE6EDDA,
            0xFF8A9A82, 0xFF9BD06A, 0xFFE3C063);
    private static final HubTheme.Palette STONE = palette(0xFF8A8A8D, 0xFF7E7E81, 0xFF46464A, 0xFF1C1C1F, 0xFF55555B,
            0xFF3A4A6A, 0xFF6A4A10);

    @Test
    void lightPanelsGetDarkGlassAndDarkOnesKeepTheirPanel() {
        assertTrue(ChatTheme.derive(HubTheme.Palette.PARCHMENT, HubTheme.Chat.NONE).glass());
        assertTrue(ChatTheme.derive(STONE, HubTheme.Chat.NONE).glass(), "stone tablet's grey counts as light");
        assertFalse(ChatTheme.derive(DARK, HubTheme.Chat.NONE).glass());
        assertFalse(ChatTheme.derive(OVERGROWN, HubTheme.Chat.NONE).glass());
        assertEquals(0xFF2B1B0E, ChatTheme.derive(HubTheme.Palette.PARCHMENT, HubTheme.Chat.NONE).plate(),
                "parchment's glass is its ink (leather)");
    }

    @Test
    void everyThemesTextReadsOnItsPlate() {
        for (HubTheme.Palette p : new HubTheme.Palette[]{HubTheme.Palette.PARCHMENT, DARK, OVERGROWN, STONE}) {
            ChatTheme.Tokens t = ChatTheme.derive(p, HubTheme.Chat.NONE);
            assertTrue(ChatTheme.contrast(t.ink(), t.plate()) >= 7, "ink on " + Integer.toHexString(p.panel()));
            assertTrue(ChatTheme.contrast(t.inkDim(), t.plate()) >= 3, "dim ink on " + Integer.toHexString(p.panel()));
            // Chat's own white and yellow lines.
            assertTrue(ChatTheme.contrast(0xFFFFFFFF, t.plate()) >= 4.5);
            assertTrue(ChatTheme.contrast(0xFFFFFF55, t.plate()) >= 4.5);
            if (t.glass()) {
                assertTrue(ChatTheme.contrast(t.focusBorder(), t.plate()) >= 3, "the focus ring shows");
            }
        }
    }

    @Test
    void aThemesChatBlockWins() {
        HubTheme.Chat chat = new HubTheme.Chat(Map.of("hover", 0x30FFFFFF, "plate", 0xFF101010), HubTheme.Chat.Glass.OFF);
        ChatTheme.Tokens t = ChatTheme.derive(HubTheme.Palette.PARCHMENT, chat);
        assertFalse(t.glass());
        assertEquals(0x30FFFFFF, t.hover());
        assertEquals(0xFF101010, t.plate());
        assertEquals(HubTheme.Palette.PARCHMENT.ink(), t.ink(), "the rest still comes from the palette");
    }

    @Test
    void liftingStopsAtTheFirstReadableShade() {
        int lifted = ChatTheme.liftFor(0xFF3A4A6A, 0xFF1C1C1F, 4.5);
        assertTrue(ChatTheme.contrast(lifted, 0xFF1C1C1F) >= 4.5);
        assertEquals(0xFFFFFFFF, ChatTheme.liftFor(0xFF000000, 0xFFFFFFFF, 30), "white when nothing reads");
        assertEquals(0xFFE8E6E1, ChatTheme.liftFor(0xFFE8E6E1, 0xFF26282F, 7), "already readable: unchanged");
    }
}
