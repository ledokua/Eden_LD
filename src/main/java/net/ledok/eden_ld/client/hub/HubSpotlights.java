package net.ledok.eden_ld.client.hub;

import net.ledok.vectorlib.client.presentation.Spotlight;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Names for the Hub's parts, so tutorials (NPCs_LD dialog lines, VectorLib {@link Spotlight}) can point at them and
 * wait for them: the tab row, every tab, the page area and each tab's page (present only while that tab is open), the
 * help button and window (and its ▶ tour button), maximize and close, the balance on the Home page, the Hub and PvP
 * buttons in the inventory, and parts inside the tabs for their tours. Ids are stable — dialogs store them.
 */
public final class HubSpotlights {
    private HubSpotlights() {}

    public static final String PREFIX = "eden_ld:hub/";
    public static final String TABS = PREFIX + "tabs";
    public static final String PAGE = PREFIX + "page";
    public static final String HELP = PREFIX + "help";
    public static final String HELP_WINDOW = PREFIX + "help_window";
    public static final String MAXIMIZE = PREFIX + "maximize";
    public static final String CLOSE = PREFIX + "close";
    /** The player's balance on the Home page's card (only where the server runs an economy). */
    public static final String BALANCE = PREFIX + "balance";
    /** The Hub button in the player's inventory ({@link InventoryHubButton}), found through a VectorLib locator. */
    public static final String INVENTORY_BUTTON = "eden_ld:inventory/hub_button";
    /** The PvP toggle right of it ({@link InventoryPvpButton}; only where the server runs the toggle). */
    public static final String INVENTORY_PVP_BUTTON = "eden_ld:inventory/pvp_button";
    /** The help window's ▶ button: starts the open tab's tour (only when the tab has one). */
    public static final String HELP_TOUR = PREFIX + "help_tour";

    // Parts inside the tabs, for the tab tours — present only while their tab (and state) shows them.
    /** Guild, no guild yet: the create form (folded away by {@link #GUILD_CREATE_TOGGLE}). */
    public static final String GUILD_CREATE = PREFIX + "guild/create";
    public static final String GUILD_CREATE_BUTTON = PREFIX + "guild/create_button";
    /** The bar left of the directory that folds the create form. */
    public static final String GUILD_CREATE_TOGGLE = PREFIX + "guild/create_toggle";
    public static final String GUILD_DIRECTORY = PREFIX + "guild/directory";
    public static final String GUILD_SEARCH = PREFIX + "guild/search";
    /** The bar right of the directory that opens the invites (with their count). */
    public static final String GUILD_INVITES_TOGGLE = PREFIX + "guild/invites_toggle";
    /** The invites panel (only while open). */
    public static final String GUILD_INVITES = PREFIX + "guild/invites";
    /** Guild, in a guild: the sub-tabs (Members, Recruiting, Quests, Level, Bank). */
    public static final String GUILD_SUBTABS = PREFIX + "guild/subtabs";
    /** The guild's level and XP bar in its header. */
    public static final String GUILD_XP = PREFIX + "guild/xp";
    /** Guild settings (officers and up). */
    public static final String GUILD_SETTINGS = PREFIX + "guild/settings";
    /** Members: the roster. */
    public static final String GUILD_ROSTER = PREFIX + "guild/roster";
    /** Members: the guild chat. */
    public static final String GUILD_CHAT = PREFIX + "guild/chat";
    public static final String WAYSTONES_SEARCH = PREFIX + "waystones/search";
    public static final String WAYSTONES_FAVORITES = PREFIX + "waystones/favorites";
    public static final String WAYSTONES_ALL = PREFIX + "waystones/all";
    /** The line under the lists: cooldown or PvP countdown. */
    public static final String WAYSTONES_STATUS = PREFIX + "waystones/status";
    /** The first waystone row's star (favorite) and Teleport button. */
    public static final String WAYSTONES_STAR = PREFIX + "waystones/star";
    public static final String WAYSTONES_TELEPORT = PREFIX + "waystones/teleport";
    /** All · Dungeons · Raids · Arenas · Ranked PvP · Duels. */
    public static final String ACTIVITIES_FILTERS = PREFIX + "activities/filters";
    /** "Your level" (only when activities are locked by level). */
    public static final String ACTIVITIES_LEVEL = PREFIX + "activities/level";
    /** The sort buttons and the order arrow. */
    public static final String ACTIVITIES_SORT = PREFIX + "activities/sort";
    public static final String ACTIVITIES_CARDS = PREFIX + "activities/cards";
    /** The first activity card. */
    public static final String ACTIVITIES_CARD = PREFIX + "activities/card";
    /** All · Unread · With items. */
    public static final String MAIL_FILTERS = PREFIX + "mail/filters";
    public static final String MAIL_LIST = PREFIX + "mail/list";
    /** The first website purchase row (only when one waits). */
    public static final String MAIL_PURCHASE = PREFIX + "mail/purchase";
    /** The first mail row. */
    public static final String MAIL_ROW = PREFIX + "mail/row";
    public static final String MAIL_CLAIM_ALL = PREFIX + "mail/claim_all";
    public static final String MAIL_DELETE_READ = PREFIX + "mail/delete_read";
    /** The open mail (right). */
    public static final String MAIL_DETAIL = PREFIX + "mail/detail";
    /** The open mail's or purchase's Claim button (only while it holds something). */
    public static final String MAIL_CLAIM = PREFIX + "mail/claim";
    /** The wallet bar: balance, amount, Withdraw, Deposit all. */
    public static final String MAIL_WALLET = PREFIX + "mail/wallet";
    public static final String MAIL_WITHDRAW = PREFIX + "mail/withdraw";
    public static final String MAIL_DEPOSIT_ALL = PREFIX + "mail/deposit_all";
    // Guild sub-tabs (in a guild).
    /** Members: the card under the roster (the picked member's options). */
    public static final String GUILD_MEMBER_CARD = PREFIX + "guild/member_card";
    /** Recruiting (officers): invite players (search + list), applications, invites waiting for an answer. */
    public static final String GUILD_RECRUIT_INVITE = PREFIX + "guild/recruit_invite";
    public static final String GUILD_APPLICATIONS = PREFIX + "guild/applications";
    public static final String GUILD_PENDING_INVITES = PREFIX + "guild/pending_invites";
    /** Quests: the daily and weekly guild quests. */
    public static final String GUILD_QUESTS = PREFIX + "guild/quests";
    /** Level: the level and XP card, and the perks card. */
    public static final String GUILD_LEVEL = PREFIX + "guild/level";
    public static final String GUILD_PERKS = PREFIX + "guild/perks";
    /** Bank: LC balance, vault tabs, upgrades, the vault grid, the player's inventory, and the Log button. */
    public static final String GUILD_BANK_BALANCE = PREFIX + "guild/bank_balance";
    public static final String GUILD_BANK_TABS = PREFIX + "guild/bank_tabs";
    public static final String GUILD_BANK_UPGRADES = PREFIX + "guild/bank_upgrades";
    public static final String GUILD_BANK_VAULT = PREFIX + "guild/bank_vault";
    public static final String GUILD_BANK_INVENTORY = PREFIX + "guild/bank_inventory";
    public static final String GUILD_BANK_LOG = PREFIX + "guild/bank_log";
    // Activities → Ranked PvP (the ids keep their old "duels" name: tutorials store them).
    public static final String DUELS_CARD_1V1 = PREFIX + "duels/card_1v1";
    public static final String DUELS_CARD_2V2 = PREFIX + "duels/card_2v2";
    public static final String DUELS_QUEUE_1V1 = PREFIX + "duels/queue_1v1";
    public static final String DUELS_QUEUE_2V2 = PREFIX + "duels/queue_2v2";
    /** Today's duel rewards bar (only where duels pay rewards). */
    public static final String DUELS_REWARDS = PREFIX + "duels/rewards";
    /** Matches · Top 1v1 · Top 2v2. */
    public static final String DUELS_VIEWS = PREFIX + "duels/views";
    public static final String DUELS_LIST = PREFIX + "duels/list";
    // Activities → Duels (wager duels, Duels_LD 1.6.0+).
    /** Announcements · My duels · History. */
    public static final String WAGERS_PAGES = PREFIX + "wagers/pages";
    /** Create duel (disabled until Duels_LD can open its stake screen from the hub). */
    public static final String WAGERS_CREATE = PREFIX + "wagers/create";
    /** The open page's list. */
    public static final String WAGERS_LIST = PREFIX + "wagers/list";
    // Kits (Kits_LD): the list, the first card, the first Claim that can be pressed, the first Preview.
    public static final String KITS_LIST = PREFIX + "kits/list";
    public static final String KITS_FIRST = PREFIX + "kits/first";
    public static final String KITS_CLAIM = PREFIX + "kits/claim";
    public static final String KITS_PREVIEW = PREFIX + "kits/preview";
    /** The open Preview dialog. */
    public static final String KITS_PREVIEW_WINDOW = PREFIX + "kits/preview_window";
    // Home: the player's card (level, balance, dailies), the "needs you now" cards ("all caught up" without any), news.
    public static final String HOME_CARD = PREFIX + "home/card";
    public static final String HOME_ACTIONS = PREFIX + "home/actions";
    public static final String HOME_NEWS = PREFIX + "home/news";
    // Profile (Minestar profiles; level and track need the platform, the track only on the player's own page).
    public static final String PROFILE_PLAYTIME = PREFIX + "profile/playtime";
    public static final String PROFILE_LEVEL = PREFIX + "profile/level";
    public static final String PROFILE_REWARDS = PREFIX + "profile/rewards";
    /** What the level gives, what's next and the track of perk levels. */
    public static final String PROFILE_TRACK = PREFIX + "profile/track";
    // Wardrobe: Backgrounds · Themes, All · Owned · Shop, the looks, the first look.
    public static final String WARDROBE_CATEGORIES = PREFIX + "wardrobe/categories";
    public static final String WARDROBE_FILTERS = PREFIX + "wardrobe/filters";
    public static final String WARDROBE_GRID = PREFIX + "wardrobe/grid";
    public static final String WARDROBE_FIRST = PREFIX + "wardrobe/first";
    // Friends: the side panels' bars stand in for them while folded.
    public static final String FRIENDS_LIST = PREFIX + "friends/list";
    public static final String FRIENDS_SEARCH = PREFIX + "friends/search";
    /** "On this server" (left), or the bar that opens it. */
    public static final String FRIENDS_HERE = PREFIX + "friends/here";
    /** The requests panel (right), or the bar that opens it. */
    public static final String FRIENDS_REQUESTS = PREFIX + "friends/requests";
    // Party: inviting (the button without a party, the invites panel in one); members and chat only in a party.
    public static final String PARTY_INVITE = PREFIX + "party/invite";
    public static final String PARTY_MEMBERS = PREFIX + "party/members";
    public static final String PARTY_CHAT = PREFIX + "party/chat";
    // Dailies: the renew time in the header, the quests, the first ↻, the day's bonus.
    public static final String DAILIES_RESET = PREFIX + "dailies/reset";
    public static final String DAILIES_LIST = PREFIX + "dailies/list";
    public static final String DAILIES_REROLL = PREFIX + "dailies/reroll";
    public static final String DAILIES_BONUS = PREFIX + "dailies/bonus";
    // Settings: Privacy · Chat · Notifications · Visual, and the open section's options (found by a locator).
    public static final String SETTINGS_SECTIONS = PREFIX + "settings/sections";
    public static final String SETTINGS_LIST = PREFIX + "settings/list";

    /** The tab's bookmark in the tab row. */
    public static String tab(HubTab tab) {
        return PREFIX + "tab/" + key(tab);
    }

    /** The tab's page: the content under the page title while that tab is open. */
    public static String page(HubTab tab) {
        return PREFIX + "page/" + key(tab);
    }

    private static String key(HubTab tab) {
        return tab.name().toLowerCase(Locale.ROOT);
    }

    /** Lists the parts in VectorLib's catalog, for editors' pickers. Client init. */
    public static void register() {
        Spotlight.register(TABS, Component.literal("Hub: tab row (every page)"));
        for (HubTab tab : HubTab.values()) {
            Spotlight.register(tab(tab), Component.literal("Hub: " + label(tab) + " tab"));
        }
        Spotlight.register(PAGE, Component.literal("Hub: page area"));
        for (HubTab tab : HubTab.values()) {
            Spotlight.register(page(tab), Component.literal("Hub: " + label(tab) + " page (shown while open)"));
        }
        Spotlight.register(HELP, Component.literal("Hub: ? help button"));
        Spotlight.register(HELP_WINDOW, Component.literal("Hub: help window"));
        Spotlight.register(MAXIMIZE, Component.literal("Hub: maximize button"));
        Spotlight.register(CLOSE, Component.literal("Hub: close button"));
        Spotlight.register(BALANCE, Component.literal("Hub: balance on the Home page"));
        Spotlight.register(INVENTORY_BUTTON, Component.literal("Inventory: Hub button"));
        Spotlight.register(INVENTORY_PVP_BUTTON, Component.literal("Inventory: PvP button"));
        Spotlight.register(HELP_TOUR, Component.literal("Hub: help window's ▶ tour button"));
        String[][] parts = {
                {GUILD_CREATE, "Guild (no guild): create form"}, {GUILD_CREATE_BUTTON, "Guild (no guild): Create button"},
                {GUILD_CREATE_TOGGLE, "Guild (no guild): create form fold bar"}, {GUILD_DIRECTORY, "Guild (no guild): directory"},
                {GUILD_SEARCH, "Guild (no guild): directory search"}, {GUILD_INVITES_TOGGLE, "Guild (no guild): invites bar"},
                {GUILD_INVITES, "Guild (no guild): invites panel"}, {GUILD_SUBTABS, "Guild: sub-tabs"},
                {GUILD_XP, "Guild: level and XP"}, {GUILD_SETTINGS, "Guild: settings (officers)"},
                {GUILD_ROSTER, "Guild: members"}, {GUILD_CHAT, "Guild: chat"},
                {WAYSTONES_SEARCH, "Waystones: search"}, {WAYSTONES_FAVORITES, "Waystones: favorites"},
                {WAYSTONES_ALL, "Waystones: all"}, {WAYSTONES_STATUS, "Waystones: cooldown line"},
                {WAYSTONES_STAR, "Waystones: first row's star"}, {WAYSTONES_TELEPORT, "Waystones: first row's Teleport"},
                {ACTIVITIES_FILTERS, "Activities: filters"}, {ACTIVITIES_LEVEL, "Activities: your level"},
                {ACTIVITIES_SORT, "Activities: sorting"}, {ACTIVITIES_CARDS, "Activities: cards"},
                {ACTIVITIES_CARD, "Activities: first card"}, {MAIL_FILTERS, "Mail: filters"},
                {MAIL_LIST, "Mail: list"}, {MAIL_PURCHASE, "Mail: first website purchase"},
                {MAIL_ROW, "Mail: first mail"}, {MAIL_CLAIM_ALL, "Mail: Claim all"},
                {MAIL_DELETE_READ, "Mail: Delete read"}, {MAIL_DETAIL, "Mail: open mail"},
                {MAIL_CLAIM, "Mail: Claim (open mail)"}, {MAIL_WALLET, "Mail: wallet bar"},
                {MAIL_WITHDRAW, "Mail: Withdraw"}, {MAIL_DEPOSIT_ALL, "Mail: Deposit all"},
                {GUILD_MEMBER_CARD, "Guild Members: member card"}, {GUILD_RECRUIT_INVITE, "Guild Recruiting: invite players"},
                {GUILD_APPLICATIONS, "Guild Recruiting: applications"}, {GUILD_PENDING_INVITES, "Guild Recruiting: sent invites"},
                {GUILD_QUESTS, "Guild Quests: quest list"}, {GUILD_LEVEL, "Guild Level: level card"},
                {GUILD_PERKS, "Guild Level: perks"}, {GUILD_BANK_BALANCE, "Guild Bank: LC balance"},
                {GUILD_BANK_TABS, "Guild Bank: vault tabs"}, {GUILD_BANK_UPGRADES, "Guild Bank: upgrades"},
                {GUILD_BANK_VAULT, "Guild Bank: vault"}, {GUILD_BANK_INVENTORY, "Guild Bank: your inventory"},
                {GUILD_BANK_LOG, "Guild Bank: Log"}, {DUELS_CARD_1V1, "Ranked PvP: 1v1 card"},
                {DUELS_CARD_2V2, "Ranked PvP: 2v2 card"}, {DUELS_QUEUE_1V1, "Ranked PvP: 1v1 queue button"},
                {DUELS_QUEUE_2V2, "Ranked PvP: 2v2 queue button"}, {DUELS_REWARDS, "Ranked PvP: today's rewards"},
                {DUELS_VIEWS, "Ranked PvP: Matches / Top"}, {DUELS_LIST, "Ranked PvP: list"},
                {WAGERS_PAGES, "Duels: Announcements / My duels / History"}, {WAGERS_CREATE, "Duels: Create duel"},
                {WAGERS_LIST, "Duels: list"},
                {KITS_LIST, "Kits: list"}, {KITS_FIRST, "Kits: first kit"},
                {KITS_CLAIM, "Kits: first Claim that can be pressed"}, {KITS_PREVIEW, "Kits: first Preview"},
                {KITS_PREVIEW_WINDOW, "Kits: Preview window"}, {HOME_CARD, "Home: player card"},
                {HOME_ACTIONS, "Home: cards that need you"}, {HOME_NEWS, "Home: news"},
                {PROFILE_PLAYTIME, "Profile: playtime"}, {PROFILE_LEVEL, "Profile: level"},
                {PROFILE_REWARDS, "Profile: rewards"}, {PROFILE_TRACK, "Profile: level perks track (own page)"},
                {WARDROBE_CATEGORIES, "Wardrobe: categories"}, {WARDROBE_FILTERS, "Wardrobe: All / Owned / Shop"},
                {WARDROBE_GRID, "Wardrobe: looks"}, {WARDROBE_FIRST, "Wardrobe: first look"},
                {FRIENDS_LIST, "Friends: friends list"}, {FRIENDS_SEARCH, "Friends: friends search"},
                {FRIENDS_HERE, "Friends: on this server (or its bar)"}, {FRIENDS_REQUESTS, "Friends: requests (or their bar)"},
                {PARTY_INVITE, "Party: inviting"}, {PARTY_MEMBERS, "Party: members"}, {PARTY_CHAT, "Party: chat"},
                {DAILIES_RESET, "Dailies: renew time"}, {DAILIES_LIST, "Dailies: quests"},
                {DAILIES_REROLL, "Dailies: first reroll"}, {DAILIES_BONUS, "Dailies: bonus"},
                {SETTINGS_SECTIONS, "Settings: sections"}, {SETTINGS_LIST, "Settings: options"}};
        for (String[] part : parts) {
            Spotlight.register(part[0], Component.literal("Hub: " + part[1]));
        }
        SettingsTab.registerLocator();
    }

    private static String label(HubTab tab) {
        String n = key(tab).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
