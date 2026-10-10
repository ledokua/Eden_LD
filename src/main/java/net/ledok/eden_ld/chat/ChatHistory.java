package net.ledok.eden_ld.chat;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The recent Global chat (players and Discord), for players joining: they get the last
 * {@code chat.join_history} lines no older than {@code chat.join_history_minutes}, under a
 * header, so they can follow what's going on. Party and guild have their own history in the
 * hub; Local isn't kept. In memory only (a restart starts fresh).
 */
public final class ChatHistory {

    private record Line(Component text, long time) {
    }

    private static final Deque<Line> LINES = new ArrayDeque<>();

    private ChatHistory() {
    }

    /** A Global line as everyone saw it (marked, so a player hiding Global doesn't see it now either). */
    public static synchronized void add(Component line) {
        int keep = EdenLD.CONFIG.chat.join_history;
        if (keep <= 0) {
            LINES.clear();
            return;
        }
        LINES.addLast(new Line(line, System.currentTimeMillis()));
        while (LINES.size() > keep) {
            LINES.removeFirst();
        }
    }

    /** The lines a joining player gets now (see {@link #sendTo}). */
    public static synchronized java.util.List<Component> recent() {
        long oldest = System.currentTimeMillis() - EdenLD.CONFIG.chat.join_history_minutes * 60_000L;
        return LINES.stream().filter(line -> line.time() >= oldest).map(Line::text).toList();
    }

    public static synchronized void sendTo(ServerPlayer player) {
        long oldest = System.currentTimeMillis() - EdenLD.CONFIG.chat.join_history_minutes * 60_000L;
        java.util.List<Line> recent = LINES.stream().filter(line -> line.time() >= oldest).toList();
        if (recent.isEmpty() || EdenLD.CONFIG.chat.join_history <= 0) {
            return;
        }
        player.sendSystemMessage(ChatMarker.mark(Component.translatable("chat.eden_ld.history", recent.size())
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), ChatMarker.Kind.GLOBAL, false));
        for (Line line : recent) {
            player.sendSystemMessage(line.text());
        }
    }
}
