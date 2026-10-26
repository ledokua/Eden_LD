package net.ledok.eden_ld.hub.duels;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wager pages' list rules (WagerLists): sorting, filtering, the pinned announcement, "+N more" and the clocks. */
class WagerListsTest {

    private record Listing(int number, long coins, int itemCount, long createdAtMillis, boolean mine)
            implements WagerLists.Entry {
    }

    private static final Listing COINS_OLD = new Listing(1, 500, 0, 1_000, false);
    private static final Listing ITEMS_NEW = new Listing(2, 0, 4, 3_000, false);
    private static final Listing MIXED_MID = new Listing(3, 200, 2, 2_000, false);
    private static final Listing RICH_MID = new Listing(4, 900, 1, 2_000, false);
    private static final Listing MINE = new Listing(5, 50, 0, 5_000, true);
    private static final List<Listing> ALL = List.of(COINS_OLD, ITEMS_NEW, MIXED_MID, RICH_MID, MINE);

    private static List<Integer> numbers(List<Listing> listings) {
        return listings.stream().map(Listing::number).toList();
    }

    @Test
    void newestFirstWithTheNumberBreakingTies() {
        // 2 (3000), then 4 and 3 (both 2000: the higher number is the newer), then 1; the player's own is not listed.
        assertEquals(List.of(2, 4, 3, 1), numbers(WagerLists.arrange(ALL, WagerLists.Sort.NEWEST, WagerLists.Filter.ALL)));
    }

    @Test
    void byCoinsUsesCoinsOnlyThenNewest() {
        // Items weigh nothing: the items-only announcement is last despite four items.
        assertEquals(List.of(4, 1, 3, 2), numbers(WagerLists.arrange(ALL, WagerLists.Sort.COINS, WagerLists.Filter.ALL)));
        Listing tie = new Listing(6, 500, 0, 4_000, false);
        assertEquals(List.of(6, 1), numbers(WagerLists.arrange(List.of(COINS_OLD, tie), WagerLists.Sort.COINS,
                WagerLists.Filter.ALL)), "equal coins: newest first");
    }

    @Test
    void filtersPutMixedStakesInBoth() {
        assertEquals(List.of(4, 3, 1), numbers(WagerLists.arrange(ALL, WagerLists.Sort.NEWEST, WagerLists.Filter.MONEY)));
        assertEquals(List.of(2, 4, 3), numbers(WagerLists.arrange(ALL, WagerLists.Sort.NEWEST, WagerLists.Filter.ITEMS)));
        assertTrue(WagerLists.passes(MIXED_MID, WagerLists.Filter.MONEY));
        assertTrue(WagerLists.passes(MIXED_MID, WagerLists.Filter.ITEMS));
        assertFalse(WagerLists.passes(ITEMS_NEW, WagerLists.Filter.MONEY));
    }

    @Test
    void ownAnnouncementIsPinnedWhateverTheFilter() {
        assertEquals(MINE, WagerLists.pinned(ALL));
        assertNull(WagerLists.pinned(List.of(COINS_OLD, ITEMS_NEW)));
        // Coins-only, so the Items filter would drop it — but it's pinned, never filtered.
        assertFalse(WagerLists.arrange(ALL, WagerLists.Sort.NEWEST, WagerLists.Filter.ITEMS).contains(MINE));
        assertEquals(MINE, WagerLists.pinned(ALL));
    }

    @Test
    void arrangeLeavesTheInputAlone() {
        List<Listing> input = new java.util.ArrayList<>(ALL);
        WagerLists.arrange(input, WagerLists.Sort.COINS, WagerLists.Filter.ALL);
        assertEquals(ALL, input);
    }

    @Test
    void threeIconsThenMore() {
        assertEquals(0, WagerLists.shownItems(0));
        assertEquals(2, WagerLists.shownItems(2));
        assertEquals(3, WagerLists.shownItems(3));
        assertEquals(3, WagerLists.shownItems(9));
        assertEquals(0, WagerLists.moreItems(0));
        assertEquals(0, WagerLists.moreItems(3));
        assertEquals(1, WagerLists.moreItems(4));
        assertEquals(6, WagerLists.moreItems(9));
    }

    @Test
    void clockReadsMinutesThenHours() {
        assertEquals("0:00", WagerLists.clock(0));
        assertEquals("0:00", WagerLists.clock(-5_000));
        assertEquals("0:00", WagerLists.clock(999));
        assertEquals("0:59", WagerLists.clock(59_999));
        assertEquals("24:13", WagerLists.clock((24 * 60 + 13) * 1000L));
        assertEquals("59:59", WagerLists.clock(3_599_000));
        assertEquals("1:00:00", WagerLists.clock(3_600_000));
        assertEquals("1:02:03", WagerLists.clock((3600 + 2 * 60 + 3) * 1000L));
    }

    @Test
    void countdownRunsFromWhenItArrived() {
        assertEquals(30_000, WagerLists.remaining(30_000, 1_000, 1_000));
        assertEquals(20_000, WagerLists.remaining(30_000, 1_000, 11_000));
        assertEquals(0, WagerLists.remaining(30_000, 1_000, 99_000), "never below zero");
        assertEquals(30_000, WagerLists.remaining(30_000, 5_000, 1_000), "a clock that went back doesn't add time");
    }

    @Test
    void coinsHaveSeparators() {
        assertEquals("0", WagerLists.coins(0));
        assertEquals("1,250", WagerLists.coins(1_250));
        assertEquals("1,000,000", WagerLists.coins(1_000_000));
    }
}
