package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * One view of the chat ({@code docs/PLAN-chat-v2.md}): a channel's lines, the system lines, or everything — and which
 * lines it shows under the player's chat settings ({@link #shows}). Until the tab strip (phase 5) the chat window has one
 * view, picked by the pips: System, the selected channel with "only the selected channel" on, else All.
 *
 * @param channel the channel of a {@link Kind#CHANNEL} tab (null for the others)
 * @param color   ARGB, as the channel's tags in chat
 */
public record ChatTabSpec(String id, Kind kind, @Nullable Channel channel, String labelKey, int color) {

    public enum Kind {
        CHANNEL,
        /** The server's lines, other mods', deaths and advancements: no chat. */
        SYSTEM,
        ALL,
        /** Staff spy copies (phase 10): nothing is filed here yet. */
        SPY,
        /** Private messages (phase 5b): nothing is filed here yet. */
        PM
    }

    public static final ChatTabSpec SYSTEM = new ChatTabSpec("system", Kind.SYSTEM, null,
            "chat.eden_ld.pip.system.name", 0xFFAAAAAA);
    public static final ChatTabSpec ALL = new ChatTabSpec("all", Kind.ALL, null, "chat.eden_ld.tab.all", 0xFFFFFFFF);
    private static final ChatTabSpec[] CHANNELS = new ChatTabSpec[Channel.values().length];

    static {
        for (Channel channel : Channel.values()) {
            String name = channel.name().toLowerCase(Locale.ROOT);
            CHANNELS[channel.ordinal()] = new ChatTabSpec(name, Kind.CHANNEL, channel, "chat.eden_ld.channel." + name,
                    color(channel));
        }
    }

    /** The channel's tab. */
    public static ChatTabSpec of(Channel channel) {
        return CHANNELS[channel.ordinal()];
    }

    /** The channel's color (ARGB), matching its tags in chat. */
    public static int color(Channel channel) {
        return switch (channel) {
            case GLOBAL -> 0xFFFFFFFF;
            case LOCAL -> 0xFFFFFF55;
            case PARTY -> 0xFF55FF55;
            case GUILD -> 0xFF55FFFF;
        };
    }

    /**
     * Whether this view shows a line with this marker. Unmarked lines (the server's, other mods') show everywhere but
     * Spy and PM; an ignored player's lines nowhere, the player's own aside; deaths and advancements by their own
     * settings; a channel's lines unless switched off in Settings → Chat — the channel typed into always shows its own,
     * and the player's lines ignore the switches. Discord lines have their own switch (bit 4), even in Global.
     */
    public boolean shows(@Nullable ChatMarker.Mark mark, ChatRules rules) {
        if (kind == Kind.SPY || kind == Kind.PM) {
            return false;
        }
        if (mark == null) {
            return true;
        }
        if (kind == Kind.SYSTEM && mark.kind() != ChatMarker.Kind.DEATH && mark.kind() != ChatMarker.Kind.ADVANCEMENT) {
            return false;
        }
        boolean own = mark.sender() != null && mark.sender().equals(rules.self());
        if (!own && rules.ignores(mark.sender())) {
            return false;
        }
        return switch (mark.kind()) {
            // A death or advancement carries its subject only for the ignore list; the switches apply to the player's own too.
            case DEATH -> rules.shown(rules.deaths(), mark.team());
            case ADVANCEMENT -> rules.shown(rules.advancements(), mark.team());
            case GLOBAL -> channelShown(0, Channel.GLOBAL, own, rules);
            case LOCAL -> channelShown(1, Channel.LOCAL, own, rules);
            case PARTY -> channelShown(2, Channel.PARTY, own, rules);
            case GUILD -> channelShown(3, Channel.GUILD, own, rules);
            case DISCORD -> channelShown(4, Channel.GLOBAL, own, rules);
        };
    }

    private boolean channelShown(int bit, Channel line, boolean own, ChatRules rules) {
        // The Discord switch (bit 4) isn't Global's own: it keeps Discord lines hidden.
        boolean picked = rules.typing() == line && bit == line.ordinal();
        if (!own && rules.switchedOff(bit) && !picked) {
            return false;
        }
        return switch (kind) {
            case ALL -> true;
            case CHANNEL -> channel == line;
            default -> false;
        };
    }
}
