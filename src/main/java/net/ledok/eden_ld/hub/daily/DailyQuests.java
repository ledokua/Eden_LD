package net.ledok.eden_ld.hub.daily;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
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

    /**
     * Review finding 19: a copy of the player's dailies tag as of their last claim, bonus, reroll or draw, saved in the
     * player file. The rewards land in the player file at once while hub.dat waits for the autosave, so after a hard
     * stop in between the copy with the newer {@code stamp} wins: a quest stays claimed exactly when its rewards stayed.
     * Hub data stays the store (offline players, staff tools); this only catches it up.
     */
    private static final AttachmentType<CompoundTag> SAVED = AttachmentRegistry.<CompoundTag>builder()
            .persistent(CompoundTag.CODEC)
            .copyOnDeath()
            .buildAndRegister(ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "dailies"));

    private DailyQuests() {
    }

    /** Registers {@link #SAVED} (before any player file is read: called from mod init). */
    static void initialize() {
        // A take that answers no more once the server stops never clears its player (review of 1.3.133).
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(
                stopped -> REROLLING.clear());
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

        /**
         * The slots the all-quests bonus waits for: every slot but one whose quest was removed from the registry
         * before it was claimed (review of 1.3.133: it could never be claimed, so the bonus stayed locked all day).
         * Claimed ones always count, so {@link #claimedCount()} reaching this means the bonus is due.
         */
        public int bonusTotal() {
            return (int) slots.stream().filter(slot -> slot.quest() != null || slot.claimed()).count();
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

    /**
     * The player's tag for today; creates/rolls over (and marks dirty) when needed. Review finding 94: only for an
     * online player: an offline one's draw would be at loot stage 1, so they get a provisional tag (today, no slots,
     * today's granted rerolls) that isn't stored; their real draw happens on their first access online.
     */
    private static CompoundTag today(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        CompoundTag stored = HubData.get(server).peek(player).getCompound(TAG);
        if (online != null) {
            stored = newestOf(server, online, stored);
        }
        long today = today();
        boolean sameDay = stored.contains("day") && stored.getLong("day") == today;
        if (sameDay && !stored.getList("slots", Tag.TAG_COMPOUND).isEmpty()) {
            if (online != null) {
                replaceRemoved(server, player, stored);
            }
            return stored;
        }
        if (online == null) {
            CompoundTag provisional = new CompoundTag();
            provisional.putLong("day", today);
            if (sameDay) {
                provisional.putInt("extra_rerolls", stored.getInt("extra_rerolls"));
                provisional.putBoolean("reshuffle", stored.getBoolean("reshuffle"));
            }
            return provisional;
        }
        // A staff reset while they were offline asked for a random set instead of the day's own.
        Random random = sameDay && stored.getBoolean("reshuffle") ? new Random() : new Random(seed(today, player));
        return fresh(server, player, random, sameDay ? stored.getInt("extra_rerolls") : 0);
    }

    /**
     * Review finding 19: hub.dat's tag, or the player file's copy ({@link #SAVED}) when that one is newer (hub.dat lost
     * a claim, bonus or reroll to a hard stop); progress made since stays where hub.dat has more.
     */
    private static CompoundTag newestOf(MinecraftServer server, ServerPlayer player, CompoundTag stored) {
        CompoundTag saved = player.getAttached(SAVED);
        if (saved == null || saved.getLong("stamp") <= stored.getLong("stamp")) {
            return stored;
        }
        CompoundTag restored = saved.copy();
        if (restored.getLong("day") == stored.getLong("day")) {
            ListTag mine = restored.getList("slots", Tag.TAG_COMPOUND);
            ListTag theirs = stored.getList("slots", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(mine.size(), theirs.size()); i++) {
                CompoundTag slot = mine.getCompound(i);
                CompoundTag other = theirs.getCompound(i);
                if (!slot.getBoolean("claimed") && slot.getString("quest").equals(other.getString("quest"))) {
                    slot.putInt("progress", Math.max(slot.getInt("progress"), other.getInt("progress")));
                }
            }
        }
        HubData.get(server).player(player.getUUID()).put(TAG, restored);
        ActiveStatQuests.invalidate();
        EdenLD.LOGGER.info("[Dailies] Restored {}'s dailies from their player file (hub data was older).",
                player.getGameProfile().getName());
        return restored;
    }

    /** A new day for the player: a draw from {@code random}, no progress, no rerolls used but {@code extraRerolls}. */
    private static CompoundTag fresh(MinecraftServer server, UUID player, Random random, int extraRerolls) {
        CompoundTag fresh = new CompoundTag();
        fresh.putLong("day", today());
        ListTag slots = new ListTag();
        for (DailyQuestDefinition quest : draw(drawPool(server, player), random)) {
            slots.add(slotTag(quest));
        }
        fresh.put("slots", slots);
        if (extraRerolls > 0) {
            fresh.putInt("extra_rerolls", extraRerolls);
        }
        write(server, player, fresh);
        return fresh;
    }

    /** Each player's own set for the day (the same all day long, different from other players'). */
    private static long seed(long day, UUID player) {
        return day * 0x9E3779B97F4A7C15L + 0x5EED ^ player.getMostSignificantBits() * 31 ^ player.getLeastSignificantBits();
    }

    /**
     * Unclaimed slots whose quest left the pool (datapack edit) get a replacement. Review finding 20: not at once — a
     * /reload that dropped a quest by mistake (a JSON typo) and the one that fixes it would otherwise cost the slot's
     * progress for the day. The slot is kept (shown as unavailable) through the quest load that lost it and replaced
     * only once a later load (a reload, a restart) still lacks it; a slot that reached its goal (the count stored at
     * draw) is never replaced, and the replacement is never a quest rerolled away today.
     */
    private static void replaceRemoved(MinecraftServer server, UUID player, CompoundTag tag) {
        ListTag slots = tag.getList("slots", Tag.TAG_COMPOUND);
        long load = DailyQuestRegistry.loadId();
        boolean marked = false;
        boolean changed = false;
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag slot = slots.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(slot.getString("quest"));
            if (id != null && DailyQuestRegistry.get(id) != null) {
                if (slot.contains("missing_load")) {
                    slot.remove("missing_load");
                    marked = true;
                }
                continue;
            }
            if (slot.getBoolean("claimed") || slot.contains("count") && slot.getInt("progress") >= slot.getInt("count")) {
                continue;
            }
            if (!slot.contains("missing_load")) {
                slot.putLong("missing_load", load);
                marked = true;
                continue;
            }
            if (slot.getLong("missing_load") == load) {
                continue;
            }
            Set<ResourceLocation> excluded = questIds(slots);
            excluded.addAll(rerolledAway(tag));
            DailyQuestDefinition replacement = randomExcluding(drawPool(server, player), excluded, new Random());
            if (replacement != null) {
                slots.set(i, slotTag(replacement));
                changed = true;
            }
        }
        if (changed) {
            write(server, player, tag);
        } else if (marked) {
            writeProgress(server, player, tag);
        }
    }

    /** A fresh slot; the goal is stored too, so a slot whose quest vanished can still tell it was done (finding 20). */
    private static CompoundTag slotTag(DailyQuestDefinition quest) {
        CompoundTag slot = new CompoundTag();
        slot.putString("quest", quest.id().toString());
        slot.putInt("progress", 0);
        slot.putInt("count", quest.count());
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

    /**
     * Claims, rerolls, draws, admin changes: the open stat quests may differ ({@link ActiveStatQuests}). Review finding
     * 19: stamped, and for an online player copied into their player file ({@link #SAVED}) along with what it paid.
     * The stamp is always past the stored one too (review of 1.3.133): an offline write on a fresh tag (an admin reset,
     * extra rerolls granted on a new day) stamped just "now", which a clock step back could put behind the player
     * file's copy, and that older copy then won at their next login.
     */
    private static void write(MinecraftServer server, UUID player, CompoundTag tag) {
        long stored = HubData.get(server).peek(player).getCompound(TAG).getLong("stamp");
        tag.putLong("stamp", Math.max(System.currentTimeMillis(), Math.max(tag.getLong("stamp"), stored) + 1));
        writeProgress(server, player, tag);
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            online.setAttached(SAVED, tag.copy());
        }
        ActiveStatQuests.invalidate();
    }

    /** Progress only: the open quests stay the same. */
    private static void writeProgress(MinecraftServer server, UUID player, CompoundTag tag) {
        HubData.get(server).player(player).put(TAG, tag);
    }

    /** The targets of the player's open (unclaimed, unfinished) stat quests today. */
    static List<String> openStatTargets(MinecraftServer server, UUID player) {
        List<String> targets = new ArrayList<>();
        ListTag slots = today(server, player).getList("slots", Tag.TAG_COMPOUND);
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag slot = slots.getCompound(i);
            DailyQuestDefinition quest = DailyQuestRegistry.get(ResourceLocation.tryParse(slot.getString("quest")));
            if (quest != null && STAT.equals(quest.objective()) && !slot.getBoolean("claimed")
                    && slot.getInt("progress") < quest.count()) {
                // No target: every stat counts.
                targets.add(quest.target().orElse(ActiveStatQuests.ANY));
            }
        }
        return targets;
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
            writeProgress(server, player.getUUID(), tag);
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

    /** Claims the all-quests bonus once every slot is claimed (a vanished quest's slot aside: {@link State#bonusTotal}). */
    @Nullable
    public static Component claimBonus(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        State state = state(server, player.getUUID());
        if (state.bonusClaimed() || state.bonusTotal() == 0 || state.claimedCount() < state.bonusTotal()) {
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
        if (rerollPick(server, player.getUUID(), ids, rerolledAway(today(server, player.getUUID()))) == null) {
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
            DailyQuestDefinition replacement = rerollPick(server, player.getUUID(), slotIds, rerolledAway(tag));
            if (replacement == null) {
                EconomyCompat.give(player, cost);
                onDone.accept(Component.translatable("message.eden_ld.dailies.no_alternatives"));
                return;
            }
            if (slotIds.get(index) != null) {
                ListTag away = tag.getList("rerolled", Tag.TAG_STRING);
                away.add(net.minecraft.nbt.StringTag.valueOf(slotIds.get(index).toString()));
                tag.put("rerolled", away);
            }
            slots.set(index, slotTag(replacement));
            String counter = usesFree ? "free_used" : "paid_used";
            tag.putInt(counter, tag.getInt(counter) + 1);
            write(server, player.getUUID(), tag);
            onDone.accept(null);
        });
    }

    /**
     * A new random set for today, with progress and rerolls starting over (admin reset). Review finding 94: for an
     * offline player only the wish is stored; the set is drawn at their loot stage on their first access online.
     */
    public static void resetToday(MinecraftServer server, UUID player) {
        if (server.getPlayerList().getPlayer(player) != null) {
            fresh(server, player, new Random(), 0);
            return;
        }
        CompoundTag reset = new CompoundTag();
        reset.putLong("day", today());
        reset.putBoolean("reshuffle", true);
        // Carried forward, so the reset is newer than any copy of the old tag (review of 1.3.133).
        reset.putLong("stamp", HubData.get(server).peek(player).getCompound(TAG).getLong("stamp"));
        write(server, player, reset);
    }

    /**
     * A reroll never duplicates a quest held in another slot, never gives back the quest being replaced, and never
     * brings back a quest rerolled away earlier today; null when the pool has nothing else left. Only from the quests
     * the player may draw now.
     */
    @Nullable
    private static DailyQuestDefinition rerollPick(MinecraftServer server, UUID player, List<ResourceLocation> slotQuests,
                                                   Set<ResourceLocation> rerolledAway) {
        Set<ResourceLocation> excluded = new HashSet<>(rerolledAway);
        for (ResourceLocation quest : slotQuests) {
            if (quest != null) {
                excluded.add(quest);
            }
        }
        return randomExcluding(drawPool(server, player), excluded, new Random());
    }

    /** The quests rerolled away today (they don't come back until tomorrow's draw). */
    private static Set<ResourceLocation> rerolledAway(CompoundTag today) {
        Set<ResourceLocation> ids = new HashSet<>();
        for (Tag entry : today.getList("rerolled", Tag.TAG_STRING)) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
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
