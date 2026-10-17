package net.ledok.eden_ld.client.chat.panel;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.ChatFilter;
import net.ledok.eden_ld.client.chat.ClientChatChannel;
import net.ledok.eden_ld.client.chat.model.ChatLayout;
import net.ledok.eden_ld.client.chat.model.ChatRules;
import net.ledok.eden_ld.client.chat.model.ChatTabSpec;
import net.ledok.eden_ld.client.chat.model.ChatViewport;
import net.ledok.eden_ld.client.chat.model.ChatWindow;
import net.ledok.eden_ld.client.chat.ui.ChatLook;
import net.ledok.eden_ld.client.chat.ui.ChatTheme;
import net.ledok.eden_ld.client.chat.ui.EdenChatUi;
import net.ledok.eden_ld.client.hub.HubNotifications;
import net.ledok.eden_ld.client.hub.HubState;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.ledok.vectorlib.client.canvas.RoundedBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One chat window's lines ({@code docs/PLAN-chat-v2.md}): the lines from {@link ChatStore} its tab shows, drawn with
 * vanilla's chat settings — opacity, fading, line spacing — a head before each player's message, and a mention's
 * stripe and tint in the notification color (it stays on the HUD longer). It answers what the open chat asks: the
 * message, style and tag under the mouse, the hover toolbar, scrolling. The main window ({@link ChatViews#main()})
 * sits where vanilla's chat does; {@link ChatPanel} hands vanilla's questions to it.
 */
public final class ChatView {

    /** A player's head and the gap after it. */
    private static final int HEAD = 10;
    /** How long a line stays on the HUD (ticks; vanilla). */
    private static final int VISIBLE = 200;
    private static final String MENTION_LOG = "Eden mention";
    private static final int TOOL = 11;

    /**
     * Where the view is drawn this frame, in GUI units: its left edge, the bottom of its newest line, the chat scale,
     * its width before scaling, how many lines fit, a line's height (chat units), and whether the chat screen is open.
     */
    public record Frame(double left, double bottom, double scale, double width, int lines, int lineHeight,
                        boolean open) {

        /** The text width in chat units (lines wrap to this). */
        int textWidth() {
            return Mth.floor(width / scale);
        }

        /** The backgrounds' width in chat units. */
        int boxWidth() {
            return Mth.ceil(width / scale);
        }

        double chatX(double mouseX) {
            return (mouseX - left) / scale - 4.0;
        }

        double chatY(double mouseY) {
            return mouseY / scale;
        }
    }

    /** One line on screen: its message, which of its wrapped lines, and the head's room before it. */
    private record Row(ChatEntry entry, FormattedCharSequence text, boolean first, int indent) {
    }

    /** A toolbar button as drawn last frame, in chat units (after the view's scale and its 4-unit shift). */
    private record ToolButton(ChatPanel.Tool tool, ChatEntry entry, int x0, int y0, int x1, int y1) {
    }

    private final ChatWindow window;
    private final ChatViewport viewport = new ChatViewport();
    private final List<ToolButton> toolbar = new ArrayList<>();
    /** Screen rects drawn over the lines (the new look's pills): no hover or click reaches a line through them. */
    private float @Nullable [] blockedA;
    private float @Nullable [] blockedB;
    private int @Nullable [] coverA;
    private int @Nullable [] coverB;

    /** The lines the tab shows, newest first, and what they were picked with. */
    private List<Row> rows = List.of();
    private int builtVersion = -1;
    private int builtWidth = -1;
    private int builtFonts = -1;
    private int builtRules = -1;
    @Nullable
    private Object builtSettings;
    @Nullable
    private Channel builtTyping;
    @Nullable
    private ChatTabSpec builtTab;
    private String builtSearch = "";

    ChatView(ChatWindow window) {
        this.window = window;
    }

    public ChatWindow window() {
        return window;
    }

    public ChatViewport viewport() {
        return viewport;
    }

    /** The tab this view shows (until the tab strip: the pips' choice — see {@link ChatFilter#currentTab()}). */
    public ChatTabSpec tab() {
        return ChatFilter.currentTab();
    }

    private static Minecraft minecraft() {
        return Minecraft.getInstance();
    }

    private static ChatComponent chat() {
        return minecraft().gui.getChat();
    }

    private static int lineHeight() {
        return (int) (9.0 * (minecraft().options.chatLineSpacing().get() + 1.0));
    }

    /** Where the view is now: vanilla's chat place and size, or where the window was moved to. */
    public Frame frame() {
        ChatComponent chat = chat();
        int guiHeight = minecraft().getWindow().getGuiScaledHeight();
        ChatLayout layout = window.layout();
        if (layout == null) {
            return new Frame(0.0, guiHeight - 40.0, chat.getScale(), chat.getWidth(), chat.getLinesPerPage(),
                    lineHeight(), chat.isChatFocused());
        }
        ChatLayout.Rect rect = layout.place(minecraft().getWindow().getGuiScaledWidth(), guiHeight);
        double scale = chat.getScale() * ChatLayout.clampZoom(layout.zoom()) / 100.0;
        int lineHeight = lineHeight();
        return new Frame(rect.x(), rect.y() + rect.height(), scale, rect.width(),
                Math.max(1, (int) (rect.height() / (lineHeight * scale))), lineHeight, chat.isChatFocused());
    }

    /** Whether the tab shows the message (search aside). */
    public boolean shows(ChatEntry entry) {
        return tab().shows(entry.mark, ChatFilter.rules());
    }

    private List<Row> rows(Frame frame) {
        int width = frame.textWidth();
        Object settings = HubState.settingsData;
        Channel typing = ClientChatChannel.current();
        ChatTabSpec tab = tab();
        String search = viewport.search();
        if (builtRules != ChatViews.rulesEpoch() || builtVersion != ChatStore.version() || builtWidth != width
                || builtFonts != ChatEntry.fontEpoch() || builtSettings != settings || builtTyping != typing
                || builtTab != tab || !builtSearch.equals(search)) {
            Font font = minecraft().font;
            ChatRules rules = ChatFilter.rules();
            List<Row> built = new ArrayList<>();
            List<ChatEntry> entries = ChatStore.entries();
            for (int i = entries.size() - 1; i >= 0; i--) {
                ChatEntry entry = entries.get(i);
                if (!tab.shows(entry.mark, rules) || !search.isEmpty() && !entry.plain().contains(search)) {
                    continue;
                }
                int indent = entry.sender() != null ? HEAD : 0;
                List<FormattedCharSequence> lines = entry.lines(font, width - indent);
                for (int line = lines.size() - 1; line >= 0; line--) {
                    built.add(new Row(entry, lines.get(line), line == 0, indent));
                }
            }
            rows = built;
            builtRules = ChatViews.rulesEpoch();
            builtVersion = ChatStore.version();
            builtWidth = width;
            builtFonts = ChatEntry.fontEpoch();
            builtSettings = settings;
            builtTyping = typing;
            builtTab = tab;
            builtSearch = search;
            viewport.clamp(rows.size(), frame.lines());
        }
        return rows;
    }

    // ---------------------------------------------------------------- drawing

    public void render(GuiGraphics graphics, int tickCount, int mouseX, int mouseY, boolean focused) {
        toolbar.clear();
        Minecraft minecraft = minecraft();
        if (minecraft.options.chatVisibility().get() == ChatVisiblity.HIDDEN) {
            return;
        }
        // Eden's new look in the open chat; on the HUD, its panels fading out after a close.
        EdenChatUi look = focused ? ChatLook.open() : null;
        if (!focused) {
            ChatLook.drawClosing(graphics);
        }
        Frame frame = frame();
        List<Row> rows = rows(frame);
        double scale = frame.scale();
        int width = frame.boxWidth();
        int base = Mth.floor(frame.bottom() / scale);
        double opacity = minecraft.options.chatOpacity().get() * 0.9 + 0.1;
        double backgroundOpacity = minecraft.options.textBackgroundOpacity().get();
        double spacing = minecraft.options.chatLineSpacing().get();
        int lineHeight = frame.lineHeight();
        int textOffset = (int) Math.round(-8.0 * (spacing + 1.0) + 4.0 * spacing);
        int perPage = frame.lines();
        int scroll = viewport.scroll();
        int accent = HubNotifications.color() & 0xFFFFFF;
        Font font = minecraft.font;
        // The message under the mouse in the open chat: its lines lit, its toolbar on the hovered line.
        boolean menus = net.ledok.eden_ld.client.chat.ChannelMenu.isOpen() || net.ledok.eden_ld.client.chat.MessageMenu.isOpen();
        Row hovered = focused && !menus ? rowAt(frame, mouseX, mouseY) : null;
        float chrome = look != null ? ChatLook.chrome() : 1f;
        ChatTheme theme = look != null ? ChatTheme.current() : null;
        int shown = Math.max(0, Math.min(perPage, rows.size() - scroll));
        if (look != null) {
            drawPlate(graphics, frame, base, shown, lineHeight, theme, chrome, font);
        }

        graphics.pose().pushPose();
        graphics.pose().translate((float) frame.left(), 0.0F, 0.0F);
        graphics.pose().scale((float) scale, (float) scale, 1.0F);
        graphics.pose().translate(4.0F, 0.0F, 0.0F);
        if (look != null) {
            drawEntryChrome(graphics, rows, scroll, shown, base, lineHeight, width, hovered, theme, chrome, accent);
        }
        int drawn = 0;
        for (int r = 0; r + scroll < rows.size() && r < perPage; r++) {
            Row row = rows.get(r + scroll);
            ChatEntry entry = row.entry();
            int age = tickCount - entry.message.addedTime() - (entry.mention ? ChatPanel.MENTION_EXTRA : 0);
            if (age >= VISIBLE && !focused) {
                continue;
            }
            double fade = focused ? 1.0 : timeFactor(age);
            if (look != null) {
                // Lines the HUD was showing stay as they were; older ones come in with the panels.
                fade = age < VISIBLE ? Math.max(timeFactor(age), chrome) : chrome;
            }
            int alpha = (int) (255.0 * fade * opacity);
            int backgroundAlpha = (int) (255.0 * fade * backgroundOpacity);
            drawn++;
            if (alpha <= 3) {
                continue;
            }
            int bottom = base - r * lineHeight;
            int textY = bottom + textOffset;
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 50.0F);
            if (look == null) {
                graphics.fill(-4, bottom - lineHeight, width + 8, bottom, backgroundAlpha << 24);
                if (entry.mention) {
                    graphics.fill(-4, bottom - lineHeight, width + 8, bottom, (int) (0x50 * fade) << 24 | accent);
                    graphics.fill(-4, bottom - lineHeight, -2, bottom, alpha << 24 | accent);
                } else if (entry.message.tag() != null) {
                    graphics.fill(-4, bottom - lineHeight, -2, bottom, entry.message.tag().indicatorColor() | alpha << 24);
                }
                if (hovered != null && hovered.entry() == entry) {
                    graphics.fill(-4, bottom - lineHeight, width + 8, bottom, 0x18FFFFFF);
                }
            }
            graphics.pose().translate(0.0F, 0.0F, 50.0F);
            if (row.first() && row.indent() > 0) {
                if (look != null) {
                    // A head is blitted at once: the boxes queued so far go first.
                    graphics.flush();
                }
                drawHead(graphics, entry.sender(), textY, alpha);
            }
            graphics.drawString(font, row.text(), row.indent(), textY, 0xFFFFFF | alpha << 24);
            if (row == hovered) {
                if (look != null) {
                    drawTagIcon(graphics, font, row, textY);
                    drawToolbar(graphics, font, frame, entry, width + 6, bottom - lineHeight - 1, bottom + 1, textY,
                            mouseX, mouseY, theme, chrome);
                } else {
                    drawToolbar(graphics, font, frame, entry, width + 8, bottom - lineHeight, bottom,
                            bottom - lineHeight + (lineHeight - 8) / 2, mouseX, mouseY, null, 1f);
                }
            }
            graphics.pose().popPose();
            if (focused) {
                entry.markSeen();
            }
        }

        long queued = minecraft.getChatListener().queueSize();
        if (queued > 0) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, base, 0.0F);
            if (look != null) {
                RoundedBox.draw(graphics, -3, 1, width + 6, 10, theme.queue, chrome, false);
            } else {
                graphics.fill(-2, 0, width + 4, 9, (int) (255.0 * backgroundOpacity) << 24);
            }
            graphics.pose().translate(0.0F, 0.0F, 50.0F);
            int color = 0xFFFFFF | (int) (128.0 * opacity) << 24;
            int queueColor = look != null ? ChatLook.textColor(color, chrome) : color;
            if (queueColor != 0) {
                graphics.drawString(font, Component.translatable("chat.queue", queued), 0, look != null ? 2 : 1,
                        queueColor);
            }
            graphics.pose().popPose();
        }

        if (focused && !rows.isEmpty()) {
            int total = rows.size() * lineHeight;
            int shownHeight = drawn * lineHeight;
            if (total != shownHeight && total > 0) {
                int top = scroll * shownHeight / rows.size() - base;
                int bar = shownHeight * shownHeight / total;
                int barAlpha = top > 0 ? 170 : 96;
                if (look != null) {
                    RoundedBox.draw(graphics, width + 3, -top - bar, 2, bar,
                            viewport.newCount() > 0 ? theme.thumbNew : theme.thumb, barAlpha / 255f * chrome, false);
                } else {
                    int color = viewport.newCount() > 0 ? 0xCC3333 : 0x3333AA;
                    int x = width + 4;
                    // Over the lines' backgrounds (z 50), as vanilla draws it.
                    graphics.fill(x, -top, x + 2, -top - bar, 100, color | barAlpha << 24);
                    graphics.fill(x + 2, -top, x + 1, -top - bar, 100, 0xCCCCCC | barAlpha << 24);
                }
            }
        }
        graphics.pose().popPose();
    }

    /**
     * The open chat's history plate, in GUI units so its border and corners keep their size at any chat scale: docked
     * to the screen's left edge, around the shown lines, ending above the hearts (and above the bed's Leave Bed
     * button). With nothing to show, one empty row saying so.
     */
    private void drawPlate(GuiGraphics graphics, Frame frame, int base, int shown, int lineHeight, ChatTheme theme,
                           float chrome, Font font) {
        double scale = frame.scale();
        int rowsTall = Math.max(1, shown);
        float x0 = (float) frame.left();
        float x1 = (float) (frame.left() + (frame.boxWidth() + 12) * scale);
        float bottom = (float) (base * scale);
        int guiHeight = minecraft().getWindow().getGuiScaledHeight();
        boolean bed = minecraft().screen instanceof net.minecraft.client.gui.screens.InBedChatScreen;
        float y1 = bed ? bottom : Math.min(bottom + 2, guiHeight - 39);
        float y0 = (float) ((base - rowsTall * lineHeight) * scale) - 2;
        var style = theme.history(ChatLook.panelOpacity());
        ChatLook.history(x0, y0, x1, y1, style);
        RoundedBox.draw(graphics, x0, y0, x1 - x0, y1 - y0, style, chrome, false);
        if (shown == 0) {
            Component empty = Component.translatable("chat.eden_ld.empty");
            int color = ChatLook.textColor(theme.tokens.inkDim(), chrome);
            if (color != 0) {
                graphics.flush();
                graphics.drawString(font, empty, Math.round((x0 + x1 - font.width(empty)) / 2),
                        Math.round(bottom - lineHeight * (float) scale / 2 - 4), color, false);
            }
        }
    }

    /**
     * Per message, not per wrapped line: the hovered message's light, a mention's tint and stripe in the notification
     * color, another tag's stripe (vanilla's "Not Secure", No Chat Reports' marks) — one rounded box over all of its
     * shown lines.
     */
    private void drawEntryChrome(GuiGraphics graphics, List<Row> rows, int scroll, int shown, int base, int lineHeight,
                                 int width, @Nullable Row hovered, ChatTheme theme, float chrome, int accent) {
        int r = 0;
        while (r < shown) {
            ChatEntry entry = rows.get(r + scroll).entry();
            int last = r;
            while (last + 1 < shown && rows.get(last + 1 + scroll).entry() == entry) {
                last++;
            }
            float y0 = base - (last + 1) * lineHeight;
            float y1 = base - r * lineHeight;
            if (entry.mention) {
                RoundedBox.draw(graphics, -3, y0, width + 10, y1 - y0, theme.fill(0x50000000 | accent), chrome, false);
                RoundedBox.draw(graphics, -3, y0 + 1, 2, y1 - y0 - 2, theme.fill(0xFF000000 | accent), chrome, false);
            } else if (entry.message.tag() != null) {
                RoundedBox.draw(graphics, -3, y0 + 1, 2, y1 - y0 - 2,
                        theme.fill(0xFF000000 | entry.message.tag().indicatorColor()), chrome, false);
            }
            if (hovered != null && hovered.entry() == entry) {
                RoundedBox.draw(graphics, -3, y0, width + 10, y1 - y0, theme.hoverRow, chrome, false);
            }
            r = last + 1;
        }
    }

    /** A tag's icon after the hovered line, as vanilla draws it (No Chat Reports' encrypted mark). */
    private static void drawTagIcon(GuiGraphics graphics, Font font, Row row, int textY) {
        GuiMessageTag tag = row.entry().message.tag();
        if (tag == null || tag.icon() == null) {
            return;
        }
        graphics.flush();
        tag.icon().draw(graphics, row.indent() + font.width(row.text()) + 4, textY);
    }

    /**
     * A toolbar's right end (chat units), moved left of what it would sit under — the picker or the GIF viewer, and in
     * the new look the pills — so they stay readable and clickable and the toolbar stays usable.
     */
    private int clearOfCovers(Frame frame, int right, int width, int top, int bottom, boolean pills) {
        float[][] rects = {
                coverA == null ? null : new float[]{coverA[0], coverA[1], coverA[2], coverA[3]},
                coverB == null ? null : new float[]{coverB[0], coverB[1], coverB[2], coverB[3]},
                pills ? blockedB : null, pills ? blockedA : null};
        double scale = frame.scale();
        for (float[] rect : rects) {
            if (rect == null) {
                continue;
            }
            double x0 = (rect[0] - frame.left()) / scale - 4.0;
            double x1 = (rect[2] - frame.left()) / scale - 4.0;
            if (right - width < x1 && right > x0 && top < rect[3] / scale && bottom > rect[1] / scale) {
                right = Mth.floor(x0) - 2;
            }
        }
        return right;
    }

    /** ↩ ⧉ ⋯ at the right end of the hovered line (Reply only on someone else's message), with when it came. */
    private void drawToolbar(GuiGraphics graphics, Font font, Frame frame, ChatEntry entry, int right, int top,
                             int bottom, int textY, int mouseX, int mouseY, @Nullable ChatTheme theme, float chrome) {
        List<ChatPanel.Tool> tools = new ArrayList<>();
        if (net.ledok.eden_ld.client.chat.MessageActions.fromOther(entry)) {
            tools.add(ChatPanel.Tool.REPLY);
        }
        tools.add(ChatPanel.Tool.COPY);
        tools.add(ChatPanel.Tool.MORE);
        double localX = frame.chatX(mouseX);
        double localY = frame.chatY(mouseY);
        String time = time(entry.time());
        int timeWidth = font.width(time) + 6;
        right = clearOfCovers(frame, right, timeWidth + tools.size() * TOOL, top, bottom, theme != null);
        int x = right - tools.size() * TOOL;
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 20.0F);
        if (theme != null) {
            RoundedBox.draw(graphics, x - timeWidth, top, timeWidth + tools.size() * TOOL, bottom - top, theme.toolbar,
                    chrome, false);
        } else {
            graphics.fill(x - timeWidth, top, x, bottom, 0xE0262626);
        }
        int first = x;
        for (ChatPanel.Tool tool : tools) {
            boolean over = localX >= x && localX < x + TOOL && localY >= top && localY < bottom;
            if (theme != null) {
                if (over) {
                    RoundedBox.draw(graphics, x + 1, top + 1, TOOL - 2, bottom - top - 2, theme.toolbarLit, chrome, false);
                }
            } else {
                graphics.fill(x, top, x + TOOL, bottom, over ? 0xF0505050 : 0xE0262626);
            }
            toolbar.add(new ToolButton(tool, entry, x, top, x + TOOL, bottom));
            x += TOOL;
        }
        if (theme != null) {
            graphics.flush();
        }
        // When it came, left of the buttons; then the buttons' symbols.
        int dim = theme != null ? ChatLook.textColor(theme.tokens.inkDim(), chrome) : 0xFFA0A0A0;
        if (dim != 0) {
            graphics.drawString(font, time, first - timeWidth + 3, textY, dim, false);
        }
        x = first;
        for (ChatPanel.Tool tool : tools) {
            boolean over = localX >= x && localX < x + TOOL && localY >= top && localY < bottom;
            String symbol = switch (tool) {
                case REPLY -> "↩";
                case COPY -> "⧉";
                case MORE -> "⋯";
            };
            int color = theme != null ? ChatLook.textColor(over ? theme.tokens.ink() : theme.tokens.inkDim(), chrome)
                    : over ? 0xFFFFFFFF : 0xFFC0C0C0;
            if (color != 0) {
                graphics.drawString(font, symbol, x + (TOOL - font.width(symbol)) / 2 + 1, textY, color, false);
            }
            x += TOOL;
        }
        graphics.pose().popPose();
    }

    /** "14:35" today, "11.10 14:35" before. */
    private static String time(long millis) {
        java.time.ZonedDateTime when = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault());
        String pattern = when.toLocalDate().equals(java.time.LocalDate.now()) ? "HH:mm" : "dd.MM HH:mm";
        return when.format(java.time.format.DateTimeFormatter.ofPattern(pattern));
    }

    /** The toolbar button under the mouse (screen coordinates), or null. */
    @Nullable
    public ChatPanel.Tool toolAt(double mouseX, double mouseY) {
        ToolButton button = toolButtonAt(mouseX, mouseY);
        return button == null ? null : button.tool();
    }

    /** The message the toolbar under the mouse belongs to. */
    @Nullable
    public ChatEntry toolEntryAt(double mouseX, double mouseY) {
        ToolButton button = toolButtonAt(mouseX, mouseY);
        return button == null ? null : button.entry();
    }

    @Nullable
    private ToolButton toolButtonAt(double mouseX, double mouseY) {
        Frame frame = frame();
        double localX = frame.chatX(mouseX);
        double localY = frame.chatY(mouseY);
        for (ToolButton button : toolbar) {
            if (localX >= button.x0() && localX < button.x1() && localY >= button.y0() && localY < button.y1()) {
                return button;
            }
        }
        return null;
    }

    /** The message under the mouse in the open chat (right-click: its menu). */
    @Nullable
    public ChatEntry entryAt(double mouseX, double mouseY) {
        Row row = rowAt(frame(), mouseX, mouseY);
        return row == null ? null : row.entry();
    }

    /** The writer's face before their message (their skin while they're on the server, else the default one). */
    private static void drawHead(GuiGraphics graphics, @Nullable UUID sender, int y, int alpha) {
        if (sender == null) {
            return;
        }
        PlayerInfo info = minecraft().getConnection() == null ? null : minecraft().getConnection().getPlayerInfo(sender);
        PlayerSkin skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(sender);
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha / 255.0F);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        PlayerFaceRenderer.draw(graphics, skin, 0, y, 8);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** Vanilla's fade-out of HUD lines. */
    private static double timeFactor(int age) {
        double factor = Mth.clamp((1.0 - age / (double) VISIBLE) * 10.0, 0.0, 1.0);
        return factor * factor;
    }

    // ---------------------------------------------------------------- the mouse (the open chat)

    /** What covers the lines this frame (GUI rects x0, y0, x1, y1, or null). */
    public void block(float @Nullable [] a, float @Nullable [] b) {
        blockedA = a;
        blockedB = b;
    }

    /** The panels over the lines this frame, in either look (the emoji picker, the GIF viewer: GUI rects, or null). */
    public void cover(int @Nullable [] a, int @Nullable [] b) {
        coverA = a;
        coverB = b;
    }

    private static boolean inside(float @Nullable [] rect, double x, double y) {
        return rect != null && x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3];
    }

    private static boolean inside(int @Nullable [] rect, double x, double y) {
        return rect != null && x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3];
    }

    @Nullable
    private Row rowAt(Frame frame, double mouseX, double mouseY) {
        if (!frame.open() || minecraft().options.chatVisibility().get() == ChatVisiblity.HIDDEN) {
            return null;
        }
        if (inside(coverA, mouseX, mouseY) || inside(coverB, mouseX, mouseY)) {
            return null;
        }
        if (ChatLook.open() != null && (inside(blockedA, mouseX, mouseY) || inside(blockedB, mouseX, mouseY))) {
            return null;
        }
        double chatX = frame.chatX(mouseX);
        double chatY = (frame.bottom() - mouseY) / (frame.scale() * frame.lineHeight());
        // The whole background counts (its right end holds the hover toolbar), not only the text.
        if (chatX < -4.0 || chatX >= frame.boxWidth() + 8) {
            return null;
        }
        List<Row> rows = rows(frame);
        int lines = Math.min(frame.lines(), rows.size());
        if (chatY < 0.0 || chatY >= lines) {
            return null;
        }
        int index = Mth.floor(chatY + viewport.scroll());
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    /** The text style under the mouse: hovers and clicks (links, items, emojis, a channel tag). */
    @Nullable
    public Style styleAt(double mouseX, double mouseY) {
        Frame frame = frame();
        Row row = rowAt(frame, mouseX, mouseY);
        if (row == null) {
            return null;
        }
        int x = Mth.floor(frame.chatX(mouseX)) - row.indent();
        return x < 0 ? null : minecraft().font.getSplitter().componentStyleAtWidth(row.text(), x);
    }

    /** The tag of the line whose stripe is under the mouse ("You were mentioned", vanilla's "Not Secure"…). */
    @Nullable
    public GuiMessageTag tagAt(double mouseX, double mouseY) {
        Frame frame = frame();
        Row row = rowAt(frame, mouseX, mouseY);
        if (row == null || frame.chatX(mouseX) > -1.0) {
            return null;
        }
        if (row.entry().mention) {
            return new GuiMessageTag(HubNotifications.color() & 0xFFFFFF, null,
                    Component.translatable("chat.eden_ld.mention.tag"), MENTION_LOG);
        }
        return row.entry().message.tag();
    }

    /** Whether the mouse is on the "N messages waiting" line under the newest one (Chat Delay; vanilla's place). */
    public boolean queueLineAt(double mouseX, double mouseY) {
        Frame frame = frame();
        if (!frame.open() || minecraft().options.hideGui
                || minecraft().options.chatVisibility().get() == ChatVisiblity.HIDDEN
                || minecraft().getChatListener().queueSize() == 0L) {
            return false;
        }
        double x = mouseX - frame.left() - 2.0;
        double below = frame.bottom() - mouseY;
        if (ChatLook.open() != null) {
            // The new look's box sits one unit lower and is a unit taller (render).
            return x <= frame.textWidth() && below < -frame.scale() && below > Mth.floor(-11.0 * frame.scale());
        }
        return x <= frame.textWidth() && below < 0.0 && below > Mth.floor(-9.0 * frame.scale());
    }

    // ---------------------------------------------------------------- scrolling

    public void scroll(int lines) {
        Frame frame = frame();
        viewport.scrollBy(lines, Math.max(0, rows(frame).size() - frame.lines()));
    }

    public void resetScroll() {
        viewport.reset();
    }

    /** A message just arrived: while the open chat is scrolled up, the view stays where it is. */
    void added(ChatEntry entry) {
        if (viewport.scroll() == 0) {
            return;
        }
        Frame frame = frame();
        if (frame.open() && shows(entry)) {
            int indent = entry.sender() != null ? HEAD : 0;
            viewport.hold(entry.lines(minecraft().font, frame.textWidth() - indent).size());
        }
    }

    /** Whether the view shows the message now. */
    public boolean showsNow(ChatEntry entry) {
        for (Row row : rows(frame())) {
            if (row.entry() == entry) {
                return true;
            }
        }
        return false;
    }

    /** Scrolls so the message sits in the middle of the open chat (when the view shows it). */
    public void scrollTo(ChatEntry entry) {
        Frame frame = frame();
        List<Row> rows = rows(frame);
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).entry() == entry) {
                index = i;
            }
        }
        if (index >= 0) {
            viewport.center(index, frame.lines(), rows.size());
        }
    }
}
