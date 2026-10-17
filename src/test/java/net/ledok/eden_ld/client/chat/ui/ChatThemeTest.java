package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.client.theme.HubTheme;
import net.ledok.vectorlib.client.canvas.BoxStyle;
import net.ledok.vectorlib.client.canvas.widget.WidgetStyle;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void hudStripsAreTheOpaquePlateDockedLeft() {
        for (HubTheme.Palette p : new HubTheme.Palette[]{HubTheme.Palette.PARCHMENT, DARK, OVERGROWN, STONE}) {
            ChatTheme.Tokens t = ChatTheme.derive(p, HubTheme.Chat.NONE);
            BoxStyle row = ChatTheme.of(t).hudRow;
            String name = Integer.toHexString(p.panel());
            // Alpha comes from the HUD (Text Background Opacity × fade): the style itself is opaque.
            assertEquals(0xFF, row.fill() >>> 24, name);
            assertEquals(t.plate() & 0xFFFFFF, row.fill() & 0xFFFFFF, name);
            assertEquals(0f, row.tl(), name);
            assertEquals(2.5f, row.tr(), name);
            assertEquals(2.5f, row.br(), name);
            assertEquals(0f, row.bl(), name);
            assertEquals(0f, row.borderWidth(), name);
            assertTrue(row.shadows().isEmpty(), name);
        }
        HubTheme.Chat translucent = new HubTheme.Chat(Map.of("plate", 0x80101010), HubTheme.Chat.Glass.OFF);
        assertEquals(0xFF101010, ChatTheme.of(ChatTheme.derive(DARK, translucent)).hudRow.fill(),
                "a theme's translucent plate is made opaque");
    }

    @Test
    void theMentionTintIsTheOpenLooksAndCached() {
        ChatTheme theme = ChatTheme.of(ChatTheme.derive(DARK, HubTheme.Chat.NONE));
        BoxStyle tint = theme.hudMention(0x123456);
        assertEquals(0x50123456, tint.fill());
        assertEquals(theme.fill(0x50123456), tint, "the same box as 4a's tint (fill 0x50, radius 2)");
        assertSame(tint, theme.hudMention(0xFF123456), "the color's alpha is ignored; built once");
        BoxStyle other = theme.hudMention(0xABCDEF);
        assertNotSame(tint, other);
        assertEquals(0x50ABCDEF, other.fill());
    }

    @Test
    void fillsAreBuiltOncePerColor() {
        ChatTheme theme = ChatTheme.of(ChatTheme.derive(HubTheme.Palette.PARCHMENT, HubTheme.Chat.NONE));
        BoxStyle stripe = theme.fill(0xFFD0D0D0);
        assertSame(stripe, theme.fill(0xFFD0D0D0));
        assertEquals(0xFFD0D0D0, stripe.fill());
        assertEquals(2f, stripe.tl());
        assertEquals(2f, stripe.br());
        for (int i = 0; i < 100; i++) {
            assertEquals(0xFF000000 | i, theme.fill(0xFF000000 | i).fill(), "the cache clears without losing a color");
        }
    }

    private static BoxStyle box(WidgetStyle.Skin skin) {
        return ((WidgetStyle.Skin.Box) skin).style();
    }

    @Test
    void theCanvasWidgetsAreTheThemesFlatBoxes() {
        for (HubTheme.Palette p : new HubTheme.Palette[]{HubTheme.Palette.PARCHMENT, DARK, OVERGROWN, STONE}) {
            ChatTheme.Tokens t = ChatTheme.derive(p, HubTheme.Chat.NONE);
            ChatTheme theme = ChatTheme.of(t);
            WidgetStyle widgets = theme.widgets;
            String name = Integer.toHexString(p.panel());
            // No square 1-px stroke on a hovered menu row, no doubled text.
            assertEquals(0, widgets.accent(), name);
            assertFalse(widgets.textShadow(), name);
            assertSame(widgets, theme.canvas.widgets(), name);
            assertSame(BoxStyle.NONE, box(widgets.button()), "a row at rest draws nothing: " + name);
            assertEquals(t.ink(), widgets.text(), name);
            BoxStyle popup = box(widgets.popup());
            assertTrue(ChatTheme.contrast(t.ink(), popup.fill() | 0xFF000000) >= 4.5, "menu text on " + name);
            assertTrue(ChatTheme.contrast(t.ink(), box(widgets.buttonHover()).fill() | 0xFF000000) >= 3,
                    "a lit row's text on " + name);
            assertEquals(4f, popup.tl(), name);
            assertEquals(t.focusBorder(), box(widgets.fieldFocused()).borderColor(), name);
            // The picker: its own panel, cells in the focus color, dim text without a shadow.
            EmojiPicker.Look look = theme.pickerLook;
            assertSame(theme.cellHover, look.cellHover(), name);
            assertSame(theme.cellLit, look.cellLit(), name);
            assertEquals(t.inkDim(), look.inkDim(), name);
            assertFalse(look.textShadow(), name);
            assertEquals(t.focusBorder() & 0xFFFFFF, theme.cellHover.fill() & 0xFFFFFF, name);
            assertTrue((theme.cellLit.fill() >>> 24) > (theme.cellHover.fill() >>> 24), "the selected tab is stronger");
        }
    }

    @Test
    void theTabStripsBoxesAreGmsChipAndFolderTab() {
        for (HubTheme.Palette p : new HubTheme.Palette[]{HubTheme.Palette.PARCHMENT, DARK, OVERGROWN, STONE}) {
            ChatTheme.Tokens t = ChatTheme.derive(p, HubTheme.Chat.NONE);
            ChatTheme theme = ChatTheme.of(t);
            String name = Integer.toHexString(p.panel());
            // A chip: the plate at alpha 199, radius 3 (its strength is the draw's alpha).
            assertEquals(199, theme.tab.fill() >>> 24, name);
            assertEquals(t.plate() & 0xFFFFFF, theme.tab.fill() & 0xFFFFFF, name);
            assertEquals(3f, theme.tab.tl(), name);
            assertEquals(3f, theme.tab.br(), name);
            // The lit plate: the input plate at alpha 245, rounded on top only.
            assertEquals(245, theme.tabLit.fill() >>> 24, name);
            assertEquals(t.plateInput() & 0xFFFFFF, theme.tabLit.fill() & 0xFFFFFF, name);
            assertEquals(3f, theme.tabLit.tl(), name);
            assertEquals(3f, theme.tabLit.tr(), name);
            assertEquals(0f, theme.tabLit.br(), name);
            assertEquals(0f, theme.tabLit.bl(), name);
            // The labels read: the active one on the lit plate, the others on the plate.
            assertTrue(ChatTheme.contrast(t.ink(), theme.tabLit.fill() | 0xFF000000) >= 4.5, "active label on " + name);
            assertTrue(ChatTheme.contrast(t.inkDim(), t.plate()) >= 3, "idle label on " + name);
        }
    }

    @Test
    void aMutedTabsInkSitsBetweenTheDimInkAndThePlate() {
        for (HubTheme.Palette p : new HubTheme.Palette[]{HubTheme.Palette.PARCHMENT, DARK, OVERGROWN, STONE}) {
            ChatTheme.Tokens t = ChatTheme.derive(p, HubTheme.Chat.NONE);
            int muted = ChatTheme.of(t).inkMuted;
            String name = Integer.toHexString(p.panel());
            assertEquals(0xFF, muted >>> 24, "opaque: " + name);
            for (int shift = 0; shift <= 16; shift += 8) {
                int dim = t.inkDim() >> shift & 0xFF;
                int plate = t.plate() >> shift & 0xFF;
                int channel = muted >> shift & 0xFF;
                assertTrue(channel >= Math.min(dim, plate) && channel <= Math.max(dim, plate), name);
            }
            double dimContrast = ChatTheme.contrast(t.inkDim(), t.plate());
            double mutedContrast = ChatTheme.contrast(muted, t.plate());
            assertTrue(mutedContrast < dimContrast && mutedContrast > 1.2, "dimmer, still there: " + name);
        }
    }

    @Test
    void liftingStopsAtTheFirstReadableShade() {
        int lifted = ChatTheme.liftFor(0xFF3A4A6A, 0xFF1C1C1F, 4.5);
        assertTrue(ChatTheme.contrast(lifted, 0xFF1C1C1F) >= 4.5);
        assertEquals(0xFFFFFFFF, ChatTheme.liftFor(0xFF000000, 0xFFFFFFFF, 30), "white when nothing reads");
        assertEquals(0xFFE8E6E1, ChatTheme.liftFor(0xFFE8E6E1, 0xFF26282F, 7), "already readable: unchanged");
    }
}
