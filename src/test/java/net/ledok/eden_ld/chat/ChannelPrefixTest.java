package net.ledok.eden_ld.chat;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** "!x message" prefixes, read without allocating: exactly as the 1.2.x lowercase-and-compare did. */
class ChannelPrefixTest {

    /** 1.2.x's {@code Channel.byPrefix}. */
    private static Channel before(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Channel channel : List.of(Channel.GUILD, Channel.GLOBAL, Channel.LOCAL, Channel.PARTY)) {
            if (lower.startsWith(channel.prefix + " ")) {
                return channel;
            }
        }
        return null;
    }

    @Test
    void prefixesNeedTheirSpaceAndTheLongestWins() {
        assertEquals(Channel.GUILD, Channel.byPrefix("!gd hi"));
        assertEquals(Channel.GLOBAL, Channel.byPrefix("!g hi"));
        assertEquals(Channel.LOCAL, Channel.byPrefix("!L hi"));
        assertEquals(Channel.PARTY, Channel.byPrefix("!p "));
        assertNull(Channel.byPrefix("!g"));
        assertNull(Channel.byPrefix("!gdx hi"));
        assertNull(Channel.byPrefix(" !g hi"));
        assertNull(Channel.byPrefix(""));
    }

    @Test
    void sameAnswersAsBefore() {
        String[] texts = {"!g hi", "!G hi", "!gd x", "!GD x", "!Gd ", "!gD", "!gd", "!l", "!l ", "!lx", "!p  two",
                "!P\thi", "!x hi", "hi !g there", "/msg !g", "!", "", "!İ hi", "!ǅ hi", "!g hi", "!gd "};
        for (String text : texts) {
            assertEquals(before(text), Channel.byPrefix(text), text);
        }
    }
}
