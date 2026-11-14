package net.ledok.eden_ld.chat;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shortened link labels and Discord's masked links. */
class ChatLinksTest {

    @Test
    void shortAddressesKeepTheirPath() {
        assertEquals("minestar.com.ua/chests", ChatLinks.shortLabel("https://minestar.com.ua/chests", 32));
        assertEquals("minestar.com.ua", ChatLinks.shortLabel("https://www.minestar.com.ua/", 32));
        assertEquals("example.com/page", ChatLinks.shortLabel("http://example.com/page?utm_source=x#top", 32));
    }

    @Test
    void longPathsKeepHostAndLastPart() {
        assertEquals("github.com/…/README.md",
                ChatLinks.shortLabel("https://github.com/ledokua/Menu_LD/blob/main/README.md", 32));
    }

    @Test
    void neverLongerThanTheLimit() {
        String label = ChatLinks.shortLabel(
                "https://modrinth.com/mod/some-extremely-long-mod-name-that-keeps-going-on-and-on", 32);
        assertTrue(label.length() <= 32, label);
        assertTrue(label.startsWith("modrinth.com/"), label);
        assertTrue(label.endsWith("…"), label);
        String host = ChatLinks.shortLabel("https://a-very-very-long-subdomain.of-some-really-long-host.example/x", 20);
        assertEquals(20, host.length(), host);
    }

    @Test
    void zeroMeansNoLimit() {
        assertEquals("example.com/a/b/c/d", ChatLinks.shortLabel("https://example.com/a/b/c/d", 0));
    }

    @Test
    void maskedLinks() {
        Matcher matcher = ChatLinks.MASKED.matcher("see [the guide](<https://minestar.com.ua/wiki/races>) now");
        assertTrue(matcher.find());
        assertEquals("the guide", matcher.group(1));
        assertEquals("https://minestar.com.ua/wiki/races", matcher.group(2));
        Matcher plain = ChatLinks.MASKED.matcher("[wiki](https://example.com/x)");
        assertTrue(plain.find());
        assertEquals("https://example.com/x", plain.group(2));
        assertFalse(ChatLinks.MASKED.matcher("[not a link] (https://example.com)").find());
    }
}
