package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import net.ledok.eden_ld.client.chat.panel.MessageText;
import net.ledok.eden_ld.client.hub.Ignoring;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu a right-click on a chat message opens ({@code docs/PLAN-custom-chat.md}, phase 2): Reply, Copy text, Open
 * profile, Ignore (or Stop ignoring), and for staff Moderate — what applies to that line (the server's lines and
 * the player's own only copy). Drawn by hand over the chat screen like {@link ChannelMenu}, at the mouse.
 */
public final class MessageMenu {

    private static final int ROW = 12;
    private static final int PAD = 4;
    private static final int BACKGROUND = 0xF0101010;
    private static final int BORDER = 0xFF505050;
    private static final int HOVER = 0x40FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFFA0A0A0;

    private record Row(Component label, Runnable action) {
    }

    @Nullable
    private static ChatEntry entry;
    private static int anchorX;
    private static int anchorY;
    private static int menuX;
    private static int menuY;
    private static int menuW;

    private MessageMenu() {
    }

    public static boolean isOpen() {
        return entry != null;
    }

    public static void open(ChatEntry target, int mouseX, int mouseY) {
        entry = target;
        anchorX = mouseX;
        anchorY = mouseY;
    }

    public static void close() {
        entry = null;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("chat.eden_ld.action." + key, args);
    }

    private static Component title(ChatEntry target) {
        String name = MessageText.senderName(target);
        return name == null ? tr("title") : tr("title_from", name);
    }

    private static List<Row> rows(ChatEntry target) {
        List<Row> rows = new ArrayList<>();
        boolean other = MessageActions.fromOther(target);
        if (other) {
            rows.add(new Row(tr("reply"), () -> MessageActions.reply(target)));
        }
        rows.add(new Row(tr("copy"), () -> MessageActions.copy(target)));
        if (target.sender() != null) {
            rows.add(new Row(tr("profile"), () -> MessageActions.profile(target)));
        }
        if (other) {
            rows.add(new Row(Ignoring.menuLabel(target.sender()), () -> MessageActions.ignore(target)));
            if (MessageActions.staff()) {
                rows.add(new Row(tr("moderate"), () -> MessageActions.moderate(target)));
            }
        }
        return rows;
    }

    public static void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        ChatEntry target = entry;
        if (target == null) {
            return;
        }
        List<Row> rows = rows(target);
        Component title = title(target);
        int width = font.width(title);
        for (Row row : rows) {
            width = Math.max(width, font.width(row.label()));
        }
        menuW = width + PAD * 2 + 4;
        int height = ROW + rows.size() * ROW + PAD * 2;
        menuX = Math.max(2, Math.min(anchorX, screenWidth - menuW - 2));
        // Upward from the mouse when there's room (the chat sits at the bottom).
        menuY = Math.max(2, Math.min(anchorY - height, screenHeight - height - 16));
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        graphics.fill(menuX, menuY, menuX + menuW, menuY + height, BACKGROUND);
        graphics.renderOutline(menuX, menuY, menuW, height, BORDER);
        graphics.drawString(font, title, menuX + PAD + 2, menuY + PAD + 2, DIM, false);
        int y = menuY + PAD + ROW;
        for (Row row : rows) {
            if (mouseX >= menuX && mouseX < menuX + menuW && mouseY >= y && mouseY < y + ROW) {
                graphics.fill(menuX + 1, y, menuX + menuW - 1, y + ROW, HOVER);
            }
            graphics.drawString(font, row.label(), menuX + PAD + 2, y + 2, TEXT, false);
            y += ROW;
        }
        graphics.pose().popPose();
    }

    /** A click while open: a row runs its action; anywhere else just closes. Used up either way. */
    public static boolean click(double mouseX, double mouseY) {
        ChatEntry target = entry;
        if (target == null) {
            return false;
        }
        List<Row> rows = rows(target);
        close();
        int y = menuY + PAD + ROW;
        for (Row row : rows) {
            if (mouseX >= menuX && mouseX < menuX + menuW && mouseY >= y && mouseY < y + ROW) {
                row.action().run();
                return true;
            }
            y += ROW;
        }
        return true;
    }
}
