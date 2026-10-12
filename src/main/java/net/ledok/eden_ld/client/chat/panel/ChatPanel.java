package net.ledok.eden_ld.client.chat.panel;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.ChatFilter;
import net.ledok.eden_ld.client.chat.ClientChatChannel;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Eden's chat window ({@code docs/PLAN-custom-chat.md}), drawn instead of vanilla's on Eden servers: the same place,
 * size, opacity, fading and line spacing (vanilla's chat settings), the lines from {@link ChatStore} filtered by the
 * channel rules, a head before each player's message, and a mention's stripe and tint in the notification color (it
 * stays on the HUD longer). Vanilla's chat screen still asks the chat for the style under the mouse and to scroll —
 * ChatComponentMixin hands those questions to this class.
 */
public final class ChatPanel {

    /** A player's head and the gap after it. */
    private static final int HEAD = 10;
    /** How long a line stays on the HUD (ticks; vanilla). */
    private static final int VISIBLE = 200;
    /** How much longer a mention stays (ticks). */
    public static final int MENTION_EXTRA = 400;
    private static final String MENTION_LOG = "Eden mention";

    /** One line on screen: its message, which of its wrapped lines, and the head's room before it. */
    private record Row(ChatEntry entry, FormattedCharSequence text, boolean first, int indent) {
    }

    /** The hover toolbar's buttons (phase 2): reply, copy, the full menu. */
    public enum Tool { REPLY, COPY, MORE }

    /** A toolbar button as drawn last frame, in chat units (after the panel's scale and its 4-unit shift). */
    private record ToolButton(Tool tool, ChatEntry entry, int x0, int y0, int x1, int y1) {
    }

    private static final int TOOL = 11;
    private static final List<ToolButton> TOOLBAR = new ArrayList<>();

    /** The lines the rules show, newest first. */
    private static List<Row> rows = List.of();
    private static boolean dirty = true;
    private static int builtVersion = -1;
    private static int builtWidth = -1;
    @Nullable
    private static Object builtSettings;
    @Nullable
    private static Channel builtChannel;
    private static boolean builtSystem;
    /** Lines scrolled up from the newest. */
    private static int scroll;
    private static boolean newSinceScroll;

    private ChatPanel() {
    }

    /** On an Eden server (it told the client its chat channels): this window, not vanilla's. */
    public static boolean active() {
        return ClientChatChannel.current() != null;
    }

    /** The rules changed (settings, channel): the lines are picked again on the next frame. */
    public static void invalidate() {
        dirty = true;
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

    /** The text width in chat units (vanilla wraps to this). */
    private static int textWidth() {
        return Mth.floor(chat().getWidth() / chat().getScale());
    }

    private static List<Row> rows() {
        int width = textWidth();
        Object settings = HubState.settingsData;
        Channel channel = ClientChatChannel.current();
        boolean system = ClientChatChannel.systemView();
        if (dirty || builtVersion != ChatStore.version() || builtWidth != width || builtSettings != settings
                || builtChannel != channel || builtSystem != system) {
            Font font = minecraft().font;
            List<Row> built = new ArrayList<>();
            List<ChatEntry> entries = ChatStore.entries();
            for (int i = entries.size() - 1; i >= 0; i--) {
                ChatEntry entry = entries.get(i);
                if (ChatFilter.hidden(entry.mark)) {
                    continue;
                }
                int indent = entry.sender() != null ? HEAD : 0;
                List<FormattedCharSequence> lines = entry.lines(font, width - indent);
                for (int line = lines.size() - 1; line >= 0; line--) {
                    built.add(new Row(entry, lines.get(line), line == 0, indent));
                }
            }
            rows = built;
            dirty = false;
            builtVersion = ChatStore.version();
            builtWidth = width;
            builtSettings = settings;
            builtChannel = channel;
            builtSystem = system;
            scroll = Math.max(0, Math.min(scroll, rows.size() - chat().getLinesPerPage()));
        }
        return rows;
    }

    // ---------------------------------------------------------------- drawing

    public static void render(GuiGraphics graphics, int tickCount, int mouseX, int mouseY, boolean focused) {
        TOOLBAR.clear();
        Minecraft minecraft = minecraft();
        if (minecraft.options.chatVisibility().get() == ChatVisiblity.HIDDEN) {
            return;
        }
        List<Row> rows = rows();
        ChatComponent chat = chat();
        double scale = chat.getScale();
        int width = Mth.ceil(chat.getWidth() / scale);
        int base = Mth.floor((graphics.guiHeight() - 40) / scale);
        double opacity = minecraft.options.chatOpacity().get() * 0.9 + 0.1;
        double backgroundOpacity = minecraft.options.textBackgroundOpacity().get();
        double spacing = minecraft.options.chatLineSpacing().get();
        int lineHeight = lineHeight();
        int textOffset = (int) Math.round(-8.0 * (spacing + 1.0) + 4.0 * spacing);
        int perPage = chat.getLinesPerPage();
        int accent = HubNotifications.color() & 0xFFFFFF;
        Font font = minecraft.font;
        // The message under the mouse in the open chat: its lines lit, its toolbar on the hovered line.
        boolean menus = net.ledok.eden_ld.client.chat.ChannelMenu.isOpen() || net.ledok.eden_ld.client.chat.MessageMenu.isOpen();
        Row hovered = focused && !menus ? rowAt(mouseX, mouseY) : null;

        graphics.pose().pushPose();
        graphics.pose().scale((float) scale, (float) scale, 1.0F);
        graphics.pose().translate(4.0F, 0.0F, 0.0F);
        int drawn = 0;
        for (int r = 0; r + scroll < rows.size() && r < perPage; r++) {
            Row row = rows.get(r + scroll);
            ChatEntry entry = row.entry();
            int age = tickCount - entry.message.addedTime() - (entry.mention ? MENTION_EXTRA : 0);
            if (age >= VISIBLE && !focused) {
                continue;
            }
            double fade = focused ? 1.0 : timeFactor(age);
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
            graphics.pose().translate(0.0F, 0.0F, 50.0F);
            if (row.first() && row.indent() > 0) {
                drawHead(graphics, entry.sender(), textY, alpha);
            }
            graphics.drawString(font, row.text(), row.indent(), textY, 0xFFFFFF | alpha << 24);
            if (row == hovered) {
                drawToolbar(graphics, font, entry, width + 8, bottom - lineHeight, bottom, mouseX, mouseY);
            }
            graphics.pose().popPose();
            if (focused) {
                entry.seen = true;
            }
        }

        long queued = minecraft.getChatListener().queueSize();
        if (queued > 0) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, base, 0.0F);
            graphics.fill(-2, 0, width + 4, 9, (int) (255.0 * backgroundOpacity) << 24);
            graphics.pose().translate(0.0F, 0.0F, 50.0F);
            graphics.drawString(font, Component.translatable("chat.queue", queued), 0, 1,
                    0xFFFFFF | (int) (128.0 * opacity) << 24);
            graphics.pose().popPose();
        }

        if (focused && !rows.isEmpty()) {
            int total = rows.size() * lineHeight;
            int shown = drawn * lineHeight;
            if (total != shown && total > 0) {
                int top = scroll * shown / rows.size() - base;
                int bar = shown * shown / total;
                int barAlpha = top > 0 ? 170 : 96;
                int color = newSinceScroll ? 0xCC3333 : 0x3333AA;
                int x = width + 4;
                // Over the lines' backgrounds (z 50), as vanilla draws it.
                graphics.fill(x, -top, x + 2, -top - bar, 100, color | barAlpha << 24);
                graphics.fill(x + 2, -top, x + 1, -top - bar, 100, 0xCCCCCC | barAlpha << 24);
            }
        }
        graphics.pose().popPose();
    }

    /** ↩ ⧉ ⋯ at the right end of the hovered line (Reply only on someone else's message). */
    private static void drawToolbar(GuiGraphics graphics, Font font, ChatEntry entry, int right, int top, int bottom,
                                    int mouseX, int mouseY) {
        List<Tool> tools = new ArrayList<>();
        if (net.ledok.eden_ld.client.chat.MessageActions.fromOther(entry)) {
            tools.add(Tool.REPLY);
        }
        tools.add(Tool.COPY);
        tools.add(Tool.MORE);
        double localX = mouseX / chat().getScale() - 4.0;
        double localY = mouseY / chat().getScale();
        int x = right - tools.size() * TOOL;
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 20.0F);
        // When it came, left of the buttons.
        String time = time(entry.time());
        int timeWidth = font.width(time) + 6;
        graphics.fill(x - timeWidth, top, x, bottom, 0xE0262626);
        graphics.drawString(font, time, x - timeWidth + 3, top + (bottom - top - 8) / 2, 0xFFA0A0A0, false);
        for (Tool tool : tools) {
            boolean over = localX >= x && localX < x + TOOL && localY >= top && localY < bottom;
            graphics.fill(x, top, x + TOOL, bottom, over ? 0xF0505050 : 0xE0262626);
            String symbol = switch (tool) {
                case REPLY -> "↩";
                case COPY -> "⧉";
                case MORE -> "⋯";
            };
            graphics.drawString(font, symbol, x + (TOOL - font.width(symbol)) / 2 + 1, top + (bottom - top - 8) / 2,
                    over ? 0xFFFFFFFF : 0xFFC0C0C0, false);
            TOOLBAR.add(new ToolButton(tool, entry, x, top, x + TOOL, bottom));
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
    public static Tool toolAt(double mouseX, double mouseY) {
        ToolButton button = toolButtonAt(mouseX, mouseY);
        return button == null ? null : button.tool();
    }

    /** The message the toolbar under the mouse belongs to. */
    @Nullable
    public static ChatEntry toolEntryAt(double mouseX, double mouseY) {
        ToolButton button = toolButtonAt(mouseX, mouseY);
        return button == null ? null : button.entry();
    }

    @Nullable
    private static ToolButton toolButtonAt(double mouseX, double mouseY) {
        double localX = mouseX / chat().getScale() - 4.0;
        double localY = mouseY / chat().getScale();
        for (ToolButton button : TOOLBAR) {
            if (localX >= button.x0() && localX < button.x1() && localY >= button.y0() && localY < button.y1()) {
                return button;
            }
        }
        return null;
    }

    /** The message under the mouse in the open chat (right-click: its menu). */
    @Nullable
    public static ChatEntry entryAt(double mouseX, double mouseY) {
        Row row = rowAt(mouseX, mouseY);
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

    @Nullable
    private static Row rowAt(double mouseX, double mouseY) {
        ChatComponent chat = chat();
        if (!chat.isChatFocused() || minecraft().options.chatVisibility().get() == ChatVisiblity.HIDDEN) {
            return null;
        }
        double chatX = mouseX / chat.getScale() - 4.0;
        double chatY = (minecraft().getWindow().getGuiScaledHeight() - mouseY - 40.0) / (chat.getScale() * lineHeight());
        if (chatX < -4.0 || chatX > textWidth()) {
            return null;
        }
        List<Row> rows = rows();
        int lines = Math.min(chat.getLinesPerPage(), rows.size());
        if (chatY < 0.0 || chatY >= lines) {
            return null;
        }
        int index = Mth.floor(chatY + scroll);
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    /** The text style under the mouse: hovers and clicks (links, items, emojis, a channel tag). */
    @Nullable
    public static Style styleAt(double mouseX, double mouseY) {
        Row row = rowAt(mouseX, mouseY);
        if (row == null) {
            return null;
        }
        int x = Mth.floor(mouseX / chat().getScale() - 4.0) - row.indent();
        return x < 0 ? null : minecraft().font.getSplitter().componentStyleAtWidth(row.text(), x);
    }

    /** The tag of the line whose stripe is under the mouse ("You were mentioned", vanilla's "Not Secure"…). */
    @Nullable
    public static GuiMessageTag tagAt(double mouseX, double mouseY) {
        Row row = rowAt(mouseX, mouseY);
        if (row == null || mouseX / chat().getScale() - 4.0 > -1.0) {
            return null;
        }
        if (row.entry().mention) {
            return new GuiMessageTag(HubNotifications.color() & 0xFFFFFF, null,
                    Component.translatable("chat.eden_ld.mention.tag"), MENTION_LOG);
        }
        return row.entry().message.tag();
    }

    // ---------------------------------------------------------------- scrolling

    public static void scroll(int lines) {
        scroll += lines;
        int most = Math.max(0, rows().size() - chat().getLinesPerPage());
        if (scroll > most) {
            scroll = most;
        }
        if (scroll <= 0) {
            scroll = 0;
            newSinceScroll = false;
        }
    }

    public static void resetScroll() {
        scroll = 0;
        newSinceScroll = false;
    }

    /** A message just arrived: while the open chat is scrolled up, the view stays where it is. */
    public static void added(ChatEntry entry) {
        if (scroll > 0 && chat().isChatFocused() && !ChatFilter.hidden(entry.mark)) {
            int indent = entry.sender() != null ? HEAD : 0;
            scroll += entry.lines(minecraft().font, textWidth() - indent).size();
            newSinceScroll = true;
        }
    }

    /** Whether the rules show the message now. */
    public static boolean shows(ChatEntry entry) {
        for (Row row : rows()) {
            if (row.entry() == entry) {
                return true;
            }
        }
        return false;
    }

    /** Scrolls so the message sits in the middle of the open chat (when the rules show it). */
    public static void scrollTo(ChatEntry entry) {
        List<Row> rows = rows();
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).entry() == entry) {
                index = i;
            }
        }
        if (index < 0) {
            return;
        }
        int page = chat().getLinesPerPage();
        scroll = Math.max(0, Math.min(index - page / 2, rows.size() - page));
        newSinceScroll = false;
    }
}
