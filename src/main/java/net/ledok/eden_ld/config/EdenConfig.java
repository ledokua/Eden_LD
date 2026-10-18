package net.ledok.eden_ld.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class EdenConfig {
    public Minestar minestar = new Minestar();
    public LiquidMana liquid_mana = new LiquidMana();
    public Flight flight = new Flight();
    public Hub hub = new Hub();
    public Combat combat = new Combat();
    public Dailies dailies = new Dailies();
    public Guilds guilds = new Guilds();
    public Activities activities = new Activities();
    public Chat chat = new Chat();
    public SystemOnly chat_system_only = new SystemOnly();
    public TabList tab_list = new TabList();
    public Profile profile = new Profile();
    public Commands commands = new Commands();
    public Hazards hazards = new Hazards();
    public Tutorial tutorial = new Tutorial();

    public static class Minestar {
        public boolean prime_role_sync_enabled = true;
        public String prime_group = "prime";
        public boolean shop_notify_on_join = true;
    }

    public static class LiquidMana {
        public double heal_percent = 5.0;
        public int heal_interval_ticks = 20;
    }

    public static class Flight {
        /** Dimensions where elytra/wing flight is blocked entirely. */
        public List<String> flight_disabled_dimensions = new ArrayList<>();
        /** Dimensions where firework boosting is blocked while gliding. */
        public List<String> boost_disabled_dimensions =
                new ArrayList<>(List.of("minecraft:overworld", "minecraft:the_nether"));
        /** When enabled, players with armor below the threshold may still boost. */
        public boolean boost_armor_threshold_enabled = true;
        public int boost_armor_threshold = 20;
        /** Give Slow Falling when flight is denied mid-air. */
        public boolean safe_landing_on_deny = true;
    }

    public static class Hub {
        /** Maximum party size (members + pending invites count toward it). */
        public int party_max_size = 5;
        /** How long a disconnected member stays in the party before auto-kick. */
        public int party_offline_grace_seconds = 180;
        /** Mob-kill XP is split among partymates within this radius of the killer (blocks). */
        public double party_xp_radius = 50.0;
        /** Bonus XP (percent of the mob's reward) added when XP is shared with a party. */
        public int party_xp_bonus_percent = 25;
        /**
         * Whether players may make FTB Teams parties. Off: FTB Teams refuses new ones (its Create
         * party button and /ftbteams party create point to the hub), so FTB Quests stays per player.
         */
        public boolean ftb_teams_parties = false;
        /**
         * Cooldown between hub waystone teleports (seconds). Per player/role override: the
         * LuckPerms meta key {@code eden_ld.waystone_cooldown} (e.g. 10, or 0 for none).
         */
        public int waystone_cooldown_seconds = 30;
        /** LC to found a guild (Economy_LD); 0 = free. */
        public long guild_create_cost = 0;
        /** Base guild size; upgrades bought from the guild bank add to it. */
        public int guild_member_limit = 5;
        /** Price of each vault tab in LC (paid from the guild bank), in order; the list length is the tab cap. */
        public List<Long> guild_tab_costs = new ArrayList<>(List.of(1_000L, 5_000L, 20_000L, 80_000L, 250_000L, 1_000_000L));
        /** Price of each member-limit upgrade (from the guild bank); each adds guild_member_upgrade_step slots. */
        public List<Long> guild_member_upgrade_costs = new ArrayList<>(List.of(1_000L, 5_000L, 10_000L, 20_000L, 40_000L));
        public int guild_member_upgrade_step = 1;
        /** Price of each claim-limit upgrade (Open Parties and Claims); each adds guild_claim_upgrade_step chunks. */
        public List<Long> guild_claim_upgrade_costs = new ArrayList<>(List.of(5_000L, 20_000L, 60_000L, 150_000L));
        public int guild_claim_upgrade_step = 25;
        /**
         * When a guild ends, its leader keeps the land only up to their own OPAC limit; the rest
         * is released. true = the most recently claimed chunks go first (the old base stays).
         */
        public boolean guild_release_newest_first = true;
        // Which themes are free is set in the Moderation window (Customization → Themes), like backgrounds.
    }

    public static class Dailies {
        /** Quests per day (drawn from the datapack pool, a different set for each player). */
        public int quest_count = 4;
        /** Hour of the day (0-23, in {@link #time_zone}) when the daily quests reset. */
        public int reset_hour = 4;
        /** Time zone for the reset, e.g. "Europe/Kyiv"; empty = the server machine's zone. */
        public String time_zone = "";
        /**
         * Free rerolls per day. Per player/role override: LuckPerms meta
         * {@code eden_ld.daily_free_rerolls}. {@code /dailies give-rerolls} adds extra ones for today.
         */
        public int free_rerolls = 1;
        /** LC cost of each paid reroll that day (after the free ones), in order; the last value repeats. */
        public List<Long> reroll_costs = new ArrayList<>(List.of(50L, 100L, 200L, 400L));
        /** Reward for claiming all of the day's quests. */
        public Bonus bonus = new Bonus();

        public static class Bonus {
            /** Items as "namespace:item count"; coins (bronze 1 LC, silver 100, gold 10 000, emerald 1 000 000). */
            public List<String> items = new ArrayList<>(List.of("economy_ld:silver_coin 5"));
            /** Experience points; 0 = none (the server rewards coins instead). */
            public int xp = 0;
            /** LC paid straight to the balance (needs Economy_LD); coins in {@link #items} are the usual way. */
            public long lc = 0;
            /** Server commands; {player} is replaced by the player's name. */
            public List<String> commands = new ArrayList<>();
        }
    }

    /**
     * The hub's Activities tab: dungeons, raids and mob arenas (from Arenas_LD, or datapack
     * {@code data/<ns>/hub_activities/}), locked behind a player level.
     */
    public static class Activities {
        /**
         * The Pufferfish Skills category (skill tree) whose level is the player's activity level,
         * e.g. {@code "puffish_skills:combat"}. Empty = nothing is locked (every activity is open).
         */
        public String level_category = "";
        /** How often online players are checked for activities their level just opened (seconds). */
        public int unlock_check_seconds = 30;
    }

    /**
     * Guild levels, guild quests and what levels give. Quests themselves are datapack files
     * ({@code data/<ns>/guild_quests/}) or {@code config/eden_ld/guild_quests/*.json}.
     */
    public static class Guilds {
        /** Guild quests on the board each day (guild levels may add more: perk {@code daily_quests}). */
        public int daily_quests = 5;
        /** Guild quests on the board each week (perk {@code weekly_quests} adds more). */
        public int weekly_quests = 2;
        /** Day the weekly guild quests reset, at the dailies' reset hour and time zone. */
        public String weekly_reset_day = "MONDAY";
        /**
         * Guild XP needed for each next level: the first entry takes a guild from level 1 to 2.
         * The list length + 1 is the highest level. Default: level 10 in about 55 days for a guild
         * finishing 3 daily (100 XP) and both weekly (500 XP) guild quests — about 443 XP a day.
         */
        public List<Long> level_xp = new ArrayList<>(List.of(800L, 1_200L, 1_700L, 2_200L, 2_700L,
                3_200L, 3_700L, 4_100L, 4_600L));
        /** Guild XP a member's claimed daily quest gives their guild (a daily's own guild_xp overrides it). */
        public int daily_quest_xp = 5;
        /**
         * How many vault tabs / member upgrades / claim upgrades may be bought before level
         * perks add more ({@code extra_tabs}, …); -1 = the whole cost list (no level gating).
         * Default: 5 of the 6 vault tabs; guild level 6 opens the sixth.
         */
        public int tab_cap = 5;
        public int member_upgrade_cap = -1;
        public int claim_upgrade_cap = -1;
        /**
         * What guild levels give, in the format of profile.level_perks: {@code amount} of
         * {@code perk} at level {@code from}, then every {@code every} levels (0 = once), up to
         * {@code until} (0 = no end). Perks: {@code emoji_slots}, {@code sticker_slots} (every
         * member's favorite slots), {@code daily_rerolls} (every member's free daily quest rerolls
         * per day), {@code daily_quests}, {@code weekly_quests} (board slots),
         * {@code tab_discount}, {@code member_upgrade_discount}, {@code claim_upgrade_discount}
         * (percent off, 90 at most), {@code extra_tabs}, {@code extra_member_upgrades},
         * {@code extra_claim_upgrades} (purchases above the caps).
         */
        public List<Profile.LevelPerk> level_perks = new ArrayList<>(List.of(
                new Profile.LevelPerk("emoji_slots", 2, 0, 5),
                new Profile.LevelPerk("claim_upgrade_discount", 4, 0, 25),
                new Profile.LevelPerk("daily_quests", 5, 0, 1),
                new Profile.LevelPerk("extra_tabs", 6, 0, 1),
                new Profile.LevelPerk("weekly_quests", 7, 0, 1),
                new Profile.LevelPerk("tab_discount", 8, 0, 25),
                new Profile.LevelPerk("daily_quests", 9, 0, 1),
                new Profile.LevelPerk("daily_rerolls", 10, 0, 5)));
    }

    /**
     * Eden's chat (replaces Styled Chat): channels and the message format. Per-group looks come
     * from LuckPerms meta: {@code eden_ld.name_color} (name), {@code eden_ld.chat_color}
     * (message text), {@code eden_ld.silent_join} (true = no join/leave messages).
     */
    public static class Chat {
        /** LOCAL channel: players within this many blocks, same dimension. */
        public double local_radius = 100;
        /** Name color when the player's groups set no {@code eden_ld.name_color}. */
        public String default_name_color = "#00c8c9";
        /** Between the name and the message; legacy codes allowed. */
        public String separator = " &8» ";
        /**
         * Discord-style markdown in messages: **bold**, *italic* or _italic_, __underline__,
         * ~~strikethrough~~, ||spoiler||, `code` (Discord shows the same).
         */
        public boolean markdown = true;
        /**
         * Tags in messages: colors ({@code <red>}, {@code <#ff8800>}, {@code <color:gold>}),
         * {@code <b> <i> <u> <st> <spoiler>}; closed with {@code </red>} or {@code </>}.
         */
        public boolean color_tags = true;
        /**
         * How a Discord line from Minestar's bridge starts (colors ignored) — Minestar's default
         * format is "&9[Discord] &b{author}&7: &f{content}". Players can hide those lines.
         */
        public String discord_line_marker = "[Discord]";
        /** Recent Global chat (players and Discord) a joining player gets: this many lines (0 = off)… */
        public int join_history = 20;
        /** …no older than this (minutes). */
        public int join_history_minutes = 120;
        /** Clickable links in messages. */
        public String link_color = "#7878ff";
        /** "@name" of an online player: highlighted, and they hear a ping. */
        public String mention_color = "#7878ff";
        public boolean mention_sound = true;
        /** Leave messages in red (join stays yellow), as Styled Chat was set up. */
        public boolean red_leave_messages = true;
        /**
         * Discord emoji sync: a bot of your own in your Discord server (no permissions needed) lets
         * config/eden_ld/emoji.json follow the server's emojis by itself. Keep the token secret.
         */
        public String discord_bot_token = "";
        /** Your Discord server's id (right-click the server icon → Copy Server ID). */
        public String discord_guild_id = "";
        /** How often the sync checks Discord (minutes); 0 = only on start and /emojis reload. */
        public int emoji_sync_minutes = 60;
        /** Favorite emojis per player; per group: LuckPerms meta eden_ld.emoji_favorites (e.g. primes 20). */
        public int emoji_favorites = 5;
        /** Saved stickers per player (their own picker tab); per group: LuckPerms meta eden_ld.sticker_favorites. */
        public int sticker_favorites = 5;
        /**
         * The hub's party/guild chat and /p, /g, /l (typed chat has vanilla's own spam limit): a player may send this
         * many messages in a row… 0 = no limit.
         */
        public int rate_limit_messages = 6;
        /** …and as many again every this many seconds. */
        public int rate_limit_seconds = 5;
    }

    /**
     * Lines that show only in the chat's System tab (1.3.2+, the new look), on top of Eden's own list (other admins'
     * command echoes, "Respawn point set", F3 feedback, login tips, ModBeacon, claim refusals…; README → Chat). Sent
     * to every player; re-read on /reload, which applies to everyone online at once.
     */
    public static class SystemOnly {
        /**
         * Translation keys of lines (find one with Inspector's translate, or in the mod's en_us.json); a trailing
         * {@code *} makes a prefix ({@code "chat.waystones.*"}). Matches the line's own key, or a part's inside an
         * empty wrapper.
         */
        public List<String> keys = new ArrayList<>();
        /**
         * How a line's text starts (leading spaces and § codes skipped; case counts), for mods that send plain text:
         * a translated line's text depends on the reader's language.
         */
        public List<String> text = new ArrayList<>();
        /**
         * Keys or text starts that stay where they are, over every rule: switches off one of Eden's, or keeps one
         * kind of admin echo by the echoed command's key ({@code "commands.ban.success"}).
         */
        public List<String> keep = new ArrayList<>();

        void fillMissing() {
            if (keys == null) keys = new ArrayList<>();
            if (text == null) text = new ArrayList<>();
            if (keep == null) keep = new ArrayList<>();
        }
    }

    /**
     * Eden's tab list (replaces Styled Player List): names as in chat (LuckPerms prefix, group
     * color, suffix) with the opt-in guild tag, a header and footer, and players sorted by rank.
     * Header/footer lines take legacy codes ({@code &6}, {@code &#00c8c9}) and {@code {online}},
     * {@code {max}}, {@code {player}}, {@code {ping}}, {@code {tps}}, {@code {mspt}}; with
     * Placeholder API installed, its {@code %…%} placeholders work too.
     */
    public static class TabList {
        public boolean enabled = true;
        public List<String> header = new ArrayList<>(List.of(
                "",
                "&#00c8c9&lFallen&#f5c542&lEden",
                "&8&m            &r &8[ &f{online}&7/&f{max} &8] &8&m            ",
                ""));
        public List<String> footer = new ArrayList<>(List.of(
                "",
                "&7TPS: {tps} &8| &7Ping: &e{ping}",
                ""));
        /** Players who turned on Settings → Show guild tag get "[TAG]" before their name. */
        public boolean guild_tags = true;
        /**
         * Highest rank first: LuckPerms meta {@code eden_ld.tab_weight}, else the weight of the
         * player's highest-weight group; equal ranks by name. Off = vanilla (by name).
         */
        public boolean sort_by_rank = true;
        /** How often names, header and footer refresh (ticks; 20 = 1 s). Only changes are sent. */
        public int update_ticks = 20;
    }

    /**
     * Player profiles (hub Profile tab) and what the Minestar activity level gives.
     */
    public static class Profile {
        /**
         * Online players' Minestar profiles are fetched again this often (minutes), so levels
         * (and the perks below) follow while they play; 0 = only on join and on the Friends tab.
         */
        public int refresh_minutes = 10;
        /**
         * Perks by Minestar level. Each rule gives {@code amount} of {@code perk} at level
         * {@code from}, then every {@code every} levels (0 = only once), up to {@code until}
         * (0 = no end). Perks: {@code emoji_slots} and {@code sticker_slots} (favorite slots),
         * {@code claims} (Open Parties and Claims bonus claims, on the player's own limit),
         * {@code daily_rerolls} (free daily quest rerolls per day).
         */
        public List<LevelPerk> level_perks = new ArrayList<>(List.of(
                new LevelPerk("emoji_slots", 1, 2, 1),
                new LevelPerk("sticker_slots", 2, 2, 1),
                new LevelPerk("claims", 5, 10, 5),
                new LevelPerk("daily_rerolls", 10, 10, 1)));

        public static class LevelPerk {
            public String perk = "";
            public int from = 1;
            public int every = 0;
            public int amount = 1;
            public int until = 0;

            public LevelPerk() {
            }

            public LevelPerk(String perk, int from, int every, int amount) {
                this.perk = perk;
                this.from = from;
                this.every = every;
                this.amount = amount;
            }
        }
    }

    /** Commands of other mods, restricted server-wide. */
    public static class Commands {
        /**
         * Commands only ops (level 2) may use, whatever the mod that adds them allows — for mods
         * that leave an admin command open to every player. Root names without the slash, e.g.
         * "structure_upgrade"; an alias ("tp" for "teleport") is a command of its own. Applied
         * when the server starts and on /reload (which re-reads this file).
         */
        public List<String> op_only = new ArrayList<>(List.of("structure_upgrade", "dungeon_difficulty_config_reload"));
        /**
         * Other mods' commands a busy player (BusyLib: a duel, a dungeon run, the tutorial) can't run — paying or
         * taking coins out, claiming kits. Root names without the slash; every subcommand is covered. Applied
         * when the server starts and on /reload.
         */
        public List<String> busy_blocked = new ArrayList<>(List.of("pay", "withdraw", "kit", "kits"));
    }

    public static class Combat {
        /**
         * PvP combat tag: when a player damages another player, both are tagged this long
         * (seconds). Tagged players can't use hub waystone teleports.
         */
        public int pvp_tag_seconds = 30;
        /** Tagged players also can't fly (elytra and wings) — enforced through the flight rules. */
        public boolean pvp_tag_blocks_flight = true;
    }

    /**
     * Hazards scale with health (docs/PLAN-hazards.md): fire, lava, poison, falls… deal at least their
     * vanilla damage, else a share of the target's health × the target's multiplier. Armor doesn't
     * reduce them; Protection enchantments and effects (Fire Resistance, Resistance…) still do.
     * Re-read on /reload.
     */
    /**
     * The tutorial ({@code docs/PLAN-tutorial.md}): first-time players are put into its room, alone in their own phase
     * and busy (locked out of teleports, guilds, rewards and spending) until the guide runs {@code /eden tutorial
     * finish}. Re-read on /reload.
     */
    public static class Tutorial {
        /** Put first-time players (no play time yet) into the tutorial. */
        public boolean enabled = true;
        /** The tutorial's dimension. */
        public String dimension = "yggdrasil:tutorial_dim";
        /** Where they arrive: "x y z" or "x y z yaw pitch"; empty = the dimension's spawn point. */
        public String spawn = "";
        /** Where they go when they finish or skip. */
        public String finish_dimension = "yggdrasil:spawn_dim";
        /** "x y z" or "x y z yaw pitch" in {@link #finish_dimension}; empty = its spawn point. */
        public String finish_spot = "";
        /** Server commands when a player starts ({player} = their name). By default: Realm Keys locks every dimension but the tutorial's. */
        public List<String> commands_on_start = new ArrayList<>(List.of(
                "realmkeys player {player} lockall",
                "realmkeys player {player} unlock yggdrasil:tutorial_dim"));
        /** Server commands when they finish or skip. By default: Realm Keys opens the first worlds. */
        public List<String> commands_on_finish = new ArrayList<>(List.of(
                "realmkeys player {player} unlock yggdrasil:spawn_dim",
                "realmkeys player {player} unlock yggdrasil:dungeon_dim",
                "realmkeys player {player} unlock yggdrasil:tower_dim",
                "realmkeys player {player} unlock yggdrasil:pvp_dim",
                "realmkeys player {player} unlock minecraft:overworld"));
        /**
         * Entity types every phase sees and hears (the guide and other NPCs): ids, or a namespace ending in ':'
         * for all of a mod's.
         */
        public List<String> always_visible = new ArrayList<>(List.of("npcs_ld:npc"));
        /**
         * Blocks in the tutorial each player gets their own copy of (items, progress, a lit furnace): ids, or a
         * namespace ending in ':'. Only blocks with a block entity; keep waystones, shops and the like off this list.
         */
        /**
         * The hub tabs' tours: what the tab shows → the NPCs_LD dialog the help window's ▶ starts. Keys are tab names
         * (home, guild, waystones, activities, mail…; {@code guild} is for players without a guild) or tab/sub-tab:
         * {@code guild/members|recruiting|quests|level|bank} for guild members, {@code activities/duels}. Nothing
         * listed = no ▶. Tours a newer Eden adds join an existing file on load; {@code ""} turns one off.
         */
        public java.util.Map<String, String> tab_tours = defaultTours();

        /** The default tours, in tab order. */
        private static java.util.Map<String, String> defaultTours() {
            java.util.Map<String, String> tours = new java.util.LinkedHashMap<>();
            tours.put("home", "config:hub/home");
            tours.put("mail", "config:hub/mail");
            tours.put("profile", "config:hub/profile");
            tours.put("wardrobe", "config:hub/wardrobe");
            tours.put("friends", "config:hub/friends");
            tours.put("party", "config:hub/party");
            tours.put("guild", "config:hub/guild");
            tours.put("guild/members", "config:hub/guild_members");
            tours.put("guild/recruiting", "config:hub/guild_recruiting");
            tours.put("guild/quests", "config:hub/guild_quests");
            tours.put("guild/level", "config:hub/guild_level");
            tours.put("guild/bank", "config:hub/guild_bank");
            tours.put("waystones", "config:hub/waystones");
            tours.put("activities", "config:hub/activities");
            tours.put("activities/duels", "config:hub/duels");
            tours.put("dailies", "config:hub/dailies");
            tours.put("kits", "config:hub/kits");
            tours.put("settings", "config:hub/settings");
            return tours;
        }
        public List<String> private_blocks = new ArrayList<>(List.of("potions_ld:alchemy_table", "minecraft:chest",
                "minecraft:trapped_chest", "minecraft:barrel", "minecraft:furnace", "minecraft:smoker",
                "minecraft:blast_furnace", "minecraft:brewing_stand"));
        /**
         * Blocks tutorial players can't use (right-click): block ids, or #tags. By default (owner): signs of every
         * kind, chiseled bookshelves and trapdoors.
         */
        public List<String> locked_blocks = new ArrayList<>(List.of("#minecraft:all_signs",
                "minecraft:chiseled_bookshelf", "#minecraft:trapdoors"));

        void fillMissing() {
            if (dimension == null) dimension = "yggdrasil:tutorial_dim";
            if (spawn == null) spawn = "";
            if (finish_dimension == null) finish_dimension = "yggdrasil:spawn_dim";
            if (finish_spot == null) finish_spot = "";
            if (commands_on_start == null) commands_on_start = new ArrayList<>();
            if (commands_on_finish == null) commands_on_finish = new ArrayList<>();
            if (always_visible == null) always_visible = new ArrayList<>();
            if (private_blocks == null) private_blocks = new ArrayList<>();
            if (locked_blocks == null) locked_blocks = new ArrayList<>();
            if (tab_tours == null) tab_tours = new java.util.LinkedHashMap<>();
            // A file written by an older Eden lacks the tours added since (the Kits tour, 1.1.6).
            defaultTours().forEach(tab_tours::putIfAbsent);
        }
    }

    public static class Hazards {
        public boolean enabled = true;
        /** Multipliers on the health share: players, other living things, and bosses (#falleneden:bosses, else #c:bosses). */
        public double player = 1.0;
        public double mob = 0.5;
        public double boss = 0.1;
        /**
         * A player's hazard another player caused (rules with "pvp": true — burning, freezing, lightning,
         * wither, poison) deals this much of its health share.
         */
        public double pvp = 0.5;
        /** Burning and freezing count as another player's doing while one hit the target this recently. */
        public int pvp_seconds = 5;
        /** One rule per damage type id; armor doesn't reduce these types. */
        public java.util.Map<String, Rule> damage_types = defaultRules();
        /** The poison effect (its damage type, minecraft:magic, is shared with harming potions). */
        public Rule poison = Rule.current(4).pvp().notLethal();

        /**
         * Percentages of the target's max, current and missing health (added up), or of max health
         * per point of vanilla damage (falls: a point per block); {@code pvp}: halved (see {@link #pvp})
         * when another player caused it; {@code lethal}: false leaves at least 1 health.
         */
        public static class Rule {
            public double max_percent;
            public double current_percent;
            public double missing_percent;
            public double per_point_percent;
            public boolean pvp;
            public boolean lethal = true;

            static Rule max(double percent) {
                Rule rule = new Rule();
                rule.max_percent = percent;
                return rule;
            }

            static Rule current(double percent) {
                Rule rule = new Rule();
                rule.current_percent = percent;
                return rule;
            }

            static Rule perPoint(double percent) {
                Rule rule = new Rule();
                rule.per_point_percent = percent;
                return rule;
            }

            Rule missing(double percent) {
                missing_percent = percent;
                return this;
            }

            Rule pvp() {
                pvp = true;
                return this;
            }

            Rule notLethal() {
                lethal = false;
                return this;
            }
        }

        /** A file without the rules or poison gets the defaults. */
        void fillMissing() {
            if (damage_types == null) damage_types = defaultRules();
            if (poison == null) poison = Rule.current(4).pvp().notLethal();
        }

        private static java.util.Map<String, Rule> defaultRules() {
            java.util.Map<String, Rule> rules = new java.util.LinkedHashMap<>();
            rules.put("minecraft:lava", Rule.max(5));
            rules.put("minecraft:in_fire", Rule.max(1.5));
            rules.put("minecraft:campfire", Rule.max(1.5));
            rules.put("minecraft:hot_floor", Rule.max(1.5));
            rules.put("minecraft:on_fire", Rule.max(1.5).pvp());
            rules.put("minecraft:freeze", Rule.max(3).pvp());
            rules.put("minecraft:drown", Rule.max(5));
            rules.put("minecraft:in_wall", Rule.max(4));
            rules.put("minecraft:cramming", Rule.max(4));
            rules.put("minecraft:starve", Rule.max(4));
            rules.put("minecraft:out_of_world", Rule.max(10));
            rules.put("minecraft:outside_border", Rule.perPoint(1));
            rules.put("minecraft:cactus", Rule.max(1));
            rules.put("minecraft:sweet_berry_bush", Rule.max(1));
            rules.put("minecraft:lightning_bolt", Rule.max(15).pvp());
            rules.put("minecraft:fall", Rule.perPoint(3.5));
            rules.put("minecraft:fly_into_wall", Rule.perPoint(3.5));
            rules.put("minecraft:stalagmite", Rule.perPoint(3.5));
            rules.put("minecraft:wither", Rule.max(1).missing(4).pvp());
            // Other mods' terrain: thorny plants as cactus, hot things as fire, Create's lava fan as lava.
            rules.put("biomesoplenty:bramble", Rule.max(1));
            rules.put("biomesoplenty:fumarole", Rule.max(1.5));
            rules.put("farmersdelight:stove_burn", Rule.max(1.5));
            rules.put("frightsdelight:soul_berry_bush", Rule.max(1));
            rules.put("frightsdelight:wither_berry_bush", Rule.max(1));
            rules.put("ubesdelight:kalan_burn", Rule.max(1.5));
            rules.put("yungscavebiomes:prickly_vines", Rule.max(1));
            rules.put("create:fan_fire", Rule.max(1.5));
            rules.put("create:fan_lava", Rule.max(5));
            return rules;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /**
     * Everything Eden_LD reads lives in {@code config/eden_ld/}: one file per section of this
     * class, named after it ({@code chat.json}, {@code hub.json}, {@code tab_list.json}, …), next
     * to emoji.json, daily_quests/ and lang/. A missing file or setting gets its default (and is
     * written back); an unreadable file keeps its defaults and says so in the log.
     */
    private static final Path FOLDER = FabricLoader.getInstance().getConfigDir().resolve(EdenLD.MOD_ID);
    /** Where all sections used to be; no longer read. */
    private static final Path OLD_FILE = FOLDER.resolve(EdenLD.MOD_ID + ".json");

    public static EdenConfig load() {
        EdenConfig config = new EdenConfig();
        if (Files.exists(OLD_FILE)) {
            EdenLD.LOGGER.warn("[Config] config/eden_ld/eden_ld.json is no longer read: settings now live in one file "
                    + "per section (chat.json, hub.json, …) in the same folder. Copy what you changed there, then delete it.");
        }
        for (java.lang.reflect.Field field : sections()) {
            Path file = FOLDER.resolve(field.getName() + ".json");
            if (!Files.exists(file)) {
                continue;
            }
            try {
                Object section = GSON.fromJson(Files.readString(file), field.getType());
                if (section != null) {
                    field.set(config, section);
                }
            } catch (Exception e) {
                EdenLD.LOGGER.error("[Config] Can't read {}, using its defaults.", file.getFileName(), e);
            }
        }
        if (config.chat.discord_bot_token == null) config.chat.discord_bot_token = "";
        if (config.chat.discord_guild_id == null) config.chat.discord_guild_id = "";
        if (config.dailies.bonus == null) config.dailies.bonus = new Dailies.Bonus();
        if (config.hub.guild_tab_costs == null) config.hub.guild_tab_costs = new ArrayList<>();
        if (config.hub.guild_member_upgrade_costs == null) config.hub.guild_member_upgrade_costs = new ArrayList<>();
        if (config.hub.guild_claim_upgrade_costs == null) config.hub.guild_claim_upgrade_costs = new ArrayList<>();
        if (config.guilds.level_xp == null) config.guilds.level_xp = new ArrayList<>();
        if (config.guilds.level_perks == null) config.guilds.level_perks = new ArrayList<>();
        if (config.guilds.weekly_reset_day == null) config.guilds.weekly_reset_day = "MONDAY";
        if (config.dailies.reroll_costs == null) config.dailies.reroll_costs = new ArrayList<>();
        if (config.flight.flight_disabled_dimensions == null) config.flight.flight_disabled_dimensions = new ArrayList<>();
        if (config.flight.boost_disabled_dimensions == null) config.flight.boost_disabled_dimensions = new ArrayList<>();
        if (config.tab_list.header == null) config.tab_list.header = new ArrayList<>();
        if (config.tab_list.footer == null) config.tab_list.footer = new ArrayList<>();
        if (config.commands.op_only == null) config.commands.op_only = new ArrayList<>();
        if (config.commands.busy_blocked == null) config.commands.busy_blocked = new ArrayList<>();
        config.hazards.fillMissing();
        config.tutorial.fillMissing();
        config.chat_system_only.fillMissing();
        config.save();
        return config;
    }

    /** Re-reads {@code commands.json} into this config (the op-only list follows /reload). */
    public void reloadCommands() {
        Path file = FOLDER.resolve("commands.json");
        if (!Files.exists(file)) {
            return;
        }
        try {
            Commands section = GSON.fromJson(Files.readString(file), Commands.class);
            if (section != null) {
                if (section.op_only == null) {
                    section.op_only = new ArrayList<>();
                }
                if (section.busy_blocked == null) {
                    section.busy_blocked = new ArrayList<>();
                }
                commands = section;
            }
        } catch (Exception e) {
            EdenLD.LOGGER.error("[Config] Can't read {}, keeping the previous list.", file.getFileName(), e);
        }
    }

    /** Re-reads {@code hazards.json} (the values follow /reload). */
    /** Re-reads {@code tutorial.json} (on /reload). */
    public void reloadTutorial() {
        Path file = FOLDER.resolve("tutorial.json");
        if (!Files.exists(file)) {
            return;
        }
        try {
            Tutorial section = GSON.fromJson(Files.readString(file), Tutorial.class);
            if (section != null) {
                section.fillMissing();
                tutorial = section;
            }
        } catch (Exception e) {
            EdenLD.LOGGER.error("[Config] Can't read {}, keeping the previous values.", file.getFileName(), e);
        }
    }

    public void reloadHazards() {
        Path file = FOLDER.resolve("hazards.json");
        if (!Files.exists(file)) {
            return;
        }
        try {
            Hazards section = GSON.fromJson(Files.readString(file), Hazards.class);
            if (section != null) {
                section.fillMissing();
                hazards = section;
            }
        } catch (Exception e) {
            EdenLD.LOGGER.error("[Config] Can't read {}, keeping the previous values.", file.getFileName(), e);
        }
    }

    /** Re-reads {@code chat_system_only.json} (on /reload; ChatChannels sends it to everyone online). */
    public void reloadSystemOnly() {
        Path file = FOLDER.resolve("chat_system_only.json");
        if (!Files.exists(file)) {
            return;
        }
        try {
            SystemOnly section = GSON.fromJson(Files.readString(file), SystemOnly.class);
            if (section != null) {
                section.fillMissing();
                chat_system_only = section;
            }
        } catch (Exception e) {
            EdenLD.LOGGER.error("[Config] Can't read {}, keeping the previous lists.", file.getFileName(), e);
        }
    }

    /** Writes {@code commands.json} only (the Moderation window edits the op-only list). */
    public void saveCommands() {
        try {
            Files.createDirectories(FOLDER);
            Files.writeString(FOLDER.resolve("commands.json"), GSON.toJson(commands));
        } catch (IOException e) {
            EdenLD.LOGGER.error("[Config] Failed to save commands.json.", e);
        }
    }

    /** Writes {@code flight.json} only (Moderation → World settings edits the flight lists). */
    public void saveFlight() {
        try {
            Files.createDirectories(FOLDER);
            Files.writeString(FOLDER.resolve("flight.json"), GSON.toJson(flight));
        } catch (IOException e) {
            EdenLD.LOGGER.error("[Config] Failed to save flight.json.", e);
        }
    }

    /** Writes every section to its file (new settings appear with their defaults). */
    public void save() {
        try {
            Files.createDirectories(FOLDER);
            for (java.lang.reflect.Field field : sections()) {
                Files.writeString(FOLDER.resolve(field.getName() + ".json"), GSON.toJson(field.get(this)));
            }
        } catch (IOException | IllegalAccessException e) {
            EdenLD.LOGGER.error("[Config] Failed to save config.", e);
        }
    }

    /** The sections: this class's public instance fields (each a settings class of its own). */
    private static List<java.lang.reflect.Field> sections() {
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        for (java.lang.reflect.Field field : EdenConfig.class.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return fields;
    }
}
