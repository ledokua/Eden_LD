package net.ledok.eden_ld.hub.guild.quest;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.daily.DailyObjectives;
import net.ledok.eden_ld.hub.daily.DailyQuests;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildBank;
import net.ledok.eden_ld.hub.guild.GuildBankServer;
import net.ledok.eden_ld.hub.guild.GuildLevels;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.ledok.eden_ld.hub.guild.GuildPerks;
import net.ledok.eden_ld.hub.lang.ServerLang;
import net.ledok.eden_ld.hub.mail.MailHub;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The guild quest board. Each guild has a daily and a weekly list ({@link Guild#quests}):
 * {@code day}/{@code week} the list belongs to, and slots of {@code quest}, {@code goal} (fixed
 * when drawn), {@code progress}, {@code done} and {@code by} (progress per member). Lists are
 * drawn per guild and period from the quests its level allows (seeded, so stable), sized by the
 * config plus the level's extra slots. Progress from every member's actions feeds it; a quest
 * completes on its own: guild XP, LC into the bank, items mailed to every member (deleted at the
 * next reset). Members online get the board pushed at most once a second. Server thread only.
 */
public final class GuildQuests {

    private static final String DAY = "day";
    private static final String WEEK = "week";
    private static final String DAILY = "daily";
    private static final String WEEKLY = "weekly";
    /** Guilds whose board changed since the last push. */
    private static final Set<UUID> DIRTY = new HashSet<>();

    private GuildQuests() {
    }

    public static void initialize() {
        GuildQuestRegistry.initialize();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0 && !DIRTY.isEmpty()) {
                GuildManager manager = GuildManager.get(server);
                List<UUID> changed = new ArrayList<>(DIRTY);
                DIRTY.clear();
                for (UUID id : changed) {
                    Guild guild = manager.byId(id);
                    if (guild != null) {
                        pushMembers(server, guild);
                    }
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.GuildQuestsRequest.TYPE,
                (payload, context) -> send(context.player()));
    }

    public static void markDirty(Guild guild) {
        DIRTY.add(guild.id);
    }

    // ---------------------------------------------------------------- periods

    /** The weekly period: the epoch day its week started (config weekday, at the daily reset hour). */
    public static long thisWeek() {
        return LocalDate.ofEpochDay(DailyQuests.today()).with(TemporalAdjusters.previousOrSame(weeklyResetDay()))
                .toEpochDay();
    }

    private static DayOfWeek weeklyResetDay() {
        try {
            return DayOfWeek.valueOf(EdenLD.CONFIG.guilds.weekly_reset_day.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return DayOfWeek.MONDAY;
        }
    }

    public static long millisUntilWeeklyReset() {
        ZonedDateTime now = ZonedDateTime.now(DailyQuests.zone());
        ZonedDateTime next = LocalDate.ofEpochDay(thisWeek()).plusDays(7).atStartOfDay(now.getZone())
                .plusHours(DailyQuests.resetHour());
        return Math.max(0, java.time.Duration.between(now, next).toMillis());
    }

    /** Time left until the period's board is drawn anew. */
    public static long millisUntilReset(GuildQuestDefinition.Period period) {
        return period == GuildQuestDefinition.Period.DAILY ? DailyQuests.millisUntilReset() : millisUntilWeeklyReset();
    }

    // ---------------------------------------------------------------- board

    /** Board size for a period at the guild's level. */
    public static int wanted(Guild guild, GuildQuestDefinition.Period period) {
        return period == GuildQuestDefinition.Period.DAILY
                ? Math.max(0, EdenLD.CONFIG.guilds.daily_quests + GuildLevels.perk(guild, GuildPerks.Kind.DAILY_QUESTS))
                : Math.max(0, EdenLD.CONFIG.guilds.weekly_quests + GuildLevels.perk(guild, GuildPerks.Kind.WEEKLY_QUESTS));
    }

    /**
     * Rolls lists over to the current day/week, tops them up to the wanted size (a level-up adds
     * slots at once) and replaces unfinished quests that left the pool. True when it changed.
     */
    public static boolean ensure(MinecraftServer server, Guild guild) {
        boolean changed = ensure(guild, GuildQuestDefinition.Period.DAILY, DAY, DAILY, DailyQuests.today())
                | ensure(guild, GuildQuestDefinition.Period.WEEKLY, WEEK, WEEKLY, thisWeek());
        if (changed) {
            GuildManager.get(server).markChanged();
            markDirty(guild);
            net.ledok.eden_ld.hub.daily.ActiveStatQuests.invalidate();
        }
        return changed;
    }

    private static boolean ensure(Guild guild, GuildQuestDefinition.Period period, String periodKey, String listKey,
                                  long current) {
        CompoundTag board = guild.quests;
        boolean changed = false;
        ListTag slots;
        if (!board.contains(periodKey) || board.getLong(periodKey) != current) {
            board.putLong(periodKey, current);
            slots = new ListTag();
            board.put(listKey, slots);
            changed = true;
        } else {
            slots = board.getList(listKey, Tag.TAG_COMPOUND);
        }
        int level = GuildLevels.level(guild);
        Random random = new Random(seed(guild.id, current, period, slots.size()));
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag slot = slots.getCompound(i);
            if (!slot.getBoolean("done") && GuildQuestRegistry.get(ResourceLocation.tryParse(slot.getString("quest"))) == null) {
                GuildQuestDefinition replacement = pick(period, level, ids(slots), random);
                if (replacement != null) {
                    slots.set(i, slotTag(replacement, guild));
                    changed = true;
                }
            }
        }
        int wanted = wanted(guild, period);
        while (slots.size() < wanted) {
            GuildQuestDefinition quest = pick(period, level, ids(slots), random);
            if (quest == null) {
                break;
            }
            slots.add(slotTag(quest, guild));
            changed = true;
        }
        if (changed) {
            board.put(listKey, slots);
        }
        return changed;
    }

    /** The targets of the open stat quests on the player's guild board (none without a guild). */
    public static List<String> openStatTargets(MinecraftServer server, UUID player) {
        Guild guild = GuildManager.get(server).guildOf(player);
        if (guild == null) {
            return List.of();
        }
        ensure(server, guild);
        List<String> targets = new java.util.ArrayList<>();
        for (String key : new String[]{DAILY, WEEKLY}) {
            ListTag slots = guild.quests.getList(key, Tag.TAG_COMPOUND);
            for (int i = 0; i < slots.size(); i++) {
                CompoundTag slot = slots.getCompound(i);
                GuildQuestDefinition quest = GuildQuestRegistry.get(ResourceLocation.tryParse(slot.getString("quest")));
                if (quest != null && net.ledok.eden_ld.hub.daily.DailyQuests.STAT.equals(quest.objective())
                        && !slot.getBoolean("done") && slot.getInt("progress") < slot.getInt("goal")
                        ) {
                    // No target: every stat counts.
                    targets.add(quest.target().orElse(net.ledok.eden_ld.hub.daily.ActiveStatQuests.ANY));
                }
            }
        }
        return targets;
    }

    /** Each guild its own board, stable through the period. */
    private static long seed(UUID guild, long period, GuildQuestDefinition.Period kind, int filled) {
        return period * 0x9E3779B97F4A7C15L ^ guild.getMostSignificantBits() * 31 ^ guild.getLeastSignificantBits()
                ^ (kind.ordinal() + 1L) * 0x632BE59BD9B4E019L ^ filled * 0x85EBCA6BL;
    }

    private static CompoundTag slotTag(GuildQuestDefinition quest, Guild guild) {
        CompoundTag slot = new CompoundTag();
        slot.putString("quest", quest.id().toString());
        slot.putInt("goal", quest.goal(guild.members.size()));
        slot.putInt("progress", 0);
        slot.putBoolean("done", false);
        slot.put("by", new CompoundTag());
        return slot;
    }

    private static Set<ResourceLocation> ids(ListTag slots) {
        Set<ResourceLocation> ids = new HashSet<>();
        for (int i = 0; i < slots.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(slots.getCompound(i).getString("quest"));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** A weighted pick among quests of this period the level allows, not already on the board. */
    @Nullable
    private static GuildQuestDefinition pick(GuildQuestDefinition.Period period, int level,
                                             Set<ResourceLocation> taken, Random random) {
        List<GuildQuestDefinition> pool = GuildQuestRegistry.all().stream()
                .filter(quest -> quest.period() == period && quest.fits(level) && !taken.contains(quest.id()))
                .toList();
        int total = pool.stream().mapToInt(GuildQuestDefinition::weight).sum();
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (GuildQuestDefinition quest : pool) {
            roll -= quest.weight();
            if (roll < 0) {
                return quest;
            }
        }
        return pool.get(pool.size() - 1);
    }

    /** A new board for both periods (admin): fresh draws, progress gone. */
    public static void reset(MinecraftServer server, Guild guild) {
        guild.quests = new CompoundTag();
        ensure(server, guild);
        net.ledok.eden_ld.hub.daily.ActiveStatQuests.invalidate();
        GuildManager.get(server).markChanged();
        markDirty(guild);
    }

    // ---------------------------------------------------------------- progress

    /**
     * Progress from a member's action (every daily hook calls this too). Unfinished quests of
     * this objective whose target {@code matcher} accepts get {@code amount}, up to the goal.
     */
    public static void report(ServerPlayer player, ResourceLocation type, Predicate<String> matcher, int amount) {
        MinecraftServer server = player.getServer();
        if (server == null || amount <= 0 || !GuildQuestRegistry.usesObjective(type)) {
            return;
        }
        GuildManager manager = GuildManager.get(server);
        Guild guild = manager.guildOf(player.getUUID());
        if (guild == null) {
            return;
        }
        ensure(server, guild);
        boolean changed = false;
        for (GuildQuestDefinition.Period period : GuildQuestDefinition.Period.values()) {
            ListTag slots = guild.quests.getList(period == GuildQuestDefinition.Period.DAILY ? DAILY : WEEKLY,
                    Tag.TAG_COMPOUND);
            for (int i = 0; i < slots.size(); i++) {
                CompoundTag slot = slots.getCompound(i);
                GuildQuestDefinition quest = GuildQuestRegistry.get(ResourceLocation.tryParse(slot.getString("quest")));
                int goal = slot.getInt("goal");
                int progress = slot.getInt("progress");
                if (quest != null && !slot.getBoolean("done")) {
                    // Review finding 10: the goal grows with the guild during the period and never shrinks, so
                    // members can't leave before the draw and rejoin to small goals.
                    int now = quest.goal(guild.members.size());
                    if (now > goal) {
                        goal = now;
                        slot.putInt("goal", goal);
                        changed = true;
                    }
                }
                if (quest == null || slot.getBoolean("done") || !quest.objective().equals(type) || progress >= goal
                        || quest.target().isPresent() && !matcher.test(quest.target().get())
                        || !net.ledok.eden_ld.hub.daily.QuestConditions.inDimension(player, quest.dimension())
                        || !net.ledok.eden_ld.hub.daily.QuestConditions.atStation(player, quest.station())) {
                    continue;
                }
                int added = Math.min(amount, goal - progress);
                slot.putInt("progress", progress + added);
                CompoundTag by = slot.getCompound("by");
                String key = player.getUUID().toString();
                by.putInt(key, by.getInt(key) + added);
                slot.put("by", by);
                changed = true;
                if (progress + added >= goal) {
                    slot.putBoolean("done", true);
                    complete(server, guild, quest, goal);
                }
            }
        }
        if (changed) {
            manager.markChanged();
            markDirty(guild);
        }
    }

    /** Per-player hub data: guild quests paid this day and week, in any guild (review finding 3). */
    private static final String PAID = "guild_quests_paid";

    /**
     * The members this finished quest may still pay: each player is paid each guild quest at most once per day (daily
     * quests) or week (weekly ones), and at most a full board's worth, whatever guild they are in. Without it, one
     * player founding a new guild (free) got a fresh board each time and farmed its rewards without limit (review
     * finding 3, 1.3.130). Counts them as paid.
     */
    private static List<UUID> payable(MinecraftServer server, Guild guild, GuildQuestDefinition quest) {
        boolean daily = quest.period() == GuildQuestDefinition.Period.DAILY;
        long current = daily ? DailyQuests.today() : thisWeek();
        String periodKey = daily ? "day" : "week";
        String listKey = daily ? "daily" : "weekly";
        String id = quest.id().toString();
        int cap = Math.max(1, wanted(guild, quest.period()));
        net.ledok.eden_ld.hub.HubData data = net.ledok.eden_ld.hub.HubData.get(server);
        List<UUID> payable = new ArrayList<>();
        for (UUID member : guild.members.keySet()) {
            CompoundTag paid = data.peek(member).getCompound(PAID);
            ListTag ids = paid.getLong(periodKey) == current ? paid.getList(listKey, Tag.TAG_STRING) : new ListTag();
            boolean already = false;
            for (int i = 0; i < ids.size(); i++) {
                already |= ids.getString(i).equals(id);
            }
            if (already || ids.size() >= cap) {
                continue;
            }
            payable.add(member);
            ids.add(net.minecraft.nbt.StringTag.valueOf(id));
            CompoundTag tag = data.player(member);
            CompoundTag write = tag.getCompound(PAID);
            write.putLong(periodKey, current);
            write.put(listKey, ids);
            tag.put(PAID, write);
        }
        return payable;
    }

    /** A finished quest: XP, bank LC, member mail, and the news to whoever is online. */
    private static void complete(MinecraftServer server, Guild guild, GuildQuestDefinition quest, int goal) {
        GuildQuestDefinition.Rewards rewards = quest.rewards();
        List<UUID> payable = payable(server, guild, quest);
        if (payable.isEmpty()) {
            // Everyone here already had a full board's rewards this period (in this guild or another).
            EdenLD.LOGGER.info("[Guild quests] {} finished {}, but every member was already paid for it (or for a full board) this {}; "
                    + "no rewards.", guild.name, quest.id(), quest.period() == GuildQuestDefinition.Period.DAILY ? "day" : "week");
            for (UUID member : guild.members.keySet()) {
                ServerPlayer online = server.getPlayerList().getPlayer(member);
                if (online != null) {
                    online.sendSystemMessage(Component.translatable("message.eden_ld.guild.quest_done_unpaid",
                            ServerLang.localize(online, quest.title(goal))).withStyle(ChatFormatting.GRAY));
                }
            }
            return;
        }
        // The bank's LC and the guild XP in proportion to the members still payable: a member already paid this
        // quest elsewhere brings no extra share.
        double share = guild.members.isEmpty() ? 1 : (double) payable.size() / guild.members.size();
        long lc = Math.round(rewards.lc() * share);
        int guildXp = (int) Math.round(rewards.guildXp() * share);
        if (lc > 0) {
            guild.bank.balance += lc;
            guild.bank.log.add(new GuildBank.LogEntry(System.currentTimeMillis(), new UUID(0L, 0L), "",
                    GuildBank.LogType.QUEST_REWARD, -1, ItemStack.EMPTY, lc, quest.id().toString()));
            while (guild.bank.log.size() > GuildBank.LOG_LIMIT) {
                guild.bank.log.remove(0);
            }
            GuildBankServer.pushToViewers(server, guild);
        }
        if (!rewards.items().isEmpty() && MailHub.LOADED) {
            long expiresAt = (System.currentTimeMillis() + millisUntilReset(quest.period())) / 1000L;
            Economy.mail(guild, quest.period(), rewards.items(), expiresAt, payable);
        }
        for (UUID member : guild.members.keySet()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online == null) {
                continue;
            }
            online.sendSystemMessage(Component.translatable("message.eden_ld.guild.quest_done",
                            ServerLang.localize(online, quest.title(goal)))
                    .withStyle(ChatFormatting.GOLD)
                    .append(" ")
                    .append(Component.translatable("message.eden_ld.party.invited_open")
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                            .withStyle(style -> style.withClickEvent(
                                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hub")))));
        }
        // XP last: a level-up message follows the quest's.
        GuildLevels.addXp(server, guild, guildXp);
    }

    /** Guild XP from a member's claimed daily quest. */
    public static void onDailyClaimed(ServerPlayer player, int guildXp) {
        MinecraftServer server = player.getServer();
        if (server == null || guildXp <= 0) {
            return;
        }
        Guild guild = GuildManager.get(server).guildOf(player.getUUID());
        if (guild != null) {
            GuildLevels.addXp(server, guild, guildXp);
        }
    }

    // ---------------------------------------------------------------- view

    /** One quest on a guild's board: its slot as stored, and each member's progress ({@code by}). */
    public record BoardQuest(GuildQuestDefinition.Period period, GuildQuestDefinition quest, int goal, int progress,
                             boolean done, java.util.Map<UUID, Integer> by) {
    }

    /** The guild's current daily and weekly boards (rolled over first, as members see them); unknown quests left out. */
    public static List<BoardQuest> board(MinecraftServer server, Guild guild) {
        ensure(server, guild);
        List<BoardQuest> out = new ArrayList<>();
        for (GuildQuestDefinition.Period period : GuildQuestDefinition.Period.values()) {
            ListTag slots = guild.quests.getList(period == GuildQuestDefinition.Period.DAILY ? DAILY : WEEKLY,
                    Tag.TAG_COMPOUND);
            for (int i = 0; i < slots.size(); i++) {
                CompoundTag slot = slots.getCompound(i);
                GuildQuestDefinition quest = GuildQuestRegistry.get(ResourceLocation.tryParse(slot.getString("quest")));
                if (quest == null) {
                    continue;
                }
                java.util.Map<UUID, Integer> by = new java.util.LinkedHashMap<>();
                CompoundTag contributions = slot.getCompound("by");
                for (String key : contributions.getAllKeys()) {
                    try {
                        by.put(UUID.fromString(key), contributions.getInt(key));
                    } catch (IllegalArgumentException ignored) {
                        // Not a member's key: nothing to show for it.
                    }
                }
                out.add(new BoardQuest(period, quest, slot.getBoolean("done") ? slot.getInt("goal")
                        : Math.max(slot.getInt("goal"), quest.goal(guild.members.size())), slot.getInt("progress"),
                        slot.getBoolean("done"), by));
            }
        }
        return out;
    }

    public static void pushMembers(MinecraftServer server, Guild guild) {
        for (UUID member : guild.members.keySet()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) {
                send(online);
            }
        }
    }

    public static void send(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || player.hasDisconnected()) {
            return;
        }
        ServerPlayNetworking.send(player, build(server, player));
    }

    private static HubPayloads.GuildQuestsData build(MinecraftServer server, ServerPlayer player) {
        Guild guild = GuildManager.get(server).guildOf(player.getUUID());
        List<HubPayloads.PerkRule> rules = GuildPerks.rules().stream()
                .map(rule -> new HubPayloads.PerkRule(rule.perk(), rule.from(), rule.every(), rule.amount(), rule.until()))
                .toList();
        if (guild == null) {
            return new HubPayloads.GuildQuestsData(1, 0, 0, GuildLevels.maxLevel(), 0, 0, List.of(), rules,
                    EconomyCompat.available());
        }
        ensure(server, guild);
        GuildLevels.Progress progress = GuildLevels.progress(guild.xp);
        List<HubPayloads.GuildQuestEntry> entries = new ArrayList<>();
        for (GuildQuestDefinition.Period period : GuildQuestDefinition.Period.values()) {
            ListTag slots = guild.quests.getList(period == GuildQuestDefinition.Period.DAILY ? DAILY : WEEKLY,
                    Tag.TAG_COMPOUND);
            for (int i = 0; i < slots.size(); i++) {
                CompoundTag slot = slots.getCompound(i);
                GuildQuestDefinition quest = GuildQuestRegistry.get(ResourceLocation.tryParse(slot.getString("quest")));
                if (quest == null) {
                    continue;
                }
                // The goal grows with the guild (finding 10): shown as it will count, not as last stored.
                int goal = slot.getBoolean("done") ? slot.getInt("goal")
                        : Math.max(slot.getInt("goal"), quest.goal(guild.members.size()));
                CompoundTag by = slot.getCompound("by");
                String top = "";
                int topAmount = 0;
                for (String key : by.getAllKeys()) {
                    int amount = by.getInt(key);
                    if (amount > topAmount) {
                        topAmount = amount;
                        try {
                            top = guild.memberNames.getOrDefault(UUID.fromString(key), "?");
                        } catch (IllegalArgumentException e) {
                            top = "?";
                        }
                    }
                }
                entries.add(new HubPayloads.GuildQuestEntry(period.ordinal(),
                        ServerLang.localize(player, quest.cardTitle()),
                        quest.name().isPresent()
                                ? ServerLang.localize(player, DailyObjectives.goal(quest.objective(), quest.target()))
                                : Component.empty(),
                        quest.icon(), DailyObjectives.shown(quest.objective(), quest.target(), slot.getInt("progress"), false),
                        DailyObjectives.shown(quest.objective(), quest.target(), goal, true), slot.getBoolean("done"),
                        quest.rewards().guildXp(), EconomyCompat.available() ? quest.rewards().lc() : 0,
                        quest.rewards().items(),
                        DailyObjectives.shown(quest.objective(), quest.target(), by.getInt(player.getUUID().toString()), false),
                        top, DailyObjectives.shown(quest.objective(), quest.target(), topAmount, false)));
            }
        }
        return new HubPayloads.GuildQuestsData(progress.level(), progress.into(), progress.needed(),
                GuildLevels.maxLevel(), DailyQuests.millisUntilReset(), millisUntilWeeklyReset(), entries, rules,
                EconomyCompat.available());
    }

    /** The only class touching Economy_LD types. */
    private static final class Economy {
        static void mail(Guild guild, GuildQuestDefinition.Period period, List<ItemStack> items, long expiresAt,
                         List<UUID> to) {
            String kind = period == GuildQuestDefinition.Period.DAILY ? "daily" : "weekly";
            var builder = net.ledok.economy_ld.mail.MailDraft.builder(net.ledok.economy_ld.mail.MailKind.REWARD,
                            net.ledok.economy_ld.mail.MailContent.translated("mail.eden_ld.guild_quest." + kind + ".title",
                                    "mail.eden_ld.guild_quest.body", List.of(guild.name)))
                    .sender(guild.name, null)
                    .expiresAt(expiresAt)
                    .to(new ArrayList<>(to))
                    .audienceNote("guild:" + guild.tag);
            for (ItemStack stack : items) {
                builder.item(new net.ledok.economy_ld.mail.MailAttachment(stack.copyWithCount(1), stack.getCount()));
            }
            net.ledok.economy_ld.mail.EconomyMail.send(builder.build()).exceptionally(error -> {
                EdenLD.LOGGER.error("[Guild quests] Couldn't mail rewards to {}.", guild.name, error);
                return null;
            });
        }
    }
}
