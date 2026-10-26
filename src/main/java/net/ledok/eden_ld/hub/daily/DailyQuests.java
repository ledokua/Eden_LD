package net.ledok.eden_ld.hub.daily;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.hub.HubData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The daily quest engine. Per-player state lives in {@link HubData} under {@code dailies}:
 * the day it belongs to, slots (quest, progress, claimed), free and paid rerolls used
 * (counted apart so a free reroll granted later is really free and the paid price ladder
 * resumes where it stopped), extra free rerolls granted today, and whether the all-quests
 * bonus was claimed. A new day (after
 * {@code reset_hour}, server time) draws a fresh set — each player their own, seeded by the
 * date and the player (so it's stable all day); an admin reset draws a new random one. Server
 * thread only.
 */
public final class DailyQuests {

    public static final ResourceLocation KILL_MOBS = eden("kill_mobs");
    public static final ResourceLocation CRAFT_ITEMS = eden("craft_items");
    public static final ResourceLocation CAST_SPELLS = eden("cast_spells");
    public static final ResourceLocation COMPLETE_DUNGEON = eden("complete_dungeon");
    public static final ResourceLocation PLACE_BLOCKS = eden("place_blocks");
    public static final ResourceLocation BREAK_BLOCKS = eden("break_blocks");
    public static final ResourceLocation EAT_FOOD = eden("eat_food");
    public static final ResourceLocation CATCH_FISH = eden("catch_fish");
    /** Any vanilla statistic; target = the stat's scoreboard name, e.g. "minecraft.custom:minecraft.jump". */
    public static final ResourceLocation STAT = eden("stat");

    public static final String FREE_REROLLS_META = "eden_ld.daily_free_rerolls";

    private static final String TAG = "dailies";
    private static final Set<UUID> REROLLING = new HashSet<>();

    private DailyQuests() {
    }

    private static ResourceLocation eden(String path) {
        return ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, path);
    }

    // ---------------------------------------------------------------- day

    public static int resetHour() {
        return Math.floorMod(EdenLD.CONFIG.dailies.reset_hour, 24);
    }

    /** Config {@code time_zone} (e.g. "Europe/Kyiv"), else the server's own zone. */
    public static java.time.ZoneId zone() {
        String configured = EdenLD.CONFIG.dailies.time_zone;
        if (configured != null && !configured.isBlank()) {
            try {
                return java.time.ZoneId.of(configured.trim());
            } catch (java.time.DateTimeException e) {
                EdenLD.LOGGER.warn("[Dailies] Unknown time_zone '{}', using the server's.", configured);
            }
        }
        return java.time.ZoneId.systemDefault();
    }

    /**
     * The quest day: the calendar date, rolled back until the reset hour. Derived from the
     * clock and stored per player, so restarts never skip or repeat a reset.
     */
    public static long today() {
        return ZonedDateTime.now(zone()).minusHours(resetHour()).toLocalDate().toEpochDay();
    }

    /** Milliseconds until the next reset. */
    public static long millisUntilReset() {
        ZonedDateTime now = ZonedDateTime.now(zone());
        ZonedDateTime next = LocalDate.ofEpochDay(today()).plusDays(1).atStartOfDay(now.getZone())
                .plusHours(resetHour());
        return Math.max(0, java.time.Duration.between(now, next).toMillis());
    }

    // ---------------------------------------------------------------- state

    /** One quest slot of today's list; {@code quest} may be null if its definition was removed. */
    public record Slot(ResourceLocation questId, @Nullable DailyQuestDefinition quest, int progress, boolean claimed) {
        public boolean complete() {
            return quest != null && progress >= quest.count();
        }
    }

    public record State(List<Slot> slots, int freeUsed, int paidUsed, int extraRerolls, boolean bonusClaimed) {
        public int claimedCount() {
            return (int) slots.stream().filter(Slot::claimed).count();
        }

        public int claimableCount() {
            return (int) slots.stream().filter(slot -> slot.complete() && !slot.claimed()).count();
        }
    }

    /** Today's state, rolled over (fresh draw) when the stored day is stale. */
    public static State state(MinecraftServer server, UUID player) {
        CompoundTag tag = today(server, player);
        ListTag slotsTag = tag.getList("slots", Tag.TAG_COMPOUND);
        List<Slot> slots = new ArrayList<>(slotsTag.size());
        for (int i = 0; i < slotsTag.size(); i++) {
            CompoundTag slot = slotsTag.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(slot.getString("quest"));
            if (id == null) {
                continue;
            }
            slots.add(new Slot(id, DailyQuestRegistry.get(id), slot.getInt("progress"), slot.getBoolean("claimed")));
        }
        return new State(slots, tag.getInt("free_used"), tag.getInt("paid_used"), tag.getInt("extra_rerolls"),
                tag.getBoolean("bonus"));
    }

    /** The player's tag for today; creates/rolls over (and marks dirty) when needed. */
    private static CompoundTag today(MinecraftServer server, UUID player) {
        CompoundTag stored = HubData.get(server).peek(player).getCompound(TAG);
        long today = today();
        if (stored.contains("day") && stored.getLong("day") == today && !stored.getList("slots", Tag.TAG_COMPOUND).isEmpty()) {
            replaceRemoved(server, player, stored);
            return stored;
        }
        return fresh(server, player, new Random(seed(today, player)));
    }

    /** A new day for the player: a draw from {@code random}, no progress, no rerolls used. */
    private static CompoundTag fresh(MinecraftServer server, UUID player, Random random) {
        CompoundTag fresh = new CompoundTag();
        fresh.putLong("day", today());
        ListTag slots = new ListTag();
        for (DailyQuestDefinition quest : draw(drawPool(server, player), random)) {
            slots.add(slotTag(quest.id()));
        }
        fresh.put("slots", slots);
        HubData.get(server).player(player).put(TAG, fresh);
        return fresh;
    }

    /** Each player's own set for the day (the same all day long, different from other players'). */
    private static long seed(long day, UUID player) {
        return day * 0x9E3779B97F4A7C15L + 0x5EED ^ player.getMostSignificantBits() * 31 ^ player.getLeastSignificantBits();
    }

    /** Unclaimed slots whose quest left the pool (datapack edit) get a replacement. */
    private static void replaceRemoved(MinecraftServer server, UUID player, CompoundTag tag) {
        ListTag slots = tag.getList("slots", Tag.TAG_COMPOUND);
        boolean changed = false;
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag slot = slots.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(slot.getString("quest"));
            if (!slot.getBoolean("claimed") && (id == null || DailyQuestRegistry.get(id) == null)) {
                DailyQuestDefinition replacement = randomExcluding(drawPool(server, player), questIds(slots), new Random());
                if (replacement != null) {
                    slots.set(i, slotTag(replacement.id()));
                    changed = true;
                }
            }
        }
        if (changed) {
            HubData.get(server).player(player).put(TAG, tag);
        }
    }

    private static CompoundTag slotTag(ResourceLocation quest) {
        CompoundTag slot = new CompoundTag();
        slot.putString("quest", quest.toString());
        slot.putInt("progress", 0);
        slot.putBoolean("claimed", false);
        return slot;
    }

    private static Set<ResourceLocation> questIds(ListTag slots) {
        Set<ResourceLocation> ids = new HashSet<>();
        for (int i = 0; i < slots.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(slots.getCompound(i).getString("quest"));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * The quests this player may draw now ({@link DailyQuestDefinition#drawableBy}: loot stage, unlocked dimensions).
     * When fewer than a day's set plus two rerolls are left, stage tops are dropped. Offline players count as stage 1.
     */
    private static List<DailyQuestDefinition> drawPool(MinecraftServer server, UUID player) {
        int stage = QuestConditions.lootStage(server.getPlayerList().getPlayer(player));
        int needed = Math.max(1, EdenLD.CONFIG.dailies.quest_count) + 2;
        return QuestConditions.pool(DailyQuestRegistry.all(), quest -> quest.drawableBy(server, player, stage, false),
                quest -> quest.drawableBy(server, player, stage, true), needed);
    }

    /** A weighted draw without replacement. */
    private static List<DailyQuestDefinition> draw(List<DailyQuestDefinition> pool, Random random) {
        List<DailyQuestDefinition> picked = new ArrayList<>();
        Set<ResourceLocation> taken = new HashSet<>();
        int count = Math.max(1, EdenLD.CONFIG.dailies.quest_count);
        for (int i = 0; i < count; i++) {
            DailyQuestDefinition quest = randomExcluding(pool, taken, random);
            if (quest == null) {
                break;
            }
            picked.add(quest);
            taken.add(quest.id());
        }
        return picked;
    }

    @Nullable
    private static DailyQuestDefinition randomExcluding(List<DailyQuestDefinition> from, Set<ResourceLocation> excluded,
                                                        Random random) {
        List<DailyQuestDefinition> pool = from.stream()
                .filter(quest -> quest.weight() > 0 && !excluded.contains(quest.id()))
                .toList();
        int total = pool.stream().mapToInt(DailyQuestDefinition::weight).sum();
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (DailyQuestDefinition quest : pool) {
            roll -= quest.weight();
            if (roll < 0) {
                return quest;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private static void write(MinecraftServer server, UUID player, CompoundTag tag) {
        HubData.get(server).player(player).put(TAG, tag);
    }

    // ---------------------------------------------------------------- progress

    /**
     * Adds progress to every unclaimed slot whose objective is {@code type} and whose target
     * the {@code matcher} accepts (no target = anything). Returns true when something moved.
     */
    public static boolean report(ServerPlayer player, ResourceLocation type, Predicate<String> matcher, int amount) {
        // The same actions feed the player's guild quests.
        net.ledok.eden_ld.hub.guild.quest.GuildQuests.report(player, type, matcher, amount);
        MinecraftServer server = player.getServer();
        // Hooks fire constantly (stats every tick); bail before touching player data unless
        // some quest in the pool uses this objective at all.
        if (server == null || amount <= 0 || !DailyQuestRegistry.usesObjective(type)) {
            return false;
        }
        CompoundTag tag = today(server, player.getUUID());
        ListTag slots = tag.getList("slots", Tag.TAG_COMPOUND);
        boolean changed = false;
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag slot = slots.getCompound(i);
            DailyQuestDefinition quest = DailyQuestRegistry.get(ResourceLocation.tryParse(slot.getString("quest")));
            if (quest == null || slot.getBoolean("claimed") || !quest.objective().equals(type)
                    || slot.getInt("progress") >= quest.count()
                    || quest.target().isPresent() && !matcher.test(quest.target().get())
                    || !QuestConditions.inDimension(player, quest.dimension())
                    || !QuestConditions.atStation(player, quest.station())) {
                continue;
            }
            int progress = Math.min(quest.count(), slot.getInt("progress") + amount);
            slot.putInt("progress", progress);
            changed = true;
            if (progress >= quest.count()) {
                notifyComplete(player, quest);
            }
        }
        if (changed) {
            write(server, player.getUUID(), tag);
        }
        return changed;
    }

    /** Target matcher for an id-based subject: exact id (no tag support). */
    public static Predicate<String> idMatcher(@Nullable ResourceLocation subject) {
        return target -> subject != null && !target.startsWith("#")
                && subject.equals(ResourceLocation.tryParse(target));
    }

    public static Predicate<String> entityMatcher(EntityType<?> type) {
        return target -> target.startsWith("#")
                ? type.is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(target.substring(1))))
                : BuiltInRegistries.ENTITY_TYPE.getKey(type).equals(ResourceLocation.tryParse(target));
    }

    /** Block id / #block tag (the placed or mined block). */
    public static Predicate<String> blockMatcher(net.minecraft.world.level.block.Block block) {
        return target -> target.startsWith("#")
                ? block.builtInRegistryHolder().is(TagKey.create(Registries.BLOCK, ResourceLocation.parse(target.substring(1))))
                : BuiltInRegistries.BLOCK.getKey(block).equals(ResourceLocation.tryParse(target));
    }

    public static Predicate<String> itemMatcher(ItemStack stack) {
        return target -> {
            if (target.startsWith("#")) {
                return stack.is(TagKey.create(Registries.ITEM, ResourceLocation.parse(target.substring(1))));
            }
            Item item = stack.getItem();
            return BuiltInRegistries.ITEM.getKey(item).equals(ResourceLocation.tryParse(target));
        };
    }

    private static void notifyComplete(ServerPlayer player, DailyQuestDefinition quest) {
        player.sendSystemMessage(Component.translatable("message.eden_ld.dailies.complete",
                        net.ledok.eden_ld.hub.lang.ServerLang.localize(player, quest.title()))
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" "))
                .append(Component.translatable("message.eden_ld.party.invited_open")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                        .withStyle(style -> style.withClickEvent(
                                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hub")))));
    }

    // ---------------------------------------------------------------- actions

    /** Claims a completed slot's rewards. Null on success, else the reason. */
    @Nullable
    public static Component claim(ServerPlayer player, int index) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        CompoundTag tag = today(server, player.getUUID());
        ListTag slots = tag.getList("slots", Tag.TAG_COMPOUND);
        if (index < 0 || index >= slots.size()) {
            return Component.translatable("message.eden_ld.dailies.unknown");
        }
        CompoundTag slot = slots.getCompound(index);
        DailyQuestDefinition quest = DailyQuestRegistry.get(ResourceLocation.tryParse(slot.getString("quest")));
        if (quest == null || slot.getBoolean("claimed") || slot.getInt("progress") < quest.count()) {
            return Component.translatable("message.eden_ld.dailies.not_complete");
        }
        slot.putBoolean("claimed", true);
        write(server, player.getUUID(), tag);
        quest.rewards().grant(player);
        net.ledok.eden_ld.hub.guild.quest.GuildQuests.onDailyClaimed(player, quest.guildXpOrDefault());
        player.sendSystemMessage(Component.translatable("message.eden_ld.dailies.claimed",
                        net.ledok.eden_ld.hub.lang.ServerLang.localize(player, quest.title()))
                .withStyle(ChatFormatting.GREEN));
        return null;
    }

    /** Claims the all-quests bonus once every slot is claimed. */
    @Nullable
    public static Component claimBonus(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        State state = state(server, player.getUUID());
        if (state.bonusClaimed() || state.slots().isEmpty() || state.claimedCount() < state.slots().size()) {
            return Component.translatable("message.eden_ld.dailies.bonus_locked");
        }
        CompoundTag tag = today(server, player.getUUID());
        tag.putBoolean("bonus", true);
        write(server, player.getUUID(), tag);
        bonusReward().grant(player);
        player.sendSystemMessage(Component.translatable("message.eden_ld.dailies.bonus_claimed")
                .withStyle(ChatFormatting.GREEN));
        return null;
    }

    /** Free rerolls today: config / LuckPerms meta, plus the Minestar level's and the guild level's, plus extras granted today. */
    public static int freeRerolls(ServerPlayer player, State state) {
        int base = Math.max(0, LuckPermsCompat.metaInt(player, FREE_REROLLS_META)
                .orElse(EdenLD.CONFIG.dailies.free_rerolls));
        int level = player.getServer() == null ? 0 : net.ledok.eden_ld.hub.profile.ProfileLevels.perk(
                player.getServer(), player.getUUID(), net.ledok.eden_ld.hub.profile.LevelPerks.Kind.DAILY_REROLLS);
        int guild = player.getServer() == null ? 0 : net.ledok.eden_ld.hub.guild.GuildLevels.memberPerk(
                player.getServer(), player.getUUID(), net.ledok.eden_ld.hub.guild.GuildPerks.Kind.DAILY_REROLLS);
        return base + level + guild + state.extraRerolls();
    }

    public static int freeRerollsLeft(ServerPlayer player, State state) {
        return Math.max(0, freeRerolls(player, state) - state.freeUsed());
    }

    /** What the next reroll costs in LC: 0 while free ones remain, else the next step of the paid ladder. */
    public static long nextRerollCost(ServerPlayer player, State state) {
        if (freeRerollsLeft(player, state) > 0) {
            return 0;
        }
        List<Long> costs = EdenLD.CONFIG.dailies.reroll_costs;
        if (costs.isEmpty()) {
            return 0;
        }
        return Math.max(0, costs.get(Math.min(state.paidUsed(), costs.size() - 1)));
    }

    /**
     * Replaces an unclaimed slot with a fresh random quest (progress lost); charges the next
     * reroll cost first. {@code onDone} gets null on success or the reason, on the server thread.
     */
    public static void reroll(ServerPlayer player, int index, Consumer<Component> onDone) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        State state = state(server, player.getUUID());
        if (index < 0 || index >= state.slots().size() || state.slots().get(index).claimed()) {
            onDone.accept(Component.translatable("message.eden_ld.dailies.cannot_reroll"));
            return;
        }
        List<ResourceLocation> ids = new ArrayList<>();
        state.slots().forEach(slot -> ids.add(slot.questId()));
        if (rerollPick(server, player.getUUID(), ids, index) == null) {
            onDone.accept(Component.translatable("message.eden_ld.dailies.no_alternatives"));
            return;
        }
        boolean usesFree = freeRerollsLeft(player, state) > 0;
        long cost = nextRerollCost(player, state);
        if (cost > 0 && !EconomyCompat.available()) {
            onDone.accept(Component.translatable("message.eden_ld.dailies.no_free_rerolls"));
            return;
        }
        if (!REROLLING.add(player.getUUID())) {
            return;
        }
        long day = today();
        EconomyCompat.take(player, cost, paid -> {
            REROLLING.remove(player.getUUID());
            if (!paid) {
                onDone.accept(Component.translatable("message.eden_ld.dailies.not_enough_lc", cost));
                return;
            }
            // The day may have rolled over while the payment was in flight.
            CompoundTag tag = today(server, player.getUUID());
            ListTag slots = tag.getList("slots", Tag.TAG_COMPOUND);
            if (day != today() || index >= slots.size() || slots.getCompound(index).getBoolean("claimed")) {
                EconomyCompat.give(player, cost);
                onDone.accept(Component.translatable("message.eden_ld.dailies.cannot_reroll"));
                return;
            }
            List<ResourceLocation> slotIds = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                slotIds.add(ResourceLocation.tryParse(slots.getCompound(i).getString("quest")));
            }
            DailyQuestDefinition replacement = rerollPick(server, player.getUUID(), slotIds, index);
            if (replacement == null) {
                EconomyCompat.give(player, cost);
                onDone.accept(Component.translatable("message.eden_ld.dailies.no_alternatives"));
                return;
            }
            slots.set(index, slotTag(replacement.id()));
            String counter = usesFree ? "free_used" : "paid_used";
            tag.putInt(counter, tag.getInt(counter) + 1);
            write(server, player.getUUID(), tag);
            onDone.accept(null);
        });
    }

    /** A new random set for today, with progress and rerolls starting over (admin reset). */
    public static void resetToday(MinecraftServer server, UUID player) {
        fresh(server, player, new Random());
    }

    /**
     * A reroll never duplicates a quest held in another slot, and avoids the quest being
     * replaced — unless the pool has nothing else, then that same quest comes back (fresh). Only from the quests the
     * player may draw now.
     */
    @Nullable
    private static DailyQuestDefinition rerollPick(MinecraftServer server, UUID player, List<ResourceLocation> slotQuests,
                                                   int index) {
        Set<ResourceLocation> others = new HashSet<>();
        for (int i = 0; i < slotQuests.size(); i++) {
            if (i != index && slotQuests.get(i) != null) {
                others.add(slotQuests.get(i));
            }
        }
        Set<ResourceLocation> all = new HashSet<>(others);
        if (slotQuests.get(index) != null) {
            all.add(slotQuests.get(index));
        }
        Random random = new Random();
        List<DailyQuestDefinition> pool = drawPool(server, player);
        DailyQuestDefinition pick = randomExcluding(pool, all, random);
        return pick != null ? pick : randomExcluding(pool, others, random);
    }

    /** Extra free rerolls for today only (quest rewards, shop items, admins). */
    public static void grantExtraRerolls(MinecraftServer server, UUID player, int amount) {
        CompoundTag tag = today(server, player);
        tag.putInt("extra_rerolls", Math.max(0, tag.getInt("extra_rerolls") + amount));
        write(server, player, tag);
    }

    /** The configured all-quests bonus as a reward. */
    public static DailyReward bonusReward() {
        EdenConfigBonus bonus = EdenConfigBonus.read();
        return new DailyReward(bonus.items(), bonus.xp(), bonus.lc(), bonus.commands(), java.util.Optional.empty());
    }

    /** Config bonus parsed once per call ("namespace:item count" strings → stacks). */
    private record EdenConfigBonus(List<ItemStack> items, int xp, long lc, List<String> commands) {
        static EdenConfigBonus read() {
            var bonus = EdenLD.CONFIG.dailies.bonus;
            List<ItemStack> items = new ArrayList<>();
            for (String entry : bonus.items) {
                String[] parts = entry.trim().split("\\s+");
                ResourceLocation id = ResourceLocation.tryParse(parts[0]);
                Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (item == null) {
                    EdenLD.LOGGER.warn("[Dailies] Unknown bonus item '{}'.", entry);
                    continue;
                }
                int count = 1;
                if (parts.length > 1) {
                    try {
                        count = Math.max(1, Integer.parseInt(parts[1]));
                    } catch (NumberFormatException ignored) {
                        EdenLD.LOGGER.warn("[Dailies] Bad bonus item count in '{}'.", entry);
                    }
                }
                items.add(new ItemStack(item, count));
            }
            return new EdenConfigBonus(items, Math.max(0, bonus.xp), Math.max(0, bonus.lc), List.copyOf(bonus.commands));
        }
    }
}
