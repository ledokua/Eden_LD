package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.chat.emoji.EmojiRegistry;
import net.ledok.eden_ld.chat.emoji.UnicodeEmoji;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * The emoji picker's tabs, shared by the chat screen's picker and the hub's (party and guild
 * chat): 0 ⭐ favorites, 1 🖼 saved stickers (drawn by each picker as images), 2 the server's
 * emojis ({@code :minestar:} icon), then the server's groups, then Discord's categories. A
 * search covers every emoji, whatever the tab.
 */
public final class EmojiTabs {

    public static final int FAVORITES = 0;
    public static final int STICKERS = 1;
    public static final int SERVER = 2;
    /** Tabs before the server's groups. */
    private static final int FIXED = 3;
    /** Category tab icons (standard emoji names), same order as {@link UnicodeEmoji.Category}. */
    private static final String[] CATEGORY_ICONS = {"grinning", "bear", "hamburger", "soccer", "red_car", "bulb",
            "heart", "checkered_flag"};

    private EmojiTabs() {
    }

    public static int count() {
        return FIXED + EmojiFont.groups().size() + CATEGORY_ICONS.length;
    }

    /** The tab a picker opens on: favorites, or the server's emojis while there are none. */
    public static int initial() {
        return EmojiFont.favorites().isEmpty() ? SERVER : FAVORITES;
    }

    /** The server group a tab shows, or null. */
    public static HubPayloads.EmojiGroup group(int tab) {
        int index = tab - FIXED;
        return index >= 0 && index < EmojiFont.groups().size() ? EmojiFont.groups().get(index) : null;
    }

    /** The category a tab shows (tabs after the groups). */
    public static UnicodeEmoji.Category category(int tab) {
        UnicodeEmoji.Category[] all = UnicodeEmoji.Category.values();
        return all[Math.max(0, Math.min(all.length - 1, tab - FIXED - EmojiFont.groups().size()))];
    }

    /** Whether the picker shows the saved stickers now (the Stickers tab, no search). */
    public static boolean stickers(int tab, String query) {
        return tab == STICKERS && (query == null || query.isBlank());
    }

    /** What a tab lists — or, with a query, every emoji matching it (the Stickers tab lists no emojis). */
    public static List<EmojiFont.Entry> entries(int tab, String query) {
        if (query != null && !query.isBlank()) {
            return EmojiFont.search(query);
        }
        if (tab == FAVORITES) {
            return EmojiFont.favorites();
        }
        if (tab == STICKERS) {
            return List.of();
        }
        if (tab == SERVER) {
            return EmojiFont.entries();
        }
        HubPayloads.EmojiGroup group = group(tab);
        return group != null ? EmojiFont.groupEntries(group) : EmojiFont.category(category(tab));
    }

    /** A tab's icon: ⭐ for favorites, :minestar: (else the first server emoji), a group's or category's emoji. */
    public static Component icon(int tab) {
        EmojiFont.Entry icon;
        if (tab == FAVORITES) {
            icon = EmojiFont.standardByName("star");
        } else if (tab == STICKERS) {
            icon = EmojiFont.standardByName("frame_photo");
        } else if (tab == SERVER) {
            icon = EmojiFont.byName("minestar");
            if (icon == null && !EmojiFont.entries().isEmpty()) {
                icon = EmojiFont.entries().get(0);
            }
        } else if (group(tab) != null) {
            HubPayloads.EmojiGroup group = group(tab);
            icon = EmojiFont.byName(group.icon());
            List<EmojiFont.Entry> members = icon == null ? EmojiFont.groupEntries(group) : List.of();
            if (!members.isEmpty()) {
                icon = members.get(0);
            }
        } else {
            icon = EmojiFont.standardByName(CATEGORY_ICONS[category(tab).ordinal()]);
        }
        return icon == null ? Component.literal(tab == FAVORITES ? "★" : "?") : glyph(icon);
    }

    /** A tab's name (tooltip); favorites show how many of the limit are used. */
    public static Component name(int tab) {
        if (tab == FAVORITES) {
            return Component.translatable("chat.eden_ld.emoji.favorites", EmojiFont.favorites().size(),
                    EmojiFont.favoritesLimit());
        }
        if (tab == STICKERS) {
            return Component.translatable("chat.eden_ld.sticker.tab", EmojiFont.stickers().size(), EmojiFont.stickersLimit());
        }
        if (tab == SERVER) {
            return Component.translatable("chat.eden_ld.emoji.tab.server");
        }
        HubPayloads.EmojiGroup group = group(tab);
        return group != null ? Component.literal(group.name())
                : Component.translatable("chat.eden_ld.emoji.category." + category(tab).name().toLowerCase(Locale.ROOT));
    }

    /** One emoji as text: its character in Eden's emoji font, white so nothing tints the image. */
    public static Component glyph(EmojiFont.Entry entry) {
        return Component.literal(Character.toString(entry.codepoint()))
                .withStyle(style -> style.withFont(EmojiRegistry.FONT).withColor(0xFFFFFF));
    }
}
