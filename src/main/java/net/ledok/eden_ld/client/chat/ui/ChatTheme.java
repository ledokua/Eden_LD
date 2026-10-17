package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.client.theme.HubTheme;
import net.ledok.eden_ld.client.theme.HubThemes;
import net.ledok.vectorlib.client.canvas.BoxStyle;
import net.ledok.vectorlib.client.canvas.Colors;
import org.jetbrains.annotations.Nullable;

/**
 * The chat's colors in the player's hub theme ({@code docs/PLAN-chat-v2.md}, "Theme tokens"), and the box styles built
 * from them once per theme. A theme's optional {@code chat} block ({@link HubTheme.Chat}) sets any color; the rest are
 * derived from its palette. Light panels (parchment, stone tablet) get "dark glass" — a plate in the theme's ink
 * color with light text — so server and rank colors (white, yellow, aqua names) stay readable.
 */
public final class ChatTheme {

    /** The chat's colors (ARGB). */
    public record Tokens(int plate, int plateInput, int border, int focusBorder, int ink, int inkDim, int hover,
                         int unread, int accent, boolean glass) {
    }

    private static @Nullable HubTheme builtFor;
    private static @Nullable ChatTheme current;

    public final Tokens tokens;
    /** The open chat's history plate and the input row's plate (docked to the screen's left edge). */
    private final BoxStyle history;
    private final BoxStyle input;
    private final BoxStyle inputFocus;
    /** The panels at the last panel opacity asked for ({@link ChatLook#panelOpacity()}). */
    private float builtOpacity = -1;
    private BoxStyle historyAt;
    private BoxStyle inputAt;
    private BoxStyle inputFocusAt;
    /** The message under the pointer. */
    public final BoxStyle hoverRow;
    /** The hover toolbar's back, and its lit button. */
    public final BoxStyle toolbar;
    public final BoxStyle toolbarLit;
    /** "↓ New", "↓ Mentions". */
    public final BoxStyle pill;
    public final BoxStyle pillHover;
    /** A channel pip: idle, the channel typed into, under the pointer. */
    public final BoxStyle pip;
    public final BoxStyle pipLit;
    public final BoxStyle pipHover;
    /** The emoji button. */
    public final BoxStyle button;
    public final BoxStyle buttonHover;
    /** An unread dot, a mention stripe, the scroll bar's thumb. */
    public final BoxStyle dot;
    public final BoxStyle stripe;
    public final BoxStyle thumb;
    public final BoxStyle thumbNew;
    /** The "messages waiting" line (Chat Delay). */
    public final BoxStyle queue;
    /** The @N badge's fill comes from the notification color (a setting): built when it changes. */
    private int badgeColor;
    private BoxStyle badge;

    private ChatTheme(Tokens tokens) {
        this.tokens = tokens;
        history = BoxStyle.fill(tokens.plate).withBorder(tokens.border, 1).withRadii(0, 4, 4, 0);
        input = BoxStyle.fill(tokens.plateInput).withBorder(tokens.border, 1).withRadii(0, 3, 3, 0);
        inputFocus = input.withBorder(tokens.focusBorder, 1);
        hoverRow = BoxStyle.fill(tokens.hover).withRadius(2);
        toolbar = BoxStyle.fill(Colors.withAlpha(tokens.plateInput, 0xF0)).withBorder(tokens.border, 1).withRadius(2);
        toolbarLit = BoxStyle.fill(Colors.lerp(tokens.plateInput, tokens.focusBorder, 0.35f)).withRadius(2);
        pill = BoxStyle.fill(Colors.withAlpha(tokens.plateInput, 0xE8)).withBorder(tokens.border, 1).withRadius(3);
        pillHover = pill.withFill(Colors.lerp(tokens.plateInput, tokens.focusBorder, 0.3f) | 0xF0000000);
        pip = BoxStyle.fill(Colors.withAlpha(tokens.plate, 0xC0)).withRadius(3);
        pipLit = BoxStyle.fill(Colors.lerp(tokens.plate, tokens.ink, 0.12f) | 0xFF000000).withRadius(3);
        pipHover = BoxStyle.fill(Colors.lerp(tokens.plate, tokens.ink, 0.2f) | 0xFF000000).withRadius(3);
        button = pip;
        buttonHover = pipHover;
        dot = BoxStyle.fill(tokens.unread).pill();
        stripe = BoxStyle.fill(0xFFFFFFFF).pill();
        thumb = BoxStyle.fill(tokens.inkDim).pill();
        thumbNew = BoxStyle.fill(tokens.unread).pill();
        queue = BoxStyle.fill(Colors.withAlpha(tokens.plate, 0xE0)).withBorder(tokens.border, 1).withRadius(2);
    }

    /** The history plate at this panel opacity (fill only; the border stays). */
    public BoxStyle history(float opacity) {
        build(opacity);
        return historyAt;
    }

    /** The input plate at this panel opacity, lit while it has the keyboard. */
    public BoxStyle input(float opacity, boolean focused) {
        build(opacity);
        return focused ? inputFocusAt : inputAt;
    }

    private void build(float opacity) {
        if (opacity == builtOpacity) {
            return;
        }
        builtOpacity = opacity;
        historyAt = history.withFill(Colors.scaleAlpha(Colors.withAlpha(tokens.plate, 224), opacity));
        inputAt = input.withFill(Colors.scaleAlpha(Colors.withAlpha(tokens.plateInput, 0xB0), opacity));
        inputFocusAt = inputAt.withBorder(tokens.focusBorder, 1);
    }

    /** Small one-color pills (mention stripes, tints) by color, built once each. */
    private final java.util.Map<Integer, BoxStyle> fills = new java.util.HashMap<>();

    /** A one-color rounded box (radius 2) — the mention tint, a tag's stripe. */
    public BoxStyle fill(int argb) {
        if (fills.size() > 64) {
            fills.clear();
        }
        return fills.computeIfAbsent(argb, c -> BoxStyle.fill(c).withRadius(2));
    }

    /** The @N badge in the player's notification color, ringed by the input plate so it reads where it crosses it. */
    public BoxStyle badge(int color) {
        if (badge == null || color != badgeColor) {
            badgeColor = color;
            badge = BoxStyle.fill(color | 0xFF000000).pill()
                    .withBorder(tokens.plateInput | 0xFF000000, 1, net.ledok.vectorlib.client.canvas.BorderAlign.OUTSIDE);
        }
        return badge;
    }

    /** The chat look for the hub theme in use (built again when the theme changes or resources reload). */
    public static ChatTheme current() {
        HubTheme theme = HubThemes.get(HubThemes.selected());
        if (theme == null) {
            theme = HubTheme.PARCHMENT;
        }
        if (theme != builtFor || current == null) {
            builtFor = theme;
            current = new ChatTheme(derive(theme.palette(), theme.chat()));
        }
        return current;
    }

    // ---------------------------------------------------------------- derivation

    /** The chat's colors for a palette, with the theme's own chat colors taking precedence. */
    public static Tokens derive(HubTheme.Palette p, HubTheme.Chat chat) {
        boolean glass = switch (chat.glass()) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> light(p.panel());
        };
        int plate;
        int plateInput;
        int border;
        int focusBorder;
        int ink;
        int inkDim;
        int hover;
        int unread;
        if (glass) {
            plate = chat.color("plate", p.ink() | 0xFF000000);
            plateInput = chat.color("plate_input", Colors.lerp(plate, p.panel() | 0xFF000000, 0.08f));
            border = chat.color("border", Colors.withAlpha(p.panel(), 0x40));
            focusBorder = chat.color("focus_border", liftFor(p.accent(), plate, 3.0));
            ink = chat.color("ink", liftFor(p.panel(), plate, 7.0));
            inkDim = chat.color("ink_dim", liftFor(p.inkDim(), plate, 4.5));
            hover = chat.color("hover", Colors.withAlpha(p.panel(), 0x24));
            unread = chat.color("unread", liftFor(p.warn(), plate, 4.5));
        } else {
            plate = chat.color("plate", p.panel() | 0xFF000000);
            plateInput = chat.color("plate_input", p.panel2() | 0xFF000000);
            border = chat.color("border", Colors.withAlpha(p.hairline(), 102));
            focusBorder = chat.color("focus_border", p.accent() | 0xFF000000);
            ink = chat.color("ink", p.ink() | 0xFF000000);
            inkDim = chat.color("ink_dim", p.inkDim() | 0xFF000000);
            // The palette's alternate row is barely different from the panel on dark themes: a light veil instead.
            hover = chat.color("hover", 0x1CFFFFFF);
            unread = chat.color("unread", p.warn() | 0xFF000000);
        }
        int accent = chat.color("accent", focusBorder);
        return new Tokens(plate, plateInput, border, focusBorder, ink, inkDim, hover, unread, accent, glass);
    }

    /** A light panel (gamma-space luma, as the hub's other light/dark checks): parchment and stone tablet. */
    public static boolean light(int color) {
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        return r * 299 + g * 587 + b * 114 > 128_000;
    }

    /** {@code color} mixed toward white until it reads against {@code background} at {@code min}:1 (WCAG contrast). */
    public static int liftFor(int color, int background, double min) {
        int opaque = color | 0xFF000000;
        for (int i = 0; i <= 50; i++) {
            int mixed = Colors.lerp(opaque, 0xFFFFFFFF, i / 50f);
            if (contrast(mixed, background) >= min) {
                return mixed;
            }
        }
        return 0xFFFFFFFF;
    }

    /** The WCAG contrast ratio of two opaque colors. */
    public static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * linear(color >> 16 & 0xFF) + 0.7152 * linear(color >> 8 & 0xFF) + 0.0722 * linear(color & 0xFF);
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
