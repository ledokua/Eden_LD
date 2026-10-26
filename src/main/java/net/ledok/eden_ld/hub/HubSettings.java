package net.ledok.eden_ld.hub;

import net.ledok.eden_ld.chat.ChatFlagBits;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Per-player hub preferences set on the Settings tab, persisted in {@link HubData}
 * under the player's {@code settings} sub-tag. Rules about others (who may invite you, …) are
 * enforced server side; what the player's own chat shows (hidden channels, deaths, …) is
 * applied by their client, which gets every line — turning a kind back on shows it again.
 */
public final class HubSettings {

    private static final String TAG = "settings";
    public static final String PARTY_INVITES_FROM = "party_invites_from";
    public static final String THEME = "theme";
    public static final String SHOW_GUILD_TAG = "show_guild_tag";
    /** The player turned the theme's own icons off (Settings → Visual); default: on. */
    public static final String THEME_ICONS_OFF = "theme_icons_off";
    /** The chat channel the player types into (ChatChannels.Channel ordinal). */
    public static final String CHAT_CHANNEL = "chat_channel";
    /** Whose death / advancement messages the player sees in chat ({@link MessagesFrom} ordinal). */
    public static final String CHAT_DEATHS = "chat_deaths";
    public static final String CHAT_ADVANCEMENTS = "chat_advancements";
    /** Channels whose messages the player doesn't want in their chat (their own lines always show). */
    public static final String CHAT_HIDE_GLOBAL = "chat_hide_global";
    public static final String CHAT_HIDE_LOCAL = "chat_hide_local";
    public static final String CHAT_HIDE_PARTY = "chat_hide_party";
    public static final String CHAT_HIDE_GUILD = "chat_hide_guild";
    public static final String CHAT_HIDE_DISCORD = "chat_hide_discord";
    /**
     * Only the channel the player types into shows in their chat (Discord counts as Global). Read by the 1.2 look
     * ({@link #CHAT_NEW_LOOK} off) and 1.2.x clients only — the new look's tab strip is its view; goes with the
     * {@code chat_new_look} hatch (the {@code SettingsData} field stays: 1.2.x wire).
     */
    public static final String CHAT_FOCUS = "chat_focus";
    /**
     * How loud the ping is when someone @mentions the player, 0-100 (0 = no ping). The client
     * plays it apart from the game's sound sliders (see the MentionPing payload).
     */
    public static final String MENTION_VOLUME = "mention_volume";
    public static final int DEFAULT_MENTION_VOLUME = 90;
    private static final java.util.List<String> HIDE_KEYS = java.util.List.of(
            CHAT_HIDE_GLOBAL, CHAT_HIDE_LOCAL, CHAT_HIDE_PARTY, CHAT_HIDE_GUILD, CHAT_HIDE_DISCORD);
    /**
     * Each chat channel's notifications ({@link ChannelNotify} ordinal), 2 bits per channel in
     * {@code ChatChannels.Channel} order: what plays a sound and counts on the channel's pip.
     */
    public static final String CHAT_NOTIFY = "chat_notify";
    /**
     * A muted chat channel: no dot on its pip, no "all messages" sound (mentions still count). Stored as the end in
     * epoch minutes, {@link #MUTED_FOR_GOOD} until turned back on, 0 not muted; the client sends the length instead
     * (minutes, -1 for good, 0 to unmute).
     */
    public static final java.util.List<String> CHAT_MUTE = java.util.List.of(
            "chat_mute_global", "chat_mute_local", "chat_mute_party", "chat_mute_guild");
    public static final int MUTED_FOR_GOOD = -1;
    /**
     * Eden's new chat look (rounded panels in the hub theme's colors, motion); off = the 1.2.x chat. An escape hatch,
     * on by default — remove it two releases after it ships.
     */
    public static final String CHAT_NEW_LOOK = "chat_new_look";
    /** How the closed chat's lines look with the new look: on = a rounded strip per message, off = the classic band. */
    public static final String CHAT_HUD_STYLE = "chat_hud_style";
    /**
     * The new look's channel tabs also show unmarked lines (the server's, other mods'); All and System always do.
     * Deaths and advancements follow their own switches in every tab.
     */
    public static final String CHAT_SYSTEM_IN_TABS = "chat_system_in_tabs";
    /** The new look's All tab (every channel together, first in the strip); off = no All tab. */
    public static final String CHAT_ALL_TAB = "chat_all_tab";
    /**
     * The chat switches that are on until the player turns them off, and their bits in {@link #chatFlags}. Append
     * only: a client reads them by bit ({@code HubPayloads.ChatFlags}).
     */
    private static final java.util.List<String> CHAT_FLAGS = java.util.List.of(CHAT_NEW_LOOK, CHAT_HUD_STYLE,
            CHAT_SYSTEM_IN_TABS, CHAT_ALL_TAB);
    /**
     * Duels_LD's wager switches (Settings → Privacy → Duels, shown when Duels_LD is installed), read by Duels through
     * {@code EdenSettingsApi.getBoolean}: skip the "you can lose your stake" popup (Duels' ready check always stays),
     * and accept personal {@code /duel <name>} challenges. Stored under these keys as they are (NBT keys take ':' and
     * '.'); unset = the default here.
     */
    public static final String DUELS_SKIP_LOSS_WARNING = "duels_ld:wager.skip_loss_warning";
    public static final String DUELS_PERSONAL_CHALLENGES = "duels_ld:wager.personal_challenges";
    private static final java.util.Map<String, Boolean> DUELS_FLAGS = java.util.Map.of(
            DUELS_SKIP_LOSS_WARNING, false, DUELS_PERSONAL_CHALLENGES, true);
    /**
     * {@link #NOTIFY_OFF}'s bit for unread mail — the client's {@code HubNotifications.Kind.MAIL} ordinal. Off also
     * drops the "unread letters" line on join.
     */
    public static final int NOTIFY_MAIL_BIT = 6;
    /** The longest timed mute the client may ask for (24 hours). */
    private static final int MAX_MUTE_MINUTES = 24 * 60;
    /** How hub tabs show news: badges, a glow, or both ({@link NotifyStyle} ordinal). */
    public static final String NOTIFY_STYLE = "notify_style";
    /** The badge / glow color: 0 = the theme's own, 1..{@link #NOTIFY_COLORS} a fixed color. */
    public static final String NOTIFY_COLOR = "notify_color";
    /** How many fixed notification colors the client offers (besides the theme's). */
    public static final int NOTIFY_COLORS = 8;
    /** Notification kinds the player turned off, as bits (the client's kinds, in order). */
    public static final String NOTIFY_OFF = "notify_off";
    /** Hub tabs the player has opened, as bits (tab order): the others show as new. */
    public static final String SEEN_TABS = "seen_tabs";
    /** Who may open this player's profile ({@link InvitesFrom} ordinal: anyone, friends, no one). */
    public static final String PROFILE_VISIBLE_TO = "profile_visible_to";
    /**
     * The hub's own language ({@link #HUB_LANGUAGES} index): 0 follows the game's language;
     * otherwise the hub (and Eden's server-side texts) use that one while the game keeps its own.
     */
    public static final String HUB_LANGUAGE = "hub_language";
    public static final java.util.List<String> HUB_LANGUAGES = java.util.List.of("", "en_us", "uk_ua");
    /** The profile background the player picked. */
    /**
     * The player's own PvP flag (docs/PLAN-pvp-toggle.md): 1 = on; absent or 0 = off, everyone's default. Only
     * {@code combat.PlayerPvp} writes it ({@link #setPvp}) — never the Settings tab's {@link #set}, which would skip the
     * cooldown and the combat check.
     */
    public static final String PVP = "pvp";
    /** When the player last switched their PvP themselves (epoch seconds; 0 = never): the switch cooldown. */
    public static final String PVP_SWITCHED = "pvp_switched";
    public static final String PROFILE_BACKGROUND = "profile_background";
    /** No background: the profile, Home card and Friends card keep the hub theme's look. */
    public static final String DEFAULT_BACKGROUND = "";
    private static final String UNLOCKED_BACKGROUNDS = "unlocked_backgrounds";
    /** Built-in profile backgrounds, for command suggestions (resource packs may add more). */
    public static final java.util.List<String> BUILT_IN_BACKGROUNDS = java.util.List.of("eden_ld:twilight",
            "eden_ld:parchment", "eden_ld:aurora", "eden_ld:ember", "eden_ld:forest", "eden_ld:ocean",
            "eden_ld:starfall", "eden_ld:blossom");
    public static final String DEFAULT_THEME = "eden_ld:parchment";
    private static final String UNLOCKED_THEMES = "unlocked_themes";
    /** Built-in themes, for command suggestions (servers may add more through resource packs). */
    public static final java.util.List<String> BUILT_IN_THEMES =
            java.util.List.of("eden_ld:parchment", "eden_ld:dark", "eden_ld:stone_tablet");

    /** Who may send this player party invites (and, for the profile, open it). Ordinal is the wire/NBT value. */
    public enum InvitesFrom {
        ANYONE,
        FRIENDS,
        NOBODY;

        public static InvitesFrom of(int value) {
            InvitesFrom[] values = values();
            return value >= 0 && value < values.length ? values[value] : ANYONE;
        }
    }

    /** How a hub tab with news looks. Ordinal is the wire/NBT value. */
    public enum NotifyStyle {
        BADGE,
        GLOW,
        BOTH;

        public static NotifyStyle of(int value) {
            NotifyStyle[] values = values();
            return value >= 0 && value < values.length ? values[value] : BADGE;
        }
    }

    /** A chat channel's notifications (Discord's three). Ordinal is the wire value; ONLY_MENTIONS is the default. */
    public enum ChannelNotify {
        ONLY_MENTIONS,
        ALL_MESSAGES,
        NOTHING;

        /** Made once: the chat's tab strip reads every channel's level every frame. */
        private static final ChannelNotify[] VALUES = values();

        public static ChannelNotify of(int value) {
            return value >= 0 && value < VALUES.length ? VALUES[value] : ONLY_MENTIONS;
        }

        /** This channel's level in the packed setting. */
        public static ChannelNotify of(int packed, int channel) {
            return of(packed >>> (channel * 2) & 3);
        }
    }

    /** Whose messages of a kind (deaths, advancements) a player sees. Ordinal is the wire/NBT value. */
    public enum MessagesFrom {
        EVERYONE,
        /** Their party, their guild, and themselves. */
        TEAM,
        NOBODY;

        public static MessagesFrom of(int value) {
            MessagesFrom[] values = values();
            return value >= 0 && value < values.length ? values[value] : EVERYONE;
        }
    }

    private HubSettings() {
    }

    public static MessagesFrom messagesFrom(MinecraftServer server, UUID player, String key) {
        return MessagesFrom.of(HubData.get(server).peek(player).getCompound(TAG).getInt(key));
    }

    /** Whether the player hid a chat channel (a {@code CHAT_HIDE_*} key). */
    public static boolean hidden(MinecraftServer server, UUID player, String key) {
        return HubData.get(server).peek(player).getCompound(TAG).getBoolean(key);
    }

    /** The hidden channels as bits, in {@code CHAT_HIDE_*} order (global = bit 0 … discord = bit 4). */
    public static int hiddenMask(MinecraftServer server, UUID player) {
        int mask = 0;
        for (int i = 0; i < HIDE_KEYS.size(); i++) {
            if (hidden(server, player, HIDE_KEYS.get(i))) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /** The player's hub language code, or "" to follow their game language. */
    public static String hubLanguage(MinecraftServer server, UUID player) {
        int index = intSetting(server, player, HUB_LANGUAGE);
        return index > 0 && index < HUB_LANGUAGES.size() ? HUB_LANGUAGES.get(index) : "";
    }

    /** The mention ping volume, 0-100; {@link #DEFAULT_MENTION_VOLUME} until the player changes it. */
    public static int mentionVolume(MinecraftServer server, UUID player) {
        CompoundTag settings = HubData.get(server).peek(player).getCompound(TAG);
        return settings.contains(MENTION_VOLUME) ? settings.getInt(MENTION_VOLUME) : DEFAULT_MENTION_VOLUME;
    }

    /** A setting that is on until the player turns it off. */
    public static boolean flag(MinecraftServer server, UUID player, String key) {
        CompoundTag settings = HubData.get(server).peek(player).getCompound(TAG);
        return !settings.contains(key) || settings.getBoolean(key);
    }

    /**
     * The chat switches as bits, in {@code CHAT_FLAGS} order (bit 0 = {@link #CHAT_NEW_LOOK}), with the known half
     * saying which this server has ({@link ChatFlagBits}).
     */
    public static int chatFlags(MinecraftServer server, UUID player) {
        boolean[] values = new boolean[CHAT_FLAGS.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = flag(server, player, CHAT_FLAGS.get(i));
        }
        return ChatFlagBits.pack(values);
    }

    /** A yes/no setting under any key, {@code def} while unset (a number counts: 0 is no). Works for offline players. */
    public static boolean bool(MinecraftServer server, UUID player, String key, boolean def) {
        CompoundTag settings = HubData.get(server).peek(player).getCompound(TAG);
        return settings.contains(key, net.minecraft.nbt.Tag.TAG_ANY_NUMERIC) ? settings.getInt(key) != 0 : def;
    }

    /** Whether {@code key} is one of Duels_LD's wager switches (the keys {@code EdenSettingsApi} may change). */
    public static boolean isDuelsFlag(String key) {
        return DUELS_FLAGS.containsKey(key);
    }

    /** One of Duels_LD's wager switches ({@link #DUELS_SKIP_LOSS_WARNING}, {@link #DUELS_PERSONAL_CHALLENGES}). */
    public static boolean duels(MinecraftServer server, UUID player, String key) {
        return bool(server, player, key, DUELS_FLAGS.getOrDefault(key, false));
    }

    /** A plain int setting (0 when unset): notify style, color, and the bit masks. */
    public static int intSetting(MinecraftServer server, UUID player, String key) {
        return HubData.get(server).peek(player).getCompound(TAG).getInt(key);
    }

    public static InvitesFrom partyInvitesFrom(MinecraftServer server, UUID player) {
        CompoundTag settings = HubData.get(server).peek(player).getCompound(TAG);
        return InvitesFrom.of(settings.getInt(PARTY_INVITES_FROM));
    }

    /** Whether the player shows their guild tag in chat, the tab list, and above their head. */
    public static boolean themeIconsOff(MinecraftServer server, UUID player) {
        return HubData.get(server).peek(player).getCompound(TAG).getBoolean(THEME_ICONS_OFF);
    }

    public static boolean showGuildTag(MinecraftServer server, UUID player) {
        return HubData.get(server).peek(player).getCompound(TAG).getBoolean(SHOW_GUILD_TAG);
    }

    /**
     * The default theme, the ones open to this player ({@link net.ledok.eden_ld.hub.profile.LookRules}: to
     * everyone or their groups), then the ones unlocked for them (given or bought).
     */
    public static java.util.List<String> availableThemes(MinecraftServer server, UUID player) {
        return available(server, player, DEFAULT_THEME,
                net.ledok.eden_ld.hub.profile.LookRules.get(server, net.ledok.eden_ld.hub.profile.LookRules.Kind.THEME)
                        .openFor(player), UNLOCKED_THEMES);
    }

    /** The default, the free ones (config), then the ones unlocked for this player. */
    private static java.util.List<String> available(MinecraftServer server, UUID player, String fallback,
                                                     java.util.List<String> free, String unlockedKey) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        ids.add(fallback);
        ids.addAll(free);
        net.minecraft.nbt.ListTag unlocked = HubData.get(server).peek(player).getCompound(TAG)
                .getList(unlockedKey, net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < unlocked.size(); i++) {
            ids.add(unlocked.getString(i));
        }
        return java.util.List.copyOf(ids);
    }

    // ---------------------------------------------------------------- profile

    /** Who may open the player's profile (themselves always can). */
    public static InvitesFrom profileVisibleTo(MinecraftServer server, UUID player) {
        return InvitesFrom.of(HubData.get(server).peek(player).getCompound(TAG).getInt(PROFILE_VISIBLE_TO));
    }

    /**
     * No background, the ones open to this player ({@link net.ledok.eden_ld.hub.profile.LookRules}:
     * to everyone or their groups), then the ones unlocked for them (given or bought).
     */
    public static java.util.List<String> availableBackgrounds(MinecraftServer server, UUID player) {
        return available(server, player, DEFAULT_BACKGROUND,
                net.ledok.eden_ld.hub.profile.LookRules.get(server, net.ledok.eden_ld.hub.profile.LookRules.Kind.BACKGROUND)
                        .openFor(player), UNLOCKED_BACKGROUNDS);
    }

    /** The player's profile background, if still available to them; else the default. */
    public static String background(MinecraftServer server, UUID player) {
        String chosen = HubData.get(server).peek(player).getCompound(TAG).getString(PROFILE_BACKGROUND);
        return !chosen.isEmpty() && availableBackgrounds(server, player).contains(chosen) ? chosen : DEFAULT_BACKGROUND;
    }

    public static boolean selectBackground(MinecraftServer server, UUID player, String background) {
        if (!availableBackgrounds(server, player).contains(background)) {
            return false;
        }
        settings(server, player).putString(PROFILE_BACKGROUND, background);
        return true;
    }

    /** The backgrounds unlocked for this player alone (given or bought). */
    public static java.util.List<String> unlockedBackgrounds(MinecraftServer server, UUID player) {
        return unlocked(server, player, UNLOCKED_BACKGROUNDS);
    }

    /** The themes unlocked for this player alone. */
    public static java.util.List<String> unlockedThemes(MinecraftServer server, UUID player) {
        return unlocked(server, player, UNLOCKED_THEMES);
    }

    private static java.util.List<String> unlocked(MinecraftServer server, UUID player, String key) {
        net.minecraft.nbt.ListTag list = HubData.get(server).peek(player).getCompound(TAG)
                .getList(key, net.minecraft.nbt.Tag.TAG_STRING);
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ids.add(list.getString(i));
        }
        return ids;
    }

    /** True when the unlock/lock changed anything. */
    public static boolean setBackgroundUnlocked(MinecraftServer server, UUID player, String background, boolean unlocked) {
        return setUnlocked(server, player, UNLOCKED_BACKGROUNDS, background, unlocked);
    }

    /** The player's chosen theme, if still available to them; else the default. */
    public static String theme(MinecraftServer server, UUID player) {
        String chosen = HubData.get(server).peek(player).getCompound(TAG).getString(THEME);
        return !chosen.isEmpty() && availableThemes(server, player).contains(chosen) ? chosen : DEFAULT_THEME;
    }

    public static boolean selectTheme(MinecraftServer server, UUID player, String theme) {
        if (!availableThemes(server, player).contains(theme)) {
            return false;
        }
        settings(server, player).putString(THEME, theme);
        return true;
    }

    /** True when the unlock/lock changed anything. */
    public static boolean setThemeUnlocked(MinecraftServer server, UUID player, String theme, boolean unlocked) {
        return setUnlocked(server, player, UNLOCKED_THEMES, theme, unlocked);
    }

    private static boolean setUnlocked(MinecraftServer server, UUID player, String key, String id, boolean unlocked) {
        CompoundTag settings = settings(server, player);
        net.minecraft.nbt.ListTag list = settings.getList(key, net.minecraft.nbt.Tag.TAG_STRING);
        boolean present = list.stream().anyMatch(tag -> tag.getAsString().equals(id));
        if (present == unlocked) {
            return false;
        }
        if (unlocked) {
            list.add(net.minecraft.nbt.StringTag.valueOf(id));
        } else {
            list.removeIf(tag -> tag.getAsString().equals(id));
        }
        settings.put(key, list);
        return true;
    }

    /** The player's live settings tag (created and marked dirty). */
    public static int chatChannel(MinecraftServer server, UUID player) {
        return HubData.get(server).peek(player).getCompound(TAG).getInt(CHAT_CHANNEL);
    }

    public static void setChatChannel(MinecraftServer server, UUID player, int channel) {
        settings(server, player).putInt(CHAT_CHANNEL, channel);
    }

    /** Writes the player's PvP flag, and the switch time when {@code switchedAt} isn't negative (an admin's change keeps it). */
    public static void setPvp(MinecraftServer server, UUID player, boolean on, long switchedAt) {
        CompoundTag settings = settings(server, player);
        settings.putInt(PVP, on ? 1 : 0);
        if (switchedAt >= 0) {
            settings.putInt(PVP_SWITCHED, (int) switchedAt);
        }
    }

    private static CompoundTag settings(MinecraftServer server, UUID player) {
        CompoundTag root = HubData.get(server).player(player);
        if (!root.contains(TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            root.put(TAG, new CompoundTag());
        }
        return root.getCompound(TAG);
    }

    /** A channel's notifications for this player. */
    public static ChannelNotify channelNotify(MinecraftServer server, UUID player, int channel) {
        return ChannelNotify.of(intSetting(server, player, CHAT_NOTIFY), channel);
    }

    /** When each channel's mute ends (epoch minutes; {@link #MUTED_FOR_GOOD}; 0 not muted), in channel order. */
    public static java.util.List<Integer> chatMutes(MinecraftServer server, UUID player) {
        CompoundTag settings = HubData.get(server).peek(player).getCompound(TAG);
        return CHAT_MUTE.stream().map(settings::getInt).toList();
    }

    /** Applies a change from the Settings tab; false for unknown keys or values. */
    public static boolean set(MinecraftServer server, UUID player, String key, int value) {
        if (CHAT_NOTIFY.equals(key)) {
            // Every channel's 2 bits, each a known level.
            for (int channel = 0; channel < CHAT_MUTE.size(); channel++) {
                if ((value >>> (channel * 2) & 3) >= ChannelNotify.values().length) {
                    return false;
                }
            }
            if (value >>> (CHAT_MUTE.size() * 2) != 0) {
                return false;
            }
            settings(server, player).putInt(key, value);
            return true;
        }
        if (CHAT_MUTE.contains(key)) {
            if (value < MUTED_FOR_GOOD || value > MAX_MUTE_MINUTES) {
                return false;
            }
            int now = (int) (System.currentTimeMillis() / 60_000L);
            settings(server, player).putInt(key, value <= 0 ? value : now + value);
            return true;
        }
        if (MENTION_VOLUME.equals(key)) {
            if (value < 0 || value > 100) {
                return false;
            }
            settings(server, player).putInt(key, value);
            return true;
        }
        if (HIDE_KEYS.contains(key) || CHAT_FOCUS.equals(key)) {
            settings(server, player).putBoolean(key, value != 0);
            return true;
        }
        if (CHAT_DEATHS.equals(key) || CHAT_ADVANCEMENTS.equals(key)) {
            if (value < 0 || value >= MessagesFrom.values().length) {
                return false;
            }
            settings(server, player).putInt(key, value);
            return true;
        }
        if (SHOW_GUILD_TAG.equals(key) || THEME_ICONS_OFF.equals(key)) {
            settings(server, player).putBoolean(key, value != 0);
            return true;
        }
        if (NOTIFY_STYLE.equals(key) || NOTIFY_COLOR.equals(key)) {
            int max = NOTIFY_STYLE.equals(key) ? NotifyStyle.values().length - 1 : NOTIFY_COLORS;
            if (value < 0 || value > max) {
                return false;
            }
            settings(server, player).putInt(key, value);
            return true;
        }
        if (HUB_LANGUAGE.equals(key)) {
            if (value < 0 || value >= HUB_LANGUAGES.size()) {
                return false;
            }
            settings(server, player).putInt(key, value);
            return true;
        }
        if (NOTIFY_OFF.equals(key) || SEEN_TABS.equals(key)) {
            if (value < 0) {
                return false;
            }
            settings(server, player).putInt(key, value);
            return true;
        }
        if (PROFILE_VISIBLE_TO.equals(key)) {
            if (value < 0 || value >= InvitesFrom.values().length) {
                return false;
            }
            settings(server, player).putInt(PROFILE_VISIBLE_TO, value);
            return true;
        }
        if (PARTY_INVITES_FROM.equals(key)) {
            if (value < 0 || value >= InvitesFrom.values().length) {
                return false;
            }
            CompoundTag root = HubData.get(server).player(player);
            CompoundTag settings = root.getCompound(TAG);
            settings.putInt(PARTY_INVITES_FROM, value);
            root.put(TAG, settings);
            return true;
        }
        if (DUELS_FLAGS.containsKey(key)) {
            if (value != 0 && value != 1) {
                return false;
            }
            settings(server, player).putBoolean(key, value != 0);
            return true;
        }
        if (CHAT_FLAGS.contains(key)) {
            if (value != 0 && value != 1) {
                return false;
            }
            settings(server, player).putBoolean(key, value != 0);
            return true;
        }
        return false;
    }
}
