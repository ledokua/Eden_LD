package net.ledok.eden_ld.hub.duels;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The plain rules behind the hub's wager pages (Activities → Duels): how Announcements are sorted and
 * filtered, which announcement is pinned, how many stake items a card shows and how many hide behind
 * "+N more", and how a countdown reads. No game classes, so it's unit-tested on its own.
 */
public final class WagerLists {

    /** Items a card shows as icons; the rest are "+N more". */
    public static final int CARD_ITEMS = 3;

    /** Newest first, or the most coins first (items can't be weighed, so "by coins" means coins only). */
    public enum Sort { NEWEST, COINS }

    /** Every announcement, those with coins in the stake, or those with items in it (a mixed stake is in both). */
    public enum Filter { ALL, MONEY, ITEMS }

    /** What sorting and filtering need from an announcement. */
    public interface Entry {
        int number();

        long coins();

        int itemCount();

        long createdAtMillis();

        /** The viewer's own announcement (pinned on top, never filtered out). */
        boolean mine();
    }

    private WagerLists() {
    }

    /** The viewer's own announcement, pinned above the list; null when they have none. */
    public static <T extends Entry> T pinned(List<T> listings) {
        for (T listing : listings) {
            if (listing.mine()) {
                return listing;
            }
        }
        return null;
    }

    /** Everyone else's announcements that pass the filter, in the chosen order (a new list). */
    public static <T extends Entry> List<T> arrange(List<T> listings, Sort sort, Filter filter) {
        List<T> out = new ArrayList<>();
        for (T listing : listings) {
            if (!listing.mine() && passes(listing, filter)) {
                out.add(listing);
            }
        }
        Comparator<T> newest = Comparator.<T>comparingLong(Entry::createdAtMillis).reversed()
                .thenComparing(Comparator.<T>comparingInt(Entry::number).reversed());
        out.sort(sort == Sort.COINS ? Comparator.<T>comparingLong(Entry::coins).reversed().thenComparing(newest) : newest);
        return out;
    }

    public static boolean passes(Entry listing, Filter filter) {
        return switch (filter) {
            case ALL -> true;
            case MONEY -> listing.coins() > 0;
            case ITEMS -> listing.itemCount() > 0;
        };
    }

    /** How many of a stake's items a card shows as icons. */
    public static int shownItems(int total) {
        return Math.max(0, Math.min(CARD_ITEMS, total));
    }

    /** The "+N more" after the icons; 0 = no such line. */
    public static int moreItems(int total) {
        return Math.max(0, total - CARD_ITEMS);
    }

    /** What's left of a countdown sent as {@code sentMillis} at {@code receivedAt}, at {@code now}; never below 0. */
    public static long remaining(long sentMillis, long receivedAt, long now) {
        return Math.max(0, sentMillis - Math.max(0, now - receivedAt));
    }

    /** A countdown or a fight's length: "24:13", "1:02:03" past an hour, "0:00" at or below zero (seconds round down). */
    public static String clock(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long secs = seconds % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.ROOT, "%d:%02d", minutes, secs);
    }

    /** Coins with thousands separators ("1,250"), as the rest of the hub shows them. */
    public static String coins(long coins) {
        return String.format(Locale.ROOT, "%,d", coins);
    }
}
