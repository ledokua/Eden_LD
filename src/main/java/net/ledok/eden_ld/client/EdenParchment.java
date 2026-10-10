package net.ledok.eden_ld.client;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.client.theme.HubTheme;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.NineSlicePanel;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.Theme;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.WidgetStyle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/**
 * The hub's look: palette, widget skins, and small builders. Starts as the family
 * parchment look (NPCs_LD dialogs, Economy_LD shop, Arenas_LD lobby); {@link #apply}
 * swaps in a {@link HubTheme} (resource-pack JSON), so every tab reads these fields when
 * it builds. Fields are mutable for that reason — assign only through {@link #apply}.
 */
public final class EdenParchment {

    private EdenParchment() {
    }

    // Palette (parchment defaults; a theme overrides any of them).
    public static int PANEL       = 0xFFE5D8B9;
    public static int PANEL_2     = 0xFFDACAA5;
    public static int HAIRLINE    = 0xFF5A4632;
    public static int HAIRLINE_HI = 0xFF3B2612;
    public static int ROW_BG      = 0xFFD8C69E;
    public static int ROW_BG_ALT  = 0xFFD2BF94;
    public static int INK         = 0xFF2B1B0E;
    public static int INK_MID     = 0xFF4A2E16;
    public static int INK_DIM     = 0xFF8A7A60;
    public static int ACCENT      = 0xFF7A4A1E;
    public static int GOOD        = 0xFF3E6B2F;
    public static int WARN        = 0xFF9A6A10;
    public static int DANGER      = 0xFF8B2F23;
    /** Tab badges and glows when the player keeps the theme's color. */
    public static int NOTIFY      = 0xFFD8392B;
    /** Progress bar: framed track, brighter fill so it reads against the row backgrounds. */
    public static int BAR_TRACK   = 0xFFBBA47A;
    public static int BAR_FILL    = 0xFF6E9A4A;
    /** Button label colors and the panel texture tint. */
    public static int BUTTON_TEXT = 0xFF3B2612;
    public static int BUTTON_TEXT_HOVER = 0xFF000000;
    public static int BUTTON_TEXT_DISABLED = 0xFF7A7060;
    public static int PANEL_TINT  = 0xFFE4E4E4;
    /** Whether text draws with a shadow — right on dark themes, doubled-looking on light ones. */
    public static boolean TEXT_SHADOW = false;

    /** Folder holding panel.png, button_normal.png, button_hover.png, button_disabled.png. */
    private static String textureFolder = "textures/gui/themes/parchment/";
    private static String textureNamespace = EdenLD.MOD_ID;

    /** The theme's pattern over the panel, and its row texture (null: plain fills). */
    @org.jetbrains.annotations.Nullable
    private static HubTheme.Background background;
    @org.jetbrains.annotations.Nullable
    private static HubTheme.Rows rows;
    /** Panel and row edges repeat instead of stretching (detailed edge art, e.g. moss). */
    private static boolean tileEdges;

    /** The theme tiles its border art (thick, detailed edges): give dialogs a little more room. */
    public static boolean tileEdges() {
        return tileEdges;
    }

    public static WidgetStyle WIDGETS;
    /** The active hub bookmark: pinned to the hover skin so it reads as "pressed in". */
    public static WidgetStyle TAB_ACTIVE;
    public static Theme THEME;

    static {
        rebuildStyles();
    }

    /** Switches the whole hub look. Open screens rebuild to pick it up. */
    public static void apply(HubTheme theme) {
        HubTheme.Palette p = theme.palette();
        PANEL = p.panel();
        PANEL_2 = p.panel2();
        HAIRLINE = p.hairline();
        HAIRLINE_HI = p.hairlineHi();
        ROW_BG = p.rowBg();
        ROW_BG_ALT = p.rowBgAlt();
        INK = p.ink();
        INK_MID = p.inkMid();
        INK_DIM = p.inkDim();
        ACCENT = p.accent();
        GOOD = p.good();
        WARN = p.warn();
        DANGER = p.danger();
        NOTIFY = p.notification();
        BAR_TRACK = p.barTrack();
        BAR_FILL = p.barFill();
        BUTTON_TEXT = p.buttonText();
        BUTTON_TEXT_HOVER = p.buttonTextHover();
        BUTTON_TEXT_DISABLED = p.buttonTextDisabled();
        PANEL_TINT = p.panelTint();
        TEXT_SHADOW = theme.textShadow();
        textureNamespace = theme.textures().getNamespace();
        String folder = theme.textures().getPath();
        textureFolder = folder.endsWith("/") ? folder : folder + "/";
        background = theme.background();
        rows = theme.rows();
        tileEdges = theme.tileEdges();
        rebuildStyles();
    }

    private static void rebuildStyles() {
        WIDGETS = widgetStyle(button("button_normal"), button("button_hover"));
        // The open sub-tab/filter: the theme's accent-tinted button_active, else its hover look.
        String active = net.minecraft.client.Minecraft.getInstance().getResourceManager()
                .getResource(tex("button_active")).isPresent() ? "button_active" : "button_hover";
        TAB_ACTIVE = widgetStyle(button(active), button(active));
        THEME = new Theme(
                PANEL, PANEL_2, HAIRLINE,
                INK, INK_MID, INK_DIM,
                ACCENT, GOOD, WARN, DANGER,
                ROW_BG, ROW_BG_ALT, 0x88000000,
                WIDGETS);
    }

    private static WidgetStyle widgetStyle(WidgetStyle.Skin normal, WidgetStyle.Skin hover) {
        return new WidgetStyle(
                normal, hover, button("button_hover"), button("button_disabled"),
                new WidgetStyle.Skin.Flat(0x2E000000 | (INK & 0xFFFFFF), HAIRLINE, 1, 2),
                new WidgetStyle.Skin.Flat(0x2E000000 | (INK & 0xFFFFFF), ACCENT, 1, 2),
                new WidgetStyle.Skin.NineSlice(tex("panel"), 48, 48, 8),
                BUTTON_TEXT, BUTTON_TEXT_HOVER, BUTTON_TEXT_DISABLED, INK_DIM, ACCENT,
                0x55000000 | (ACCENT & 0xFFFFFF),
                18, 16, 4, TEXT_SHADOW);
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(textureNamespace, textureFolder + name + ".png");
    }

    private static WidgetStyle.Skin.NineSlice button(String name) {
        return new WidgetStyle.Skin.NineSlice(tex(name), 32, 16, 4);
    }

    /** Shell background: the theme's tiled nine-slice panel (give the shell ~8px padding for the border art). */
    public static Function<Rect, CanvasNode> panel() {
        ResourceLocation texture = tex("panel");
        int tint = PANEL_TINT;
        HubTheme.Background pattern = background;
        NineSlicePanel.Mode edges = tileEdges ? NineSlicePanel.Mode.TILE : NineSlicePanel.Mode.STRETCH;
        return r -> {
            CanvasNode frame = NineSlicePanel.of(texture, 48, 48)
                    .insets(8).centerMode(NineSlicePanel.Mode.TILE).edgeMode(edges)
                    .tint(tint)
                    .bounds(0, 0, r.width(), r.height());
            if (pattern == null) {
                return frame;
            }
            net.ledok.vectorlib.client.canvas.CanvasGroup group = new net.ledok.vectorlib.client.canvas.CanvasGroup();
            group.add(frame);
            // Inside the panel's 8-unit border art.
            group.add(pattern(pattern, 6, 6, r.width() - 12, r.height() - 12));
            return group;
        };
    }

    /** The theme's background picture over an area: repeated (cut at the far edges) or stretched once. */
    private static CanvasNode pattern(HubTheme.Background pattern, float x, float y, float w, float h) {
        net.ledok.vectorlib.client.canvas.CanvasGroup group = new net.ledok.vectorlib.client.canvas.CanvasGroup();
        group.pickable(false);
        HubTheme.Image image = pattern.image();
        int tint = (Math.round(pattern.opacity() * 255) << 24) | 0xFFFFFF;
        if (w <= 0 || h <= 0) {
            return group;
        }
        if (!pattern.tile()) {
            group.add(net.ledok.vectorlib.client.canvas.ImageNode.of(image.texture(), x, y, w, h,
                    image.width(), image.height()).tint(tint));
            return group;
        }
        float tw = pattern.tileWidth();
        float th = pattern.tileHeight();
        for (float ty = 0; ty < h; ty += th) {
            float ch = Math.min(th, h - ty);
            for (float tx = 0; tx < w; tx += tw) {
                float cw = Math.min(tw, w - tx);
                group.add(new net.ledok.vectorlib.client.canvas.ImageNode(image.texture())
                        .textureSize(image.width(), image.height())
                        .region(0, 0, image.width() * cw / tw, image.height() * ch / th)
                        .bounds(x + tx, y + ty, cw, ch).tint(tint));
            }
        }
        return group;
    }

    /** Plain colored text node (shadow per theme — light themes look doubled with one). */
    public static TextNode text(Component text, int color) {
        return TextNode.of(text).color(color).shadow(TEXT_SHADOW);
    }

    /** Colored text wrapped to the given width (canvas units at scale 1). */
    public static TextNode text(Component text, int color, int wrapWidth) {
        return text(text, color).maxWidth(wrapWidth);
    }

    /** Fixed-size button. */
    public static Button button(Component label, float w, float h, Runnable onClick) {
        return new Button(w, h, label, onClick);
    }

    /** Horizontal room a button needs around its label to clear the border art. */
    private static final float BUTTON_TEXT_PADDING = 12;

    /** Width that fits the label (translations vary a lot in length), never below {@code minWidth}. */
    public static float fitWidth(Component label, float minWidth) {
        return Math.max(minWidth, net.minecraft.client.Minecraft.getInstance().font.width(label) + BUTTON_TEXT_PADDING);
    }

    /**
     * A row of hub sub-tabs/filters: label-sized tabs, the theme's accent-tinted active tab
     * ({@link #TAB_ACTIVE}), and a highlight that slides to a new choice — {@code key} remembers the
     * choice across the tab's rebuilds so the slide survives them.
     */
    public static <T> net.ledok.vectorlib.client.canvas.widget.TabBar<T> tabs(java.util.List<T> values,
            java.util.function.Function<T, Component> label, T selected, java.util.function.Consumer<T> onSelect,
            float minWidth, String key) {
        net.ledok.vectorlib.client.canvas.widget.TabBar<T> bar =
                new net.ledok.vectorlib.client.canvas.widget.TabBar<>(values, label, selected, onSelect);
        styleTabs(bar, minWidth);
        return bar.remember("eden_ld/" + key);
    }

    /** The hub's sub-tab look on any tab bar (a settings page's sections, segmented choices). */
    public static void styleTabs(net.ledok.vectorlib.client.canvas.widget.TabBar<?> bar, float minWidth) {
        bar.height(14).minTabWidth(minWidth).gap(3).tabPadding(BUTTON_TEXT_PADDING / 2f).activeStyle(TAB_ACTIVE);
    }

    /** Button sized to its label via {@link #fitWidth}. */
    public static Button fitButton(Component label, float minWidth, float h, Runnable onClick) {
        return new Button(fitWidth(label, minWidth), h, label, onClick);
    }

    /** 1 px horizontal hairline row in the theme's line color. */
    public static Flex hairline() {
        Flex line = Flex.column();
        line.sizing(Sizing.fill(), Sizing.fixed(1));
        line.backgroundFill(HAIRLINE);
        return line;
    }

    /** Vertical spacer of a fixed height. */
    public static Flex spacer(float px) {
        Flex s = Flex.column();
        s.sizing(Sizing.fill(), Sizing.fixed(px));
        return s;
    }

    /** Flat fill with a 1-unit border — cards that need to stand out from the panel. */
    public static Function<Rect, CanvasNode> framed(int fill, int border) {
        Function<Rect, CanvasNode> row = row(fill);
        if (row != null) {
            return row;
        }
        return r -> Shapes.rect(0.5f, 0.5f, r.width() - 1, r.height() - 1).fill(fill).stroke(border, 1);
    }

    /**
     * The theme's row texture for a row/card fill ({@link #ROW_BG}, {@link #ROW_BG_ALT}), or null
     * when the theme has none or it's another fill.
     */
    @org.jetbrains.annotations.Nullable
    private static Function<Rect, CanvasNode> row(int fill) {
        HubTheme.Rows texture = rows;
        if (texture == null || fill != ROW_BG && fill != ROW_BG_ALT) {
            return null;
        }
        boolean alt = fill == ROW_BG_ALT && fill != ROW_BG;
        HubTheme.Image image = alt && texture.alt() != null ? texture.alt() : texture.image();
        // Without an alt texture the alt rows are the same texture a shade darker.
        int tint = alt && texture.alt() == null ? 0xFFE6E6E6 : 0xFFFFFFFF;
        int border = texture.border();
        NineSlicePanel.Mode edges = tileEdges ? NineSlicePanel.Mode.TILE : NineSlicePanel.Mode.STRETCH;
        return r -> NineSlicePanel.of(image.texture(), image.width(), image.height())
                .insets(border).centerMode(NineSlicePanel.Mode.TILE).edgeMode(edges)
                .tint(tint)
                .bounds(0, 0, r.width(), r.height());
    }

    /**
     * A framed progress bar with its label centered inside ("12 / 20"): dark border, darker
     * track, bright fill — readable on any row background.
     */
    public static net.ledok.vectorlib.client.canvas.widget.ProgressBar progressBar(float width, float height, float ratio,
                                                                              Component label, boolean muted) {
        // VectorLib's bar in the theme's colors; it slides to new values (callers add remember(key)
        // so it also slides when the tab rebuilds).
        net.ledok.vectorlib.client.canvas.widget.ProgressBar bar = new net.ledok.vectorlib.client.canvas.widget.ProgressBar(width, height)
                .value(ratio).label(label).colors(BAR_TRACK, muted ? INK_DIM : BAR_FILL, HAIRLINE_HI)
                .labelColor(INK, TEXT_SHADOW);
        bar.sizing(Sizing.fixed(width), Sizing.fixed(height));
        return bar;
    }

    /** Full-size flat backdrop shape for {@code Flex.background(...)} factories. */
    public static Function<Rect, CanvasNode> flat(int fill) {
        Function<Rect, CanvasNode> row = row(fill);
        if (row != null) {
            return row;
        }
        return r -> Shapes.rect(0, 0, r.width(), r.height()).fill(fill);
    }
}
