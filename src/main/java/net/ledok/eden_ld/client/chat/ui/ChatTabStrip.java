package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.ChannelMenu;
import net.ledok.eden_ld.client.chat.ChatActivity;
import net.ledok.eden_ld.client.chat.ChatTabActions;
import net.ledok.eden_ld.client.chat.ClientChatChannel;
import net.ledok.eden_ld.client.chat.model.ChatTabSpec;
import net.ledok.eden_ld.client.chat.model.ChatTabs;
import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import net.ledok.eden_ld.client.hub.HubNotifications;
import net.ledok.eden_ld.hub.HubSettings.ChannelNotify;
import net.ledok.vectorlib.client.canvas.Animation;
import net.ledok.vectorlib.client.canvas.BoxStyle;
import net.ledok.vectorlib.client.canvas.Easing;
import net.ledok.vectorlib.client.canvas.Motion;
import net.ledok.vectorlib.client.canvas.RoundedBox;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The new look's tab strip on one chat screen ({@code docs/PLAN-chat-v2.md}, phase 5), owned by its {@link EdenChatUi}:
 * a chip per tab ({@link ChatTabs}: {@code [All] Global Local [Party] [Guild] System}) in a row above the history
 * plate, wrapping into more rows on a narrow chat ({@link TabStripLayout}). The active tab sits on a lit folder-tab
 * plate that glides from chip to chip, with a foot line in its channel's color (All's in the theme's accent); a dot
 * pops in on a tab with lines not seen yet, @N counts unseen mentions, and a muted channel (or one set to Nothing) is
 * struck through. A click shows the tab (a channel's picks it to write in and jumps to its oldest unseen mention), a
 * right-click opens its menu ({@link TabMenu}). Nothing under the emoji picker, the GIF viewer or vanilla's suggestion
 * list is hovered or clicked, nor anything while a menu is open. A tab change also starts the history's slide
 * ({@link #slideDx}, {@link #slideAlpha}).
 * <p>
 * Adapted from GardenMyth (ProstoMaslo), used with permission: GM's chip, its numbers and motion (ChatChrome's
 * chips, ChatPane's walk), rewritten for Eden's immediate-mode drawing. Lightweight: per-slot arrays made once
 * (labels, widths, springs), laid out again only when the tabs, the wrap edge, the language or the fonts change;
 * nothing is allocated per frame but the tooltip, rebuilt when the hovered tab or what it says changes.
 */
public final class ChatTabStrip {

    private static final int SLOTS = ChatTabs.SLOTS;
    private static final float TAB_H = TabStripLayout.TAB_H;
    private static final float PAD = TabStripLayout.PAD;
    /** The unread dot's size, its center in from the chip's top-right corner. */
    private static final float DOT = 3.6f;
    private static final float DOT_IN = 4;
    /** The lit plate's foot line. */
    private static final float FOOT = 1.2f;
    /** How far a hovered chip is drawn stronger, and the weakest a chip is drawn (Text Background Opacity 0). */
    private static final float HOVER_LIFT = 0.45f;
    private static final float MIN_STRENGTH = 0.35f;
    /** Time steps are capped (GM): after a hitch the springs land where 100 ms would have taken them. */
    private static final float MAX_DT = 100;
    /** The history's slide on a tab switch: how far, how long, how faint it starts. */
    private static final float SLIDE = 14;
    private static final float SLIDE_MS = 220;
    private static final float SLIDE_FAINT = 0.15f;
    /** @N badges: "@1" … "@9", then "@9+". */
    private static final String[] BADGES = {"@1", "@2", "@3", "@4", "@5", "@6", "@7", "@8", "@9", "@9+"};
    private static final float BADGE_H = 9;

    // ---------------------------------------------------------------- per slot (ChatTabs.SLOTS)

    private final Component[] label = new Component[SLOTS];
    /** The label struck through: a muted channel, or one set to Nothing. */
    private final Component[] struck = new Component[SLOTS];
    /** The chip's width: its label and padding (no room for the dot: it sits in the corner). */
    private final float[] slotWidth = new float[SLOTS];
    private final Motion.Spring[] hover = new Motion.Spring[SLOTS];
    private final Motion.Presence[] dot = new Motion.Presence[SLOTS];
    private final int[] badgeWidth = new int[BADGES.length];

    // ---------------------------------------------------------------- per chip shown (strip order)

    private final int[] slotOf = new int[SLOTS];
    private final float[] width = new float[SLOTS];
    private final float[] x = new float[SLOTS];
    private final int[] row = new int[SLOTS];
    private int count;
    private int rows;

    /** What the chips were laid out for: the tabs ({@link ChatTabs#key}), the wrap edge, the fonts and the language. */
    private boolean laidOut;
    private int builtKey;
    private float builtEdge;
    private int builtFonts = -1;
    private String builtLanguage = "";

    /** Where the strip was drawn last (its top; GUI units), and whether it was: clicks use that frame's chips. */
    private float top;
    private boolean live;

    /** The lit plate: x, width, and its row's offset from the strip's top; they snap on the first frame drawn. */
    private final Motion.Spring litX = new Motion.Spring(45);
    private final Motion.Spring litW = new Motion.Spring(45);
    private final Motion.Spring litY = new Motion.Spring(45);
    private boolean litSnap = true;
    private double lastMs = Double.NaN;
    /** Dots already on when the chat opens show at once (no pop on every open). */
    private boolean dotsStarted;

    /** The slide: the active tab last frame (its slot), the direction and start of the slide playing, its ease. */
    private int lastSlot = -1;
    private int slideDir;
    private double slideAtMs = Double.NaN;
    private float slideEase = 1;

    /** The tooltip, built for one tab in one state. */
    private int tipSlot = -1;
    private long tipKey;
    private List<Component> tip = List.of();

    public ChatTabStrip() {
        for (int slot = 0; slot < SLOTS; slot++) {
            label[slot] = Component.translatable(ChatTabs.spec(slot).labelKey());
            struck[slot] = Component.translatable(ChatTabs.spec(slot).labelKey())
                    .withStyle(ChatFormatting.STRIKETHROUGH);
            hover[slot] = new Motion.Spring(60);
            dot[slot] = new Motion.Presence(260, 0);
        }
    }

    // ---------------------------------------------------------------- layout (the start of every frame)

    /**
     * The start of every frame (render HEAD, after the tabs' refresh): lays the chips out again when the tabs, the
     * wrap edge ({@code plateRight}, the history plate's right edge; never past the screen's), the fonts or the
     * language changed, and notes a tab change for the history's slide — the history draws before the strip, in the
     * same frame.
     */
    public void layout(ChatTabs tabs, Font font, float plateRight, int screenWidth) {
        String language = Minecraft.getInstance().options.languageCode;
        int fonts = ChatEntry.fontEpoch();
        if (fonts != builtFonts || !language.equals(builtLanguage)) {
            measure(font);
            builtFonts = fonts;
            builtLanguage = language;
            laidOut = false;
        }
        float edge = TabStripLayout.wrapEdge(plateRight, screenWidth);
        if (!laidOut || tabs.key() != builtKey || edge != builtEdge) {
            count = tabs.count();
            for (int i = 0; i < count; i++) {
                slotOf[i] = tabs.slotAt(i);
                width[i] = slotWidth[slotOf[i]];
            }
            rows = TabStripLayout.layout(width, count, TabStripLayout.MARGIN, edge, x, row);
            laidOut = true;
            builtKey = tabs.key();
            builtEdge = edge;
        }
        noteActive(tabs.activeSlot());
    }

    private void measure(Font font) {
        for (int slot = 0; slot < SLOTS; slot++) {
            slotWidth[slot] = font.width(label[slot]) + 2 * PAD;
        }
        for (int i = 0; i < BADGES.length; i++) {
            badgeWidth[i] = font.width(BADGES[i]) + 4;
        }
    }

    /** A tab change while the chat is open (not its first frame) slides the history in from the new tab's side. */
    private void noteActive(int slot) {
        double now = Motion.nowMs();
        if (slot != lastSlot) {
            if (lastSlot >= 0) {
                // Slots, not strip indexes: a hidden channel's tab may come or go with the change.
                slideDir = TabStripLayout.slideDir(lastSlot, slot);
                slideAtMs = now;
            }
            lastSlot = slot;
        }
        float t = Double.isNaN(slideAtMs) || !Animation.enabled() ? 1f
                : (float) Math.min(1.0, Math.max(0.0, (now - slideAtMs) / SLIDE_MS));
        slideEase = Easing.OUT_CUBIC.apply(t);
    }

    /** The screen was resized or shown again: the lit plate jumps to its chip, and no slide plays for the change. */
    public void snap() {
        litSnap = true;
        lastMs = Double.NaN;
        lastSlot = -1;
        slideAtMs = Double.NaN;
        slideEase = 1;
    }

    /** How tall the strip is (GUI units; 0 with no tabs). */
    public float height() {
        return TabStripLayout.height(rows);
    }

    /** How far right of its place the history is drawn this frame (GUI units): ±14 easing to 0 after a tab switch. */
    public float slideDx() {
        return SLIDE * slideDir * (1 - slideEase);
    }

    /** How strong the history is drawn this frame: 0.15 easing to 1 after a tab switch. */
    public float slideAlpha() {
        return SLIDE_FAINT + (1 - SLIDE_FAINT) * slideEase;
    }

    private int indexOf(int slot) {
        for (int i = 0; i < count; i++) {
            if (slotOf[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- what each tab shows

    /** The channel a slot's tab belongs to, or null. */
    @Nullable
    private static Channel channel(int slot) {
        return ChatTabs.channelOf(slot);
    }

    /** The dot: lines not seen yet (on the active tab too), not while @N shows; never on All or Spy. */
    private static boolean dotOn(int slot) {
        if (slot == ChatTabs.SYSTEM_SLOT) {
            return ChatActivity.unseenSystem();
        }
        Channel channel = channel(slot);
        return channel != null && ChatActivity.unseen(channel) && ChatActivity.mentions(channel) == 0;
    }

    /** Unseen mentions (a channel's tab only). */
    private static int mentions(int slot) {
        Channel channel = channel(slot);
        return channel == null ? 0 : ChatActivity.mentions(channel);
    }

    /** Muted, or notifications Nothing: GM's "ignored" mark. */
    private static boolean struck(int slot) {
        Channel channel = channel(slot);
        return channel != null
                && (ChatActivity.muted(channel) || ChatActivity.notify(channel) == ChannelNotify.NOTHING);
    }

    /** The label's color: struck tabs dimmer still, Spy in the danger color, ink while active or hovered. */
    private static int labelColor(int slot, boolean active, float hover, boolean struck, ChatTheme theme) {
        if (struck) {
            return theme.inkMuted;
        }
        if (slot == ChatTabs.SPY_SLOT) {
            return theme.canvas.danger();
        }
        return active || hover > 0.5f ? theme.tokens.ink() : theme.tokens.inkDim();
    }

    /** The lit plate's foot line: the channel's color (Global's white too), All's in the theme's accent. */
    private static int footColor(int slot, ChatTheme theme) {
        return (slot == ChatTabs.ALL_SLOT ? theme.tokens.focusBorder() : ChatTabs.spec(slot).color()) | 0xFF000000;
    }

    // ---------------------------------------------------------------- drawing

    /**
     * After the screen has drawn (render TAIL, z 150), right above the history plate drawn this frame: the chips and
     * the lit plate (one batch of boxes, also kept for the close fade), the dots, one flush, the labels, the @N
     * badges over every chip, then the hovered tab's tooltip. {@code menus}: a menu has the pointer; {@code list}:
     * vanilla's suggestion list shows; {@code coverA}/{@code coverB}: the picker's and the GIF viewer's rects.
     */
    public void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY, boolean menus, boolean list,
                     int @Nullable [] coverA, int @Nullable [] coverB) {
        float[] plate = ChatLook.historyRect();
        if (plate == null || count == 0) {
            live = false;
            ChatLook.strip(null);
            return;
        }
        ChatTheme theme = ChatTheme.current();
        top = plate[1] - TabStripLayout.GAP - height();
        live = true;
        double now = Motion.nowMs();
        float dt = Double.isNaN(lastMs) ? 0 : (float) Math.min(MAX_DT, Math.max(0, now - lastMs));
        lastMs = now;
        float chrome = ChatLook.chrome();
        float opacity = Math.max(MIN_STRENGTH, ChatLook.panelOpacity());
        int hovered = menus ? -1 : at(mouseX, mouseY, list, coverA, coverB);
        int activeSlot = ChatTabActions.tabs().activeSlot();
        int active = indexOf(activeSlot);

        ChatLook.strip(theme.tab);
        for (int i = 0; i < count; i++) {
            float y0 = TabStripLayout.rowTop(top, row[i]);
            float lift = hover[slotOf[i]].target(i == hovered && i != active ? 1 : 0).step(dt);
            float strength = Math.min(1, opacity * (1 + HOVER_LIFT * lift));
            RoundedBox.draw(graphics, x[i], y0, width[i], TAB_H, theme.tab, strength * chrome, false);
            ChatLook.stripChip(x[i], y0, x[i] + width[i], y0 + TAB_H, strength);
        }
        if (active >= 0) {
            float rowY = TabStripLayout.rowTop(0, row[active]);
            if (litSnap) {
                litX.snap(x[active]);
                litW.snap(width[active]);
                litY.snap(rowY);
                litSnap = false;
            } else {
                litX.target(x[active]).step(dt);
                litW.target(width[active]).step(dt);
                litY.target(rowY).step(dt);
            }
            float lx = litX.value();
            float lw = litW.value();
            float ly = top + litY.value();
            BoxStyle foot = theme.fill(footColor(activeSlot, theme));
            RoundedBox.draw(graphics, lx, ly, lw, TAB_H, theme.tabLit, chrome, false);
            RoundedBox.draw(graphics, lx, ly + TAB_H - FOOT, lw, FOOT, foot, chrome, false);
            ChatLook.stripLit(lx, ly, lx + lw, ly + TAB_H, theme.tabLit, ly + TAB_H - FOOT, ly + TAB_H, foot);
        }
        for (int i = 0; i < count; i++) {
            Motion.Presence presence = dot[slotOf[i]];
            boolean on = dotOn(slotOf[i]);
            if (dotsStarted) {
                presence.set(on, now);
            } else {
                presence.snap(on);
            }
            if (!presence.on()) {
                continue;
            }
            // Pops in past its size and settles (GM); goes at once.
            float size = DOT * Motion.outBack(presence.raw(now), 1.4f);
            if (size < 0.3f) {
                continue;
            }
            float cx = x[i] + width[i] - DOT_IN;
            float cy = TabStripLayout.rowTop(top, row[i]) + DOT_IN;
            RoundedBox.draw(graphics, cx - size / 2, cy - size / 2, size, size, theme.dot, chrome, false);
        }
        dotsStarted = true;
        // Boxes first, then the text over them (amendment 12).
        graphics.flush();
        for (int i = 0; i < count; i++) {
            int slot = slotOf[i];
            boolean isStruck = struck(slot);
            int color = ChatLook.textColor(labelColor(slot, i == active, hover[slot].value(), isStruck, theme), chrome);
            if (color != 0) {
                graphics.drawString(font, isStruck ? struck[slot] : label[slot], Math.round(x[i] + PAD),
                        Math.round(TabStripLayout.rowTop(top, row[i]) + 5), color, false);
            }
        }
        drawBadges(graphics, font, theme, chrome);
        if (hovered >= 0) {
            graphics.renderComponentTooltip(font, tooltip(slotOf[hovered]), mouseX, mouseY);
        }
    }

    /**
     * The @N badges, after every chip and label (a later draw lies over an earlier one at the same z, so a badge
     * covers the next chip's corner and the GIF viewer, drawn after, covers the badge): the badge's right end 2 past
     * its chip's, its top 4 over it, kept on screen.
     */
    private void drawBadges(GuiGraphics graphics, Font font, ChatTheme theme, float chrome) {
        int color = HubNotifications.color();
        BoxStyle style = null;
        for (int i = 0; i < count; i++) {
            int n = mentions(slotOf[i]);
            if (n <= 0) {
                continue;
            }
            if (style == null) {
                style = theme.badge(color);
            }
            int badge = Math.min(n, BADGES.length) - 1;
            RoundedBox.draw(graphics, badgeX(i, badge), badgeY(i), badgeWidth[badge], BADGE_H, style, chrome, false);
        }
        if (style == null) {
            return;
        }
        graphics.flush();
        int ink = ChatLook.textColor(ChatLook.textOn(color), chrome);
        if (ink == 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            int n = mentions(slotOf[i]);
            if (n > 0) {
                int badge = Math.min(n, BADGES.length) - 1;
                graphics.drawString(font, BADGES[badge], Math.round(badgeX(i, badge) + 2), Math.round(badgeY(i) + 1),
                        ink, false);
            }
        }
    }

    private float badgeX(int i, int badge) {
        return Math.max(1, x[i] + width[i] + 2 - badgeWidth[badge]);
    }

    private float badgeY(int i) {
        return Math.max(1, TabStripLayout.rowTop(top, row[i]) - 4);
    }

    // ---------------------------------------------------------------- the pointer

    /** The chip under the pointer (an index in strip order), or -1: none there, or something over the strip has it. */
    private int at(double mouseX, double mouseY, boolean list, int @Nullable [] coverA, int @Nullable [] coverB) {
        if (!live || TabStripLayout.blocked(mouseX, mouseY, coverA, coverB, list,
                TabStripLayout.suggestTop(Minecraft.getInstance().getWindow().getGuiScaledHeight()))) {
            return -1;
        }
        return TabStripLayout.hit(x, row, width, count, top, mouseX, mouseY);
    }

    /**
     * A press (after the chat's canvas and the pills had it), on the chips as drawn last frame: left shows the tab
     * on press — a channel's picks it and jumps to its oldest unseen mention —, right opens its menu under the chip
     * (over it when there's no room), middle does nothing; each is used up. The gaps between chips let it through.
     */
    public boolean clicked(double mouseX, double mouseY, int button, ChatCanvas canvas, boolean list,
                           int @Nullable [] coverA, int @Nullable [] coverB) {
        if (button > 2) {
            return false;
        }
        int index = at(mouseX, mouseY, list, coverA, coverB);
        if (index < 0) {
            return false;
        }
        ChatTabSpec tab = ChatTabs.spec(slotOf[index]);
        if (button == 0) {
            ChatTabActions.show(tab, tab.kind() == ChatTabSpec.Kind.CHANNEL);
        } else if (button == 1) {
            float y0 = TabStripLayout.rowTop(top, row[index]);
            canvas.openTabMenu(tab, x[index], y0, y0 + TAB_H);
        }
        return true;
    }

    // ---------------------------------------------------------------- the tooltip

    /** What the tooltip says depends on, packed: built again only when it changes. */
    private static long tipState(int slot) {
        Channel typing = ClientChatChannel.current();
        long state = typing == null ? 0 : typing.ordinal() + 1;
        Channel channel = channel(slot);
        if (channel == null) {
            return state | (ChatActivity.unseenSystem() ? 1L << 3 : 0);
        }
        long mentions = Math.min(0xFFFF, ChatActivity.mentions(channel));
        return state | (ChatActivity.unseen(channel) ? 1L << 3 : 0) | (long) ChatActivity.notify(channel).ordinal() << 4
                | (ChatTabActions.tabs().hidden(slot) ? 1L << 6 : 0) | mentions << 8
                | (long) ChatActivity.muteEnd(channel) << 32;
    }

    private List<Component> tooltip(int slot) {
        long state = tipState(slot);
        if (slot != tipSlot || state != tipKey) {
            tipSlot = slot;
            tipKey = state;
            tip = buildTooltip(slot);
        }
        return tip;
    }

    /** Eden's 1.2 pip lines and GM's "write here from anywhere" line, then what the mouse and the keys do. */
    private static List<Component> buildTooltip(int slot) {
        List<Component> lines = new ArrayList<>();
        ChatTabSpec tab = ChatTabs.spec(slot);
        Channel typing = ClientChatChannel.current();
        Component typingName = typing == null ? Component.empty() : Component.translatable(
                ChatTabSpec.of(typing).labelKey()).withColor(ClientChatChannel.color(typing) & 0xFFFFFF);
        Channel channel = channel(slot);
        switch (channel != null ? ChatTabSpec.Kind.CHANNEL : tab.kind()) {
            case CHANNEL -> {
                lines.add(Component.translatable(tab.labelKey())
                        .withColor(ClientChatChannel.color(channel) & 0xFFFFFF));
                if (channel == typing) {
                    lines.add(gray(Component.translatable("chat.eden_ld.pip.typing")));
                }
                int mentions = ChatActivity.mentions(channel);
                if (mentions > 0) {
                    lines.add(gray(Component.translatable("chat.eden_ld.pip.mentions", mentions)));
                } else if (ChatActivity.unseen(channel)) {
                    lines.add(gray(Component.translatable("chat.eden_ld.pip.unseen")));
                }
                Component muted = ChannelMenu.mutedLine(channel);
                if (muted != null) {
                    lines.add(gray(muted.copy()));
                }
                lines.add(gray(Component.translatable("chat.eden_ld.pip.notify",
                        ChannelMenu.notifyLabel(ChatActivity.notify(channel)))));
                if (ChatTabActions.tabs().hidden(slot)) {
                    lines.add(gray(Component.translatable("chat.eden_ld.pip.switched_off")));
                }
                lines.add(gray(Component.translatable("chat.eden_ld.tab.once", channel.prefix)));
                lines.add(hint("chat.eden_ld.tab.hint"));
                lines.add(hint("chat.eden_ld.tab.keys"));
            }
            case ALL -> {
                lines.add(Component.translatable(tab.labelKey()));
                lines.add(gray(Component.translatable("chat.eden_ld.tab.all.desc", typingName)));
                lines.add(hint("chat.eden_ld.tab.view_hint"));
                lines.add(hint("chat.eden_ld.tab.keys"));
            }
            case SYSTEM -> {
                lines.add(Component.translatable("chat.eden_ld.pip.system.name").withColor(tab.color() & 0xFFFFFF));
                lines.add(gray(Component.translatable("chat.eden_ld.tab.system.desc", typingName)));
                if (ChatActivity.unseenSystem()) {
                    lines.add(gray(Component.translatable("chat.eden_ld.pip.unseen")));
                }
                lines.add(hint("chat.eden_ld.tab.view_hint"));
                lines.add(hint("chat.eden_ld.tab.keys"));
            }
            default -> lines.add(Component.translatable(tab.labelKey()).withColor(tab.color() & 0xFFFFFF));
        }
        return lines;
    }

    private static Component gray(net.minecraft.network.chat.MutableComponent line) {
        return line.withStyle(ChatFormatting.GRAY);
    }

    /** What the mouse or the keys do: two short lines, as tooltips don't wrap (a 426-wide screen fits both). */
    private static Component hint(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.DARK_GRAY);
    }
}
