package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What the player hasn't seen, per channel — lines from others (the pip's dot), mentions (@N) — and system lines
 * (the System pip's dot), counted in one pass over the store. A count stays valid until the store changes
 * ({@code version}) or a line is seen or new again ({@code seenEpoch}); the pips read it every frame.
 * Mutes and notification levels aren't counted in: they're applied when read (ChatActivity), as a mute ends by the clock.
 */
public final class ChatCounters {

    private final int version;
    private final int seenEpoch;
    private final int[] unseen = new int[Channel.values().length];
    private final int[] mentions = new int[Channel.values().length];
    private final int unseenSystem;

    private ChatCounters(List<ChatEntry> entries, int version, int seenEpoch) {
        this.version = version;
        this.seenEpoch = seenEpoch;
        int system = 0;
        for (ChatEntry entry : entries) {
            if (entry.seen()) {
                continue;
            }
            Channel channel = entry.channel();
            if (channel == null) {
                system++;
                continue;
            }
            unseen[channel.ordinal()]++;
            if (entry.mention()) {
                mentions[channel.ordinal()]++;
            }
        }
        unseenSystem = system;
    }

    /** {@code previous} while the store is as it was, else counted again. */
    public static ChatCounters of(@Nullable ChatCounters previous, List<ChatEntry> entries, int version, int seenEpoch) {
        if (previous != null && previous.version == version && previous.seenEpoch == seenEpoch) {
            return previous;
        }
        return new ChatCounters(entries, version, seenEpoch);
    }

    /** Lines in the channel not seen yet (mentions included). */
    public int unseen(Channel channel) {
        return unseen[channel.ordinal()];
    }

    /** Mentions in the channel not seen yet. */
    public int mentions(Channel channel) {
        return mentions[channel.ordinal()];
    }

    /** Lines of no channel not seen yet (the server's, other mods', deaths, advancements). */
    public int unseenSystem() {
        return unseenSystem;
    }
}
