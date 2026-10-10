package net.ledok.eden_ld.client.hub;

import net.minecraft.network.chat.Component;

/**
 * Hub bookmarks, in bar order. {@link #SETTINGS} is seated at the opposite end of
 * the bar. Tabs flip {@code implemented} as their phases ship; unimplemented ones
 * show a work-in-progress placeholder. Each tab shows an icon (its name only while open, and
 * on hover); until real icon art exists the icon is a colored square or circle.
 */
public enum HubTab {
    HOME(true, 0, 0xFFE8A93A, false),
    MAIL(true, 9, 0xFFE8E0C8, false),
    PROFILE(true, 1, 0xFF9B6BE0, true),
    /** Looks to wear, buy and gift (backgrounds, themes; cosmetics later). */
    WARDROBE(true, 12, 0xFFC0883A, false),
    FRIENDS(true, 2, 0xFF45B85F, true),
    PARTY(true, 3, 0xFF3A8FE0, false),
    GUILD(true, 4, 0xFFD2463C, false),
    WAYSTONES(true, 5, 0xFF2FB8B8, true),
    ACTIVITIES(true, 6, 0xFF8C8C96, false),
    DAILIES(true, 7, 0xFFF0C23C, true),
    /** Staff only: opens the Moderation window instead of a page. */
    MODERATION(true, 10, 0xFF6A6F78, false),
    SETTINGS(true, 8, 0, false);

    public final boolean implemented;
    /**
     * The tab's bit in the player's "opened tabs" setting — fixed per tab, so reordering or adding
     * tabs never shifts what players have already opened.
     */
    public final int bit; // 11 was the Duels tab (now a filter in Activities): don't reuse it
    /** Placeholder icon: its color and whether it's a circle (else a square). */
    public final int iconColor;
    public final boolean round;

    HubTab(boolean implemented, int bit, int iconColor, boolean round) {
        this.implemented = implemented;
        this.bit = bit;
        this.iconColor = iconColor;
        this.round = round;
    }

    /** The bookmark's own text (the settings bookmark is just "⚙"). */
    public Component label() {
        return Component.translatable("gui.eden_ld.hub.tab." + name().toLowerCase());
    }

    /** The tab's name in words: the open bookmark, tooltips, the help window's title. */
    public Component displayName() {
        return this == SETTINGS ? Component.translatable("gui.eden_ld.hub.settings.title") : label();
    }
}
