package net.ledok.eden_ld.client.theme;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A theme.json's {@code chat} block. */
class HubThemeChatTest {

    private static HubTheme.Chat read(String json) {
        return HubTheme.Chat.read(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void colorsReadOpaqueOrWithTheirAlpha() {
        HubTheme.Chat chat = read("{\"plate\": \"#101010\", \"hover\": \"#30FFFFFF\", \"glass\": \"off\"}");
        assertEquals(0xFF101010, chat.color("plate", 0));
        assertEquals(0x30FFFFFF, chat.color("hover", 0));
        assertEquals(HubTheme.Chat.Glass.OFF, chat.glass());
    }

    @Test
    void aBadColorKeepsTheDerivedOne() {
        HubTheme.Chat chat = read("{\"ink\": \"#E6EDDG\"}");
        assertFalse(chat.colors().containsKey("ink"));
        assertEquals(0xFFABCDEF, chat.color("ink", 0xFFABCDEF));
    }
}
