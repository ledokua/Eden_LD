package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a hub tab has to tell the player, and how it's shown: a count (friend requests, party and
 * guild invites, dailies to claim, shop deliveries) or "new" for a tab never opened. Settings →
 * Notifications turns kinds off; Settings → Visual picks badges or a glow and their color (the
 * theme's own by default). Stored server side like every hub setting.
 */
public final class HubNotifications {

    /** The kinds, in {@link HubSettings#NOTIFY_OFF} bit order. */
    public enum Kind {
        NEW_TABS, FRIEND_REQUESTS, PARTY_INVITES, GUILD_INVITES, DAILIES, SHOP, MAIL, ACTIVITIES, DUELS
    }

    /** The fixed colors after the theme's own, in {@link HubSettings#NOTIFY_COLOR} order (1..8). */
    public static final int[] COLORS = {
            0xFFE0402F, 0xFFF0892E, 0xFFF2C94C, 0xFF4CC46A, 0xFF3CC8D8, 0xFF4A7CF0, 0xFFA35BE8, 0xFFF06AB0};
    public static final String[] COLOR_NAMES = {"red", "orange", "yellow", "green", "cyan", "blue", "purple", "pink"};

    /** Tabs opened this session before the server's echo of {@link HubSettings#SEEN_TABS} lands. */
    private static final Set<HubTab> SEEN_NOW = EnumSet.noneOf(HubTab.class);

    private HubNotifications() {
    }

    public static void reset() {
        SEEN_NOW.clear();
    }

    public static boolean enabled(Kind kind) {
        HubPayloads.SettingsData settings = HubState.settingsData;
        return settings == null || (settings.notifyOff() & (1 << kind.ordinal())) == 0;
    }

    public static HubSettings.NotifyStyle style() {
        HubPayloads.SettingsData settings = HubState.settingsData;
        return settings == null ? HubSettings.NotifyStyle.BADGE : HubSettings.NotifyStyle.of(settings.notifyStyle());
    }

    /** The badge / glow color: the chosen one, else the theme's. */
    public static int color() {
        HubPayloads.SettingsData settings = HubState.settingsData;
        int choice = settings == null ? 0 : settings.notifyColor();
        return choice >= 1 && choice <= COLORS.length ? COLORS[choice - 1] : EdenParchment.NOTIFY;
    }

    /** What's waiting on this tab (0 = nothing, or that kind is off). */
    public static int count(HubTab tab) {
        return switch (tab) {
            case HOME -> enabled(Kind.SHOP) && HubState.homeData != null ? HubState.homeData.shopPending() : 0;
            case MAIL -> enabled(Kind.MAIL) && HubState.mailData != null ? HubState.mailData.unread() : 0;
            case FRIENDS -> enabled(Kind.FRIEND_REQUESTS) && HubState.friendsAvailable() ? HubState.friendRequestCount() : 0;
            case PARTY -> enabled(Kind.PARTY_INVITES) ? HubState.partyInvites() : 0;
            case GUILD -> enabled(Kind.GUILD_INVITES) ? HubState.guildInvites() : 0;
            case DAILIES -> enabled(Kind.DAILIES) ? HubState.dailiesClaimable() : 0;
            case ACTIVITIES -> enabled(Kind.ACTIVITIES) ? HubState.activityUnlocks.size() : 0;
            default -> 0;
        };
    }

    /** A tab the player has never opened (once settings are known; Home opens first anyway). */
    public static boolean isNew(HubTab tab) {
        HubPayloads.SettingsData settings = HubState.settingsData;
        if (settings == null || !enabled(Kind.NEW_TABS) || tab == HubTab.HOME || SEEN_NOW.contains(tab)) {
            return false;
        }
        return (settings.seenTabs() & (1 << tab.bit)) == 0;
    }

    /** The tab was opened: it stops being new, here at once and on the server for next time. */
    public static void markSeen(HubTab tab) {
        HubPayloads.SettingsData settings = HubState.settingsData;
        if (settings == null || (settings.seenTabs() & (1 << tab.bit)) != 0 || !SEEN_NOW.add(tab)) {
            return;
        }
        int mask = settings.seenTabs();
        for (HubTab seen : SEEN_NOW) {
            mask |= 1 << seen.bit;
        }
        ClientPlayNetworking.send(new HubPayloads.SettingChange(HubSettings.SEEN_TABS, mask));
    }

    /** Turns a kind on or off (Settings → Notifications). */
    public static void setEnabled(Kind kind, boolean on) {
        HubPayloads.SettingsData settings = HubState.settingsData;
        int mask = settings == null ? 0 : settings.notifyOff();
        mask = on ? mask & ~(1 << kind.ordinal()) : mask | (1 << kind.ordinal());
        ClientPlayNetworking.send(new HubPayloads.SettingChange(HubSettings.NOTIFY_OFF, mask));
    }
}
