package net.ledok.eden_ld.hub.moderation;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last {@link #KEEP} chat lines each player received (every channel, system lines and whispers), in memory only,
 * so a complaint can carry what its reporter saw ({@link ReportDesk}). Whispers remember who the other side was:
 * a complaint keeps only those between the reporter and the player they report. Kept across a relog (a reporter may
 * reconnect first), dropped on server stop. Lines arrive from any thread.
 */
public final class ChatRecall {

    static final int KEEP = 50;
    private static final int MAX_LINE = 300;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** A received line; {@code whisperWith}: the other player's name for a whisper, else null. */
    record Line(long time, String text, @Nullable String whisperWith) {
    }

    private static final Map<UUID, Deque<Line>> LINES = new ConcurrentHashMap<>();
    /** Set while a private line is sent ({@link #sendPrivate}): it is kept as a whisper, not as a system line. */
    private static final ThreadLocal<Boolean> PRIVATE = ThreadLocal.withInitial(() -> false);

    private ChatRecall() {
    }

    /** A system line (Eden's channels, deaths, joins, notices); action-bar lines aren't kept. */
    public static void system(UUID player, Component line) {
        if (!PRIVATE.get()) {
            add(player, line.getString(), null);
        }
    }

    /**
     * Sends a line of a private conversation (1.3.94, {@code chat.dm.DirectMessages}) and keeps it as a whisper with
     * {@code with} (the other player's name in a DM): a complaint carries it only when {@code with} is the player
     * reported. Group lines pass a partner no name can match ({@code DmRules.GROUP_PARTNER}), so they never do.
     */
    public static void sendPrivate(net.minecraft.server.level.ServerPlayer player, Component line, String with) {
        add(player.getUUID(), line.getString(), with);
        PRIVATE.set(true);
        try {
            player.sendSystemMessage(line);
        } finally {
            PRIVATE.set(false);
        }
    }

    /** A signed chat line: a player's message, or a whisper (incoming or the sender's own copy). */
    public static void chat(UUID player, OutgoingChatMessage message, ChatType.Bound bound) {
        String text = bound.decorate(message.content()).getString();
        String with = null;
        var key = bound.chatType().unwrapKey().orElse(null);
        if (key != null && key.equals(ChatType.MSG_COMMAND_INCOMING)) {
            with = bound.name().getString();
        } else if (key != null && key.equals(ChatType.MSG_COMMAND_OUTGOING)) {
            with = bound.targetName().map(Component::getString).orElse("");
        }
        add(player, text, with);
    }

    private static void add(UUID player, String text, @Nullable String whisperWith) {
        if (text == null || text.isBlank()) {
            return;
        }
        String line = text.length() > MAX_LINE ? text.substring(0, MAX_LINE) : text;
        Deque<Line> lines = LINES.computeIfAbsent(player, id -> new ArrayDeque<>());
        synchronized (lines) {
            lines.addLast(new Line(System.currentTimeMillis(), line, whisperWith));
            while (lines.size() > KEEP) {
                lines.removeFirst();
            }
        }
    }

    /**
     * What {@code player} received, oldest first, as "[HH:mm:ss] text" ("[HH:mm:ss] [whisper] …" for whispers):
     * whispers only with {@code whisperPartner} (none when null).
     */
    static List<String> recall(UUID player, @Nullable String whisperPartner) {
        Deque<Line> lines = LINES.get(player);
        List<String> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        synchronized (lines) {
            for (Line line : lines) {
                if (line.whisperWith() != null) {
                    if (whisperPartner == null || !whisperPartner.equalsIgnoreCase(line.whisperWith())) {
                        continue;
                    }
                    out.add("[" + TIME.format(Instant.ofEpochMilli(line.time())) + "] [whisper] " + line.text());
                } else {
                    out.add("[" + TIME.format(Instant.ofEpochMilli(line.time())) + "] " + line.text());
                }
            }
        }
        return out;
    }

    static void clear() {
        LINES.clear();
    }
}
