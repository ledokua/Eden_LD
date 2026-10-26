package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.chat.ChatFlagBits;
import net.ledok.eden_ld.hub.HubPayloads;

/**
 * Client-session hub state. {@link #lastTab} makes the hub reopen where the player
 * left it; both fields reset on server join, so a fresh session lands on Home.
 */
public final class HubState {

    public static HubTab lastTab = HubTab.HOME;
    public static HubPayloads.HomeData homeData;
    public static HubPayloads.PartyData partyData;
    public static HubPayloads.FriendsData friendsData;
    public static HubPayloads.SettingsData settingsData;
    /** The chat switches ({@link HubPayloads.ChatFlags}); -1 until the server says (all on, none known). */
    public static int chatFlags = ChatFlagBits.UNSENT;
    /** The Activities tab's cards (dungeons, raids, arenas). */
    public static HubPayloads.ActivitiesData activitiesData;
    /** Activities closed for maintenance (dimmed, not clickable), and whether this player may enter them anyway (staff). */
    public static java.util.Set<String> closedActivities = java.util.Set.of();
    public static boolean closedBypass;
    public static HubPayloads.DuelsData duelsData;
    public static HubPayloads.WardrobeData wardrobeData;
    /** The player's own duel queue (the Home card), and when it arrived. */
    public static HubPayloads.DuelsStatus duelsStatus;
    public static long duelsStatusAt;
    public static long duelsReceivedAt;
    /**
     * Wager duels (Activities → Duels, Duels_LD 1.6.0+): the last header (null = this server has none, so no Duels
     * filter), and each page's last data with when it came (its countdowns run from there).
     */
    public static HubPayloads.WagerData wagerHeader;
    public static java.util.List<HubPayloads.WagerListingView> wagerListings;
    public static long wagerListingsAt;
    public static HubPayloads.WagerMineView wagerMine;
    public static long wagerMineAt;
    public static java.util.List<HubPayloads.WagerRecordView> wagerHistory;
    /** Activities the player's level opened that they haven't seen yet: the tab badge and a Home card. */
    public static java.util.List<net.minecraft.network.chat.Component> activityUnlocks = java.util.List.of();
    public static HubPayloads.DailiesData dailiesData;
    /** When {@link #dailiesData} arrived; the reset timer counts down from here. */
    public static long dailiesReceivedAt;
    /** Kits_LD's kits for this player (null: the server has no Kits_LD, so no Kits tab) and when they came. */
    public static HubPayloads.KitsData kitsData;
    public static long kitsReceivedAt;
    /** A Settings section to open the next time the Settings tab builds (the chat channel menu's "Chat settings"). */
    @org.jetbrains.annotations.Nullable
    public static String openSettingsSection;
    public static HubPayloads.WaystonesData waystonesData;
    /** When {@link #waystonesData} arrived; its timers count down from here. */
    public static long waystonesReceivedAt;
    /** Last refused teleport's reason, shown briefly on the Waystones tab's status line. */
    public static net.minecraft.network.chat.Component waystoneMessage;
    public static long waystoneMessageAt;
    /** Party chat lines for the tab panel, oldest first. */
    public static final java.util.List<HubPayloads.ChatEntry> partyChat = new java.util.ArrayList<>();
    public static HubPayloads.GuildData guildData;
    /** Last guild bank data (only while the bank view is/was open). */
    public static HubPayloads.BankData bankData;
    /** The Guild tab's open sub-tab. */
    static GuildView guildView = GuildView.MEMBERS;
    /** The guild's level and quest board (sub-tabs Quests and Level, the header's XP bar). */
    public static HubPayloads.GuildQuestsData guildQuests;
    public static long guildQuestsReceivedAt;
    /** Guild chat lines for the tab panel, oldest first. */
    public static final java.util.List<HubPayloads.ChatEntry> guildChat = new java.util.ArrayList<>();
    /** Whose profile the Profile tab shows: null = the player's own. */
    public static java.util.UUID profileTarget;
    /** The last profile page that arrived (for {@link #profileTarget}). */
    public static HubPayloads.ProfileData profileData;
    /** The player's mailbox; null until the server sends it (no Economy_LD: never, and no Mail tab). */
    public static HubPayloads.MailData mailData;
    /** Who the player ignores (uuid → last known name), in the order they were added. */
    public static java.util.Map<java.util.UUID, String> ignored = java.util.Map.of();

    private HubState() {
    }

    public static void resetOnJoin() {
        lastTab = HubTab.HOME;
        HubNotifications.reset();
        mailData = null;
        homeData = null;
        partyData = null;
        friendsData = null;
        settingsData = null;
        chatFlags = ChatFlagBits.UNSENT;
        activitiesData = null;
        closedActivities = java.util.Set.of();
        closedBypass = false;
        duelsData = null;
        wardrobeData = null;
        duelsStatus = null;
        wagerHeader = null;
        wagerListings = null;
        wagerMine = null;
        wagerHistory = null;
        activityUnlocks = java.util.List.of();
        waystonesData = null;
        dailiesData = null;
        kitsData = null;
        waystoneMessage = null;
        partyChat.clear();
        guildData = null;
        guildChat.clear();
        bankData = null;
        guildView = GuildView.MEMBERS;
        guildQuests = null;
        profileTarget = null;
        profileData = null;
        ignored = java.util.Map.of();
    }

    /** A chat switch ({@link HubPayloads.ChatFlags} bit): as the server sent it, or on when it doesn't know it. */
    public static boolean chatFlag(int bit) {
        return ChatFlagBits.on(chatFlags, bit);
    }

    /** Whether the server knows the chat switch (it keeps the player's choice, so the Settings tab offers it). */
    public static boolean chatFlagKnown(int bit) {
        return ChatFlagBits.known(chatFlags, bit);
    }

    /** Eden's new chat look (on unless the player turned it off). */
    public static boolean chatNewLook() {
        return chatFlag(HubPayloads.ChatFlags.NEW_LOOK);
    }

    /** The closed chat's lines as rounded strips (on unless the player picked Classic; drawn with the new look only). */
    public static boolean chatHudStrips() {
        return chatFlag(HubPayloads.ChatFlags.HUD_STRIPS);
    }

    /** The new look's channel tabs show the server's and other mods' lines too (on unless the player turned it off). */
    public static boolean chatSystemInTabs() {
        return chatFlag(HubPayloads.ChatFlags.SYSTEM_IN_TABS);
    }

    /** The new look's All tab (on unless the player hid it). */
    public static boolean chatAllTab() {
        return chatFlag(HubPayloads.ChatFlags.ALL_TAB);
    }

    /** Whether the player ignores {@code uuid}: their lines hide, and lists put them last. */
    public static boolean ignores(java.util.UUID uuid) {
        return uuid != null && ignored.containsKey(uuid);
    }

    public static void onIgnoreData(HubPayloads.IgnoreData data) {
        java.util.Map<java.util.UUID, String> map = new java.util.LinkedHashMap<>();
        data.ignored().forEach(entry -> map.put(entry.uuid(), entry.name()));
        ignored = java.util.Collections.unmodifiableMap(map);
    }

    /** The uuid whose profile the Profile tab wants: {@link #profileTarget}, else the player's own. */
    public static java.util.UUID profileSubject() {
        if (profileTarget != null) {
            return profileTarget;
        }
        var player = net.minecraft.client.Minecraft.getInstance().player;
        return player == null ? null : player.getUUID();
    }

    /** Live daily quests ready to claim: pushed dailies data first, the Home snapshot until then. */
    public static int dailiesClaimable() {
        if (dailiesData != null) {
            return (int) dailiesData.quests().stream()
                    .filter(q -> !q.claimed() && q.progress() >= q.count()).count();
        }
        return homeData != null ? homeData.dailiesClaimable() : 0;
    }

    /** {claimed, total} for the Home mini progress line. */
    public static int[] dailiesProgress() {
        if (dailiesData != null) {
            int claimed = (int) dailiesData.quests().stream().filter(HubPayloads.DailyEntry::claimed).count();
            return new int[]{claimed, dailiesData.quests().size()};
        }
        return homeData != null ? new int[]{homeData.dailiesClaimed(), homeData.dailiesTotal()} : new int[]{0, 0};
    }

    /** Hidden until the server says Blay's Waystones is present (Home data carries the flag). */
    /** The wait left on a kit now (its data counts down from when it came): 0 ready, -1 claimed once. */
    public static long kitRemaining(HubPayloads.KitView kit) {
        if (kit.remainingMillis() <= 0) {
            return kit.remainingMillis();
        }
        return Math.max(0, kit.remainingMillis() - (System.currentTimeMillis() - kitsReceivedAt));
    }

    /** Kits that can be claimed right now (the Kits tab's badge). */
    public static int kitsReady() {
        if (kitsData == null) {
            return 0;
        }
        return (int) kitsData.kits().stream().filter(kit -> !kit.locked() && kitRemaining(kit) == 0).count();
    }

    public static boolean waystonesAvailable() {
        return homeData != null && homeData.waystonesAvailable();
    }

    /** Hidden until the server says Minestar is present (Home data carries the flag). */
    public static boolean friendsAvailable() {
        return homeData != null && homeData.friendsAvailable();
    }

    /** Live incoming friend request count: pushed friends data first, the Home snapshot until then. */
    public static int friendRequestCount() {
        if (friendsData != null) {
            // Requests from ignored players wait in the panel without counting.
            return (int) friendsData.incoming().stream().filter(request -> !ignores(request.uuid())).count();
        }
        return homeData != null ? homeData.friendRequests() : 0;
    }

    public static void onGuildData(HubPayloads.GuildData data) {
        guildData = data;
        guildChat.clear();
        guildChat.addAll(data.chat());
    }

    /** Live party invites for the player: pushed party data first, the Home snapshot until then. */
    public static int partyInvites() {
        if (partyData != null) {
            return partyData.myInvites().size();
        }
        return homeData != null ? homeData.partyInvites() : 0;
    }

    /** Live guild invite count for the Home card. */
    public static int guildInvites() {
        return guildData != null && !guildData.inGuild() ? guildData.myInvites().size() : 0;
    }

    public static void onPartyData(HubPayloads.PartyData data) {
        partyData = data;
        partyChat.clear();
        partyChat.addAll(data.chatHistory());
    }
}
