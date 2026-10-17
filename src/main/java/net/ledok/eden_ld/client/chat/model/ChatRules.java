package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.hub.HubSettings;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * What a chat view's filter needs to know about the player ({@link ChatTabSpec#shows}): who they are, whom they ignore,
 * their Settings → Chat switches and the channel they type into. Built by the client (ChatFilter) and handed in, so the
 * filters are plain code.
 *
 * @param switchedOff  channels hidden in Settings → Chat (bit per channel; bit 4 = Discord)
 * @param deaths       a {@link HubSettings.MessagesFrom} ordinal
 * @param advancements a {@link HubSettings.MessagesFrom} ordinal
 * @param systemInTabs unmarked lines (the server's, other mods') show in channel tabs too ({@code chat_system_in_tabs};
 *                     always on with the 1.2 look)
 */
public record ChatRules(@Nullable UUID self, Predicate<UUID> ignored, int switchedOff, int deaths, int advancements,
                        @Nullable Channel typing, boolean systemInTabs) {

    /** No settings yet: everything shows. */
    public static ChatRules none(@Nullable UUID self, Predicate<UUID> ignored, @Nullable Channel typing) {
        return new ChatRules(self, ignored, 0, 0, 0, typing, true);
    }

    public boolean ignores(@Nullable UUID player) {
        return player != null && ignored.test(player);
    }

    public boolean switchedOff(int bit) {
        return (switchedOff & 1 << bit) != 0;
    }

    /** A death or advancement line under its setting ({@code team}: about the reader, their party or guild). */
    public boolean shown(int messagesFrom, boolean team) {
        return switch (HubSettings.MessagesFrom.of(messagesFrom)) {
            case EVERYONE -> true;
            case TEAM -> team;
            case NOBODY -> false;
        };
    }
}
