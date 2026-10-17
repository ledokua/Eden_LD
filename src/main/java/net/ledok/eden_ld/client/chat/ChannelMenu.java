package net.ledok.eden_ld.client.chat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.ui.ChatMenus;
import net.ledok.eden_ld.client.hub.HubScreen;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.HubSettings.ChannelNotify;
import net.ledok.vectorlib.client.canvas.widget.ContextMenu;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The menu a right-click on a chat channel's pip opens, Discord style: Mute ▸ (5 minutes … until turned back on) or
 * Unmute, Notifications ▸ (All messages, Only @mentions, Nothing), Mark as read, and Chat settings (the hub's
 * Settings → Chat). Changes go to the server like any hub setting.
 * <p>
 * The new look ({@code docs/PLAN-chat-v2.md}, phase 4b) shows it as a VectorLib menu on the chat's canvas: its rows
 * come from {@link #entries} (a {@link Spec} taken when it opens, and the {@link Actions} the rows run). The old look
 * ({@code chat_new_look} off) keeps the 1.2.x menu below, drawn by hand over the chat screen like the old emoji
 * picker: it opens upward from the pip, and a submenu opens beside the row under the mouse.
 */
public final class ChannelMenu {

    private static final int ROW = 12;
    private static final int SUBTITLED = 21;
    private static final int PAD = 4;
    private static final int BACKGROUND = 0xF0101010;
    private static final int BORDER = 0xFF505050;
    private static final int HOVER = 0x40FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFFA0A0A0;
    private static final int OFF = 0xFF606060;
    /** Mute lengths in minutes; -1 until turned back on. */
    private static final int[] MUTES = {5, 15, 60, 24 * 60, HubSettings.MUTED_FOR_GOOD};

    private enum Action { MUTE, UNMUTE, NOTIFY, READ, SETTINGS }

    private record Row(@Nullable Action action, Component label, @Nullable Component subtitle, boolean enabled,
                       boolean submenu) {
        int height() {
            return action == null ? 5 : subtitle != null ? SUBTITLED : ROW;
        }
    }

    @Nullable
    private static Channel channel;
    private static int anchorX;
    private static int anchorY;
    /** The open submenu: MUTE or NOTIFY, or null. */
    @Nullable
    private static Action submenu;
    /** Where things were drawn last frame (clicks use them). */
    private static int menuX;
    private static int menuY;
    private static int menuW;
    private static int subX;
    private static int subY;
    private static int subW;
    /** The top of the row the open submenu belongs to. */
    private static int subAnchorY;

    private ChannelMenu() {
    }

    public static boolean isOpen() {
        return channel != null;
    }

    /** Opens upward from the pip whose top-left is ({@code pipX}, {@code pipTop}). */
    public static void open(Channel target, int pipX, int pipTop) {
        channel = target;
        anchorX = pipX;
        anchorY = pipTop;
        submenu = null;
    }

    public static void close() {
        channel = null;
        submenu = null;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("chat.eden_ld.menu." + key, args);
    }

    // ---------------------------------------------------------------- the new look's rows

    /**
     * What the new look's menu shows, taken when it opens: the channel's name in its color, the muted line (null when
     * not muted), its notification level, whether Mark as read has anything to do, and the theme's dim text color.
     */
    public record Spec(Component title, int titleColor, @Nullable Component mutedLine, ChannelNotify level,
                       boolean unread, int inkDim) {
    }

    /** What the rows do. */
    public interface Actions {
        /** Mute for this long (minutes; {@link HubSettings#MUTED_FOR_GOOD} until turned back on, 0 unmutes). */
        void mute(int minutes);

        void notify(ChannelNotify level);

        void markRead();

        /** The hub's Settings → Chat. */
        void settings();
    }

    /** The menu for {@code target} as it is now; {@code inkDim} is the chat theme's dim text. */
    public static Spec spec(Channel target, int inkDim) {
        return new Spec(Component.translatable("chat.eden_ld.channel." + target.name().toLowerCase(Locale.ROOT)),
                ClientChatChannel.color(target) | 0xFF000000, mutedLine(target), ChatActivity.notify(target),
                ChatActivity.unseen(target) || ChatActivity.mentions(target) > 0, inkDim);
    }

    /** The rows acting on {@code target}, as the old menu's. */
    public static Actions actions(Channel target) {
        return new Actions() {
            @Override
            public void mute(int minutes) {
                ChannelMenu.mute(target, minutes);
            }

            @Override
            public void notify(ChannelNotify level) {
                ChannelMenu.notify(target, level);
            }

            @Override
            public void markRead() {
                ChatActivity.markRead(target);
            }

            @Override
            public void settings() {
                HubState.openSettingsSection = "chat";
                HubScreen.open("settings");
            }
        };
    }

    /**
     * The new look's rows: the channel's name; Mute ▸, or (muted) the muted line and Unmute; Notifications ▸ with the
     * level dim after it (a submenu row has no secondary text); Mark as read (greyed when nothing is unread); a line;
     * Chat settings.
     */
    public static List<ContextMenu.Entry> entries(Spec spec, Actions actions) {
        List<ContextMenu.Entry> entries = new ArrayList<>();
        entries.add(new ContextMenu.Header(spec.title(), spec.titleColor()));
        if (spec.mutedLine() == null) {
            entries.add(new ContextMenu.Submenu(tr("mute"), () -> ChatMenus.build(muteEntries(actions)), true));
        } else {
            entries.add(new ContextMenu.Header(spec.mutedLine(), spec.inkDim()));
            entries.add(new ContextMenu.Item(tr("unmute"), () -> actions.mute(0), true));
        }
        Component notify = Component.empty().append(tr("notify"))
                .append(Component.literal("  ").append(notifyLabel(spec.level())).withColor(spec.inkDim() & 0xFFFFFF));
        entries.add(new ContextMenu.Submenu(notify, () -> ChatMenus.build(notifyEntries(spec.level(), actions)), true));
        entries.add(new ContextMenu.Item(tr("read"), actions::markRead, spec.unread()));
        entries.add(new ContextMenu.Separator());
        entries.add(new ContextMenu.Item(tr("settings"), actions::settings, true));
        return entries;
    }

    /** Mute ▸: 5 minutes … until turned back on. */
    static List<ContextMenu.Entry> muteEntries(Actions actions) {
        List<ContextMenu.Entry> entries = new ArrayList<>();
        for (int minutes : MUTES) {
            entries.add(new ContextMenu.Item(tr(minutes < 0 ? "mute.forever" : "mute." + minutes),
                    () -> actions.mute(minutes), true));
        }
        return entries;
    }

    /** Notifications ▸: the three levels in Discord's order, a dot on {@code current}. */
    static List<ContextMenu.Entry> notifyEntries(ChannelNotify current, Actions actions) {
        List<ContextMenu.Entry> entries = new ArrayList<>();
        for (ChannelNotify level : List.of(ChannelNotify.ALL_MESSAGES, ChannelNotify.ONLY_MENTIONS, ChannelNotify.NOTHING)) {
            entries.add(new ContextMenu.Check(notifyLabel(level), level == current, () -> actions.notify(level), true));
        }
        return entries;
    }

    public static Component notifyLabel(ChannelNotify level) {
        return Component.translatable("chat.eden_ld.notify." + level.name().toLowerCase(Locale.ROOT));
    }

    /** "Muted until 14:35" / "Muted until 13.10 14:35" / "Muted until you turn it back on"; null when not muted. */
    @Nullable
    public static Component mutedLine(Channel target) {
        int end = ChatActivity.muteEnd(target);
        if (end == 0) {
            return null;
        }
        if (end == HubSettings.MUTED_FOR_GOOD) {
            return tr("muted_forever");
        }
        ZonedDateTime when = Instant.ofEpochSecond(end * 60L).atZone(ZoneId.systemDefault());
        String pattern = when.toLocalDate().equals(LocalDate.now()) ? "HH:mm" : "dd.MM HH:mm";
        return tr("muted_until", when.format(DateTimeFormatter.ofPattern(pattern)));
    }

    // ---------------------------------------------------------------- the old look's menu

    private static List<Row> rows(Channel target) {
        List<Row> rows = new ArrayList<>();
        Component muted = mutedLine(target);
        if (muted == null) {
            rows.add(new Row(Action.MUTE, tr("mute"), null, true, true));
        } else {
            rows.add(new Row(Action.UNMUTE, tr("unmute"), muted, true, false));
        }
        rows.add(new Row(Action.NOTIFY, tr("notify"), notifyLabel(ChatActivity.notify(target)), true, true));
        rows.add(new Row(Action.READ, tr("read"), null,
                ChatActivity.unseen(target) || ChatActivity.mentions(target) > 0, false));
        rows.add(new Row(null, Component.empty(), null, false, false));
        rows.add(new Row(Action.SETTINGS, tr("settings"), null, true, false));
        return rows;
    }

    private static List<Component> subRows(Action which, Channel target) {
        List<Component> rows = new ArrayList<>();
        if (which == Action.MUTE) {
            for (int minutes : MUTES) {
                rows.add(tr(minutes < 0 ? "mute.forever" : "mute." + minutes));
            }
        } else {
            ChannelNotify current = ChatActivity.notify(target);
            // Discord's order: All messages, Only @mentions, Nothing.
            for (ChannelNotify level : List.of(ChannelNotify.ALL_MESSAGES, ChannelNotify.ONLY_MENTIONS, ChannelNotify.NOTHING)) {
                rows.add(Component.literal(level == current ? "● " : "○ ").append(notifyLabel(level)));
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------- drawing

    public static void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        Channel target = channel;
        if (target == null) {
            return;
        }
        List<Row> rows = rows(target);
        Component title = Component.translatable("chat.eden_ld.channel." + target.name().toLowerCase(Locale.ROOT));
        int width = font.width(title);
        int height = ROW + PAD * 2;
        for (Row row : rows) {
            width = Math.max(width, font.width(row.label()) + (row.submenu() ? 12 : 0));
            if (row.subtitle() != null) {
                width = Math.max(width, font.width(row.subtitle()));
            }
            height += row.height();
        }
        menuW = width + PAD * 2 + 4;
        menuX = Math.max(2, Math.min(anchorX, screenWidth - menuW - 2));
        menuY = Math.max(2, anchorY - 2 - height);

        // Which row is under the mouse; a submenu row opens its submenu, another row closes it.
        boolean inSub = submenu != null && mouseX >= subX && mouseX < subX + subW && mouseY >= subY
                && mouseY < subY + subHeight(subRows(submenu, target));
        int y = menuY + PAD + ROW;
        Row hovered = null;
        int hoveredY = 0;
        for (Row row : rows) {
            if (mouseX >= menuX && mouseX < menuX + menuW && mouseY >= y && mouseY < y + row.height()) {
                hovered = row;
                hoveredY = y;
            }
            y += row.height();
        }
        if (hovered != null && !inSub) {
            submenu = hovered.submenu() ? hovered.action() : null;
            if (submenu != null) {
                subAnchorY = hoveredY;
            }
        }

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        box(graphics, menuX, menuY, menuW, height);
        graphics.drawString(font, title, menuX + PAD + 2, menuY + PAD + 2,
                ClientChatChannel.color(target), false);
        y = menuY + PAD + ROW;
        for (Row row : rows) {
            if (row.action() == null) {
                graphics.fill(menuX + PAD, y + 2, menuX + menuW - PAD, y + 3, BORDER);
            } else {
                boolean lit = row == hovered || row.submenu() && row.action() == submenu;
                if (lit && row.enabled()) {
                    graphics.fill(menuX + 1, y, menuX + menuW - 1, y + row.height(), HOVER);
                }
                graphics.drawString(font, row.label(), menuX + PAD + 2, y + 2, row.enabled() ? TEXT : OFF, false);
                if (row.submenu()) {
                    graphics.drawString(font, "›", menuX + menuW - PAD - 5, y + 2, DIM, false);
                }
                if (row.subtitle() != null) {
                    graphics.drawString(font, row.subtitle(), menuX + PAD + 2, y + 11, DIM, false);
                }
            }
            y += row.height();
        }
        if (submenu != null) {
            drawSubmenu(graphics, font, subRows(submenu, target), mouseX, mouseY, screenWidth, screenHeight);
        }
        graphics.pose().popPose();
    }

    private static int subHeight(List<Component> rows) {
        return rows.size() * ROW + PAD * 2;
    }

    private static void drawSubmenu(GuiGraphics graphics, Font font, List<Component> rows, int mouseX, int mouseY,
                                    int screenWidth, int screenHeight) {
        int width = 0;
        for (Component row : rows) {
            width = Math.max(width, font.width(row));
        }
        subW = width + PAD * 2 + 4;
        int height = subHeight(rows);
        subX = menuX + menuW + 1 + subW <= screenWidth ? menuX + menuW + 1 : menuX - subW - 1;
        subY = Math.max(2, Math.min(subAnchorY - PAD, screenHeight - height - 16));
        box(graphics, subX, subY, subW, height);
        int y = subY + PAD;
        for (Component row : rows) {
            if (mouseX >= subX && mouseX < subX + subW && mouseY >= y && mouseY < y + ROW) {
                graphics.fill(subX + 1, y, subX + subW - 1, y + ROW, HOVER);
            }
            graphics.drawString(font, row, subX + PAD + 2, y + 2, TEXT, false);
            y += ROW;
        }
    }

    private static void box(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, BACKGROUND);
        graphics.renderOutline(x, y, width, height, BORDER);
    }

    // ---------------------------------------------------------------- clicks

    /** A click while open: true when it was the menu's (a click elsewhere just closes it, and is used up too). */
    public static boolean click(double mouseX, double mouseY) {
        Channel target = channel;
        if (target == null) {
            return false;
        }
        if (submenu != null) {
            List<Component> rows = subRows(submenu, target);
            if (mouseX >= subX && mouseX < subX + subW && mouseY >= subY + PAD && mouseY < subY + PAD + rows.size() * ROW) {
                int index = (int) ((mouseY - subY - PAD) / ROW);
                if (submenu == Action.MUTE) {
                    mute(target, MUTES[index]);
                } else {
                    notify(target, List.of(ChannelNotify.ALL_MESSAGES, ChannelNotify.ONLY_MENTIONS,
                            ChannelNotify.NOTHING).get(index));
                }
                close();
                return true;
            }
        }
        int y = menuY + PAD + ROW;
        for (Row row : rows(target)) {
            if (mouseX >= menuX && mouseX < menuX + menuW && mouseY >= y && mouseY < y + row.height()) {
                if (row.action() != null && row.enabled()) {
                    switch (row.action()) {
                        case UNMUTE -> {
                            mute(target, 0);
                            close();
                        }
                        case READ -> {
                            ChatActivity.markRead(target);
                            close();
                        }
                        case SETTINGS -> {
                            close();
                            HubState.openSettingsSection = "chat";
                            HubScreen.open("settings");
                        }
                        // Mute and Notifications open on hover; a click keeps them open.
                        default -> submenu = row.action();
                    }
                }
                return true;
            }
            y += row.height();
        }
        close();
        return true;
    }

    private static void mute(Channel target, int minutes) {
        ClientPlayNetworking.send(new HubPayloads.SettingChange(HubSettings.CHAT_MUTE.get(target.ordinal()), minutes));
        if (minutes != 0) {
            ChatActivity.clearUnseen(target);
        }
    }

    private static void notify(Channel target, ChannelNotify level) {
        HubPayloads.SettingsData settings = HubState.settingsData;
        int packed = settings == null ? 0 : settings.chatNotify();
        int shift = target.ordinal() * 2;
        packed = packed & ~(3 << shift) | level.ordinal() << shift;
        ClientPlayNetworking.send(new HubPayloads.SettingChange(HubSettings.CHAT_NOTIFY, packed));
    }
}
