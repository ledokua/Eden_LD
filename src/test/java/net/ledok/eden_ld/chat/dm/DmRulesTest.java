package net.ledok.eden_ld.chat.dm;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmRulesTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("ffffffff-0000-0000-0000-000000000002");
    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    void aDmHasOneIdWhoeverWritesFirst() {
        assertEquals(DmRules.dmKey(A, B), DmRules.dmKey(B, A));
        assertTrue(DmRules.isDm(DmRules.dmKey(A, B)));
        assertFalse(DmRules.isDm(DmRules.groupKey(42)));
        assertTrue(DmRules.dmKey(A, B).length() <= 96);
    }

    @Test
    void trimDropsOldLinesAndThoseOverTheCap() {
        long now = 30 * DAY;
        Deque<Long> lines = new ArrayDeque<>();
        lines.add(now - 8 * DAY);
        lines.add(now - 7 * DAY + 1000);
        lines.add(now);
        assertEquals(1, DmRules.trim(lines, Long::longValue, now));
        assertEquals(2, lines.size());

        Deque<Long> many = new ArrayDeque<>();
        for (int i = 0; i < DmRules.KEEP + 5; i++) {
            many.add(now);
        }
        assertEquals(5, DmRules.trim(many, Long::longValue, now));
        assertEquals(DmRules.KEEP, many.size());
    }

    @Test
    void conversationsExpire() {
        long now = 100 * DAY;
        assertFalse(DmRules.expired(false, 1, 2, now - 20 * DAY, now));
        assertFalse(DmRules.expired(false, 0, 2, now - 6 * DAY, now));
        assertTrue(DmRules.expired(false, 0, 2, now - 8 * DAY, now));
        assertFalse(DmRules.expired(true, 0, 3, now - 20 * DAY, now));
        assertTrue(DmRules.expired(true, 0, 3, now - 31 * DAY, now));
        assertTrue(DmRules.expired(true, 5, 0, now, now));
    }

    @Test
    void groupNamesAreCleaned() {
        assertEquals("Raid team", DmRules.cleanName("  Raid §c  team \n"));
        assertNull(DmRules.cleanName("   "));
        assertNull(DmRules.cleanName("§"));
        assertEquals(DmRules.NAME_MAX, DmRules.cleanName("x".repeat(40)).length());
    }

    @Test
    void aPrivateLineCarriesItsConversation() {
        net.minecraft.network.chat.Component line = net.ledok.eden_ld.chat.ChatMarker.markDm(
                net.minecraft.network.chat.Component.literal("[PM] Bob: hi"), "dm:x", B);
        net.ledok.eden_ld.chat.ChatMarker.Mark mark = net.ledok.eden_ld.chat.ChatMarker.find(line);
        assertEquals(net.ledok.eden_ld.chat.ChatMarker.Kind.DM, mark.kind());
        assertEquals(B, mark.sender());
        assertEquals("dm:x", mark.conversation());
        assertEquals("[PM] Bob: hi", line.getString());
        net.ledok.eden_ld.chat.ChatMarker.Mark note = net.ledok.eden_ld.chat.ChatMarker.find(
                net.ledok.eden_ld.chat.ChatMarker.markDm(net.minecraft.network.chat.Component.literal("x"), "g:1", null));
        assertNull(note.sender());
        assertEquals("g:1", note.conversation());
    }
}
