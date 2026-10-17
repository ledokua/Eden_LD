package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.hub.HubSettings;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tab filters show exactly what the 1.2.5 chat window showed: every marker, setting and pip choice, against a copy
 * of the old {@code ChatFilter.hidden} (with its inputs passed in).
 */
class ChatFilterEquivalenceTest {

    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TROLL = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Predicate<UUID> IGNORED = id -> id.equals(TROLL);

    /** Settings → Chat as 1.2.5 read them (null: none received yet). */
    private record Settings(int hidden, boolean focus, int deaths, int advancements) {
    }

    // ---------------------------------------------------------------- 1.2.5's ChatFilter.hidden

    private static boolean oldHidden(ChatMarker.Mark mark, boolean systemView, Settings settings, Channel selected) {
        if (mark == null) {
            return false;
        }
        if (systemView && mark.kind() != ChatMarker.Kind.DEATH && mark.kind() != ChatMarker.Kind.ADVANCEMENT) {
            return true;
        }
        boolean own = mark.sender() != null && mark.sender().equals(ME);
        if (!own && mark.sender() != null && IGNORED.test(mark.sender())) {
            return true;
        }
        if (settings == null) {
            return false;
        }
        return switch (mark.kind()) {
            case DEATH -> !oldShown(settings.deaths(), mark.team());
            case ADVANCEMENT -> !oldShown(settings.advancements(), mark.team());
            case GLOBAL -> oldChannelHidden(settings, 0, Channel.GLOBAL, own, selected);
            case LOCAL -> oldChannelHidden(settings, 1, Channel.LOCAL, own, selected);
            case PARTY -> oldChannelHidden(settings, 2, Channel.PARTY, own, selected);
            case GUILD -> oldChannelHidden(settings, 3, Channel.GUILD, own, selected);
            case DISCORD -> oldChannelHidden(settings, 4, Channel.GLOBAL, own, selected);
        };
    }

    private static boolean oldShown(int messagesFrom, boolean team) {
        return switch (HubSettings.MessagesFrom.of(messagesFrom)) {
            case EVERYONE -> true;
            case TEAM -> team;
            case NOBODY -> false;
        };
    }

    private static boolean oldChannelHidden(Settings settings, int bit, Channel channel, boolean own, Channel selected) {
        boolean picked = selected == channel && bit == channel.ordinal();
        if (!own && (settings.hidden() & (1 << bit)) != 0 && !picked) {
            return true;
        }
        return settings.focus() && selected != null && selected != channel;
    }

    // ---------------------------------------------------------------- 1.2.6: the pips' tab and its rules

    private static boolean newHidden(ChatMarker.Mark mark, boolean systemView, Settings settings, Channel selected) {
        ChatTabSpec tab = systemView ? ChatTabSpec.SYSTEM
                : settings != null && settings.focus() && selected != null ? ChatTabSpec.of(selected) : ChatTabSpec.ALL;
        ChatRules rules = settings == null ? ChatRules.none(ME, IGNORED, selected)
                : new ChatRules(ME, IGNORED, settings.hidden(), settings.deaths(), settings.advancements(), selected);
        return !tab.shows(mark, rules);
    }

    @Test
    void everyLineShowsAsBefore() {
        UUID[] senders = {null, ME, BOB, TROLL};
        Channel[] selections = Arrays.copyOf(Channel.values(), Channel.values().length + 1);
        int checked = 0;
        for (ChatMarker.Kind kind : ChatMarker.Kind.values()) {
            for (boolean team : new boolean[]{false, true}) {
                for (UUID sender : senders) {
                    ChatMarker.Mark mark = new ChatMarker.Mark(kind, team, sender);
                    for (boolean systemView : new boolean[]{false, true}) {
                        for (Channel selected : selections) {
                            checked += compare(mark, systemView, null, selected);
                            compare(null, systemView, null, selected);
                            for (int hidden = 0; hidden < 32; hidden++) {
                                for (boolean focus : new boolean[]{false, true}) {
                                    for (int deaths = 0; deaths < 3; deaths++) {
                                        for (int advancements = 0; advancements < 3; advancements++) {
                                            Settings settings = new Settings(hidden, focus, deaths, advancements);
                                            checked += compare(mark, systemView, settings, selected);
                                            compare(null, systemView, settings, selected);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(7 * 2 * 4 * 2 * 5 * (1 + 32 * 2 * 3 * 3), checked);
    }

    private static int compare(ChatMarker.Mark mark, boolean systemView, Settings settings, Channel selected) {
        boolean before = oldHidden(mark, systemView, settings, selected);
        boolean after = newHidden(mark, systemView, settings, selected);
        assertEquals(before, after, () -> mark + " system=" + systemView + " " + settings + " selected=" + selected);
        return 1;
    }
}
