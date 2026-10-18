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
 * @param systemOnly   system-only lines (1.3.2, {@code SystemOnlyRules}) show in the System tab alone: the new look
 *                     (the 1.2 look shows them as before)
 */
public record ChatRules(@Nullable UUID self, Predicate<UUID> ignored, int switchedOff, int deaths, int advancements,
                        @Nullable Channel typing, boolean systemInTabs, boolean systemOnly) {

    /** As before system-only lines (1.2.x, the 1.2 look): they show as other unmarked lines do. */
    public ChatRules(@Nullable UUID self, Predicate<UUID> ignored, int switchedOff, int deaths, int advancements,
                     @Nullable Channel typing, boolean systemInTabs) {
        this(self, ignored, switchedOff, deaths, advancements, typing, systemInTabs, false);
    }

    /** No settings yet: everything shows. */
    public static ChatRules none(@Nullable UUID self, Predicate<UUID> ignored, @Nullable Channel typing) {
        return none(self, ignored, typing, false);
    }

    /** As {@link #none(UUID, Predicate, Channel)}; {@code systemOnly}: system-only lines in System alone (new look). */
    public static ChatRules none(@Nullable UUID self, Predicate<UUID> ignored, @Nullable Channel typing,
                                 boolean systemOnly) {
        return new ChatRules(self, ignored, 0, 0, 0, typing, true, systemOnly);
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
