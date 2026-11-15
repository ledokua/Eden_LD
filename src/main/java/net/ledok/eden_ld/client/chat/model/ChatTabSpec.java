package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * One view of the chat ({@code docs/PLAN-chat-v2.md}): a channel's lines, the system lines, or everything — and which
 * lines it shows under the player's chat settings ({@link #shows}). The new look picks it with its tab strip
 * ({@link ChatTabs}, phase 5); the 1.2 look with its pips: System, the selected channel with "only the selected
 * channel" on, else All.
 *
 * @param channel      the channel of a {@link Kind#CHANNEL} tab (null for the others)
 * @param labelKey     the label's translation key; a {@link Kind#PM} tab's own name instead (a player's, a group's)
 * @param color        ARGB, as the channel's tags in chat
 * @param conversation a {@link Kind#PM} tab's conversation (1.3.94); null for the others
 */
public record ChatTabSpec(String id, Kind kind, @Nullable Channel channel, String labelKey, int color,
                          @Nullable String conversation) {

    public enum Kind {
        CHANNEL,
        /** The server's lines, other mods', deaths and advancements: no chat. The only tab of system-only lines. */
        SYSTEM,
        ALL,
        /** Staff spy copies (phase 10): nothing is filed here yet. */
        SPY,
        /** A private conversation (1.3.94): a DM or a group chat, its lines alone. */
        PM
    }

    /** The color of private lines and tabs (vanilla's whisper pink). */
    public static final int PM_COLOR = 0xFFFF77FF;

    public ChatTabSpec(String id, Kind kind, @Nullable Channel channel, String labelKey, int color) {
        this(id, kind, channel, labelKey, color, null);
    }

    /** A private conversation's tab, named {@code name}. */
    public static ChatTabSpec pm(String conversation, String name) {
        return new ChatTabSpec("pm:" + conversation, Kind.PM, null, name, PM_COLOR, conversation);
    }

    /** The tab's name as the strip shows it: translated, or a conversation's own name as it is. */
    public net.minecraft.network.chat.MutableComponent label() {
        return kind == Kind.PM ? net.minecraft.network.chat.Component.literal(labelKey)
                : net.minecraft.network.chat.Component.translatable(labelKey);
    }

    public static final ChatTabSpec SYSTEM = new ChatTabSpec("system", Kind.SYSTEM, null,
            "chat.eden_ld.badge.system", 0xFFAAAAAA);
    public static final ChatTabSpec ALL = new ChatTabSpec("all", Kind.ALL, null, "chat.eden_ld.tab.all", 0xFFFFFFFF);
    /** Staff spy copies (phase 10): its place in the strip is kept, but nothing shows it yet. */
    public static final ChatTabSpec SPY = new ChatTabSpec("spy", Kind.SPY, null, "chat.eden_ld.tab.spy", 0xFFE5534B);
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

    /** As {@link #shows(ChatMarker.Mark, boolean, ChatRules)} for a line that isn't system-only. */
    public boolean shows(@Nullable ChatMarker.Mark mark, ChatRules rules) {
        return shows(mark, false, rules);
    }

    /**
     * Whether this view shows a line with this marker. Unmarked lines (the server's, other mods') show everywhere but
     * Spy and PM — in a channel's tab only while {@link ChatRules#systemInTabs()}, and in System alone when they're
     * system-only ({@code systemOnly}: other admins' command echoes, routine notices) under
     * {@link ChatRules#systemOnly()}; an ignored player's lines nowhere, the player's own aside; deaths and
     * advancements by their own settings, in every tab
     * (Party's and Guild's only their members'; joins likewise, else as the server's lines); a channel's lines unless switched off in Settings → Chat — the
     * channel typed into always shows its own, and the player's lines ignore the switches. Discord lines have their
     * own switch (bit 4), even in Global. A private line (DM, 1.3.94) shows in its conversation's tab and in All —
     * with the 1.2 look in the channel views too — never in System; a PM tab shows nothing else.
     */
    public boolean shows(@Nullable ChatMarker.Mark mark, boolean systemOnly, ChatRules rules) {
        if (kind == Kind.SPY) {
            return false;
        }
        if (kind == Kind.PM) {
            return mark != null && mark.kind() == ChatMarker.Kind.DM && conversation != null
                    && conversation.equals(mark.conversation())
                    && (mark.sender() == null || mark.sender().equals(rules.self()) || !rules.ignores(mark.sender()));
        }
        // A system-only line lives in System alone with the new look; the 1.2 look shows it as before.
        if (systemOnly && mark == null && rules.systemOnly()) {
            return kind == Kind.SYSTEM;
        }
        // chat_system_in_tabs only takes lines away: with it on, the rules below run as they always did.
        if (kind == Kind.CHANNEL && mark == null && !rules.systemInTabs()) {
            return false;
        }
        if (mark == null) {
            return true;
        }
        if (kind == Kind.SYSTEM && mark.kind() != ChatMarker.Kind.DEATH && mark.kind() != ChatMarker.Kind.ADVANCEMENT
                && mark.kind() != ChatMarker.Kind.JOIN) {
            return false;
        }
        // Joins and leaves show as the server's lines did; the Party and Guild tabs take only their members'.
        if (mark.kind() == ChatMarker.Kind.JOIN) {
            return kind != Kind.CHANNEL || rules.systemInTabs() && inCircle(mark);
        }
        boolean own = mark.sender() != null && mark.sender().equals(rules.self());
        if (!own && rules.ignores(mark.sender())) {
            return false;
        }
        return switch (mark.kind()) {
            // A death or advancement carries its subject only for the ignore list; the switches apply to the player's own too.
            case DEATH -> rules.shown(rules.deaths(), mark.team()) && inCircle(mark);
            case ADVANCEMENT -> rules.shown(rules.advancements(), mark.team()) && inCircle(mark);
            case JOIN -> true;
            case GLOBAL -> channelShown(0, Channel.GLOBAL, own, rules);
            case LOCAL -> channelShown(1, Channel.LOCAL, own, rules);
            case PARTY -> channelShown(2, Channel.PARTY, own, rules);
            case GUILD -> channelShown(3, Channel.GUILD, own, rules);
            case DISCORD -> channelShown(4, Channel.GLOBAL, own, rules);
            case DM -> kind == Kind.ALL || kind == Kind.CHANNEL && !rules.systemOnly();
        };
    }

    /** Whether a death, advancement or join belongs in this tab: Party's and Guild's take only their members'. */
    private boolean inCircle(ChatMarker.Mark mark) {
        if (kind != Kind.CHANNEL) {
            return true;
        }
        return switch (channel) {
            case PARTY -> mark.party();
            case GUILD -> mark.guild();
            default -> true;
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
