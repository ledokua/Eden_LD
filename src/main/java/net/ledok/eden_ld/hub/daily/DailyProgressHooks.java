package net.ledok.eden_ld.hub.daily;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.ledok.eden_ld.hub.HubServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Eden's built-in progress sources: mob kills (projectiles count for their shooter),
 * crafting, — with Spell Engine — finished spell casts (targets: spell id or #spell tag), and — with TreeChop — the
 * logs of a felled tree (break_blocks).
 */
public final class DailyProgressHooks {

    private DailyProgressHooks() {
    }

    public static void initialize() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed) -> {
            if (killer instanceof ServerPlayer player && !(killed instanceof ServerPlayer)
                    && DailyQuests.report(player, DailyQuests.KILL_MOBS, DailyQuests.entityMatcher(killed.getType()), 1)) {
                HubServer.pushDailies(player);
            }
        });
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("spell_engine")) {
            SpellCastHook.register();
        }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("treechop")) {
            TreeChopHook.register();
        }
    }

    /** The only class touching Spell Engine types. The event fires on both sides; count the server's. */
    private static final class SpellCastHook {
        static void register() {
            net.spell_engine.api.spell.event.SpellEvents.SPELL_CAST.register(args -> {
                if (!(args.caster() instanceof ServerPlayer player)) {
                    return;
                }
                var spell = args.spell();
                java.util.function.Predicate<String> matcher = target -> target.startsWith("#")
                        ? spell.tags().anyMatch(tag -> tag.location().toString().equals(target.substring(1)))
                        : spell.unwrapKey().map(key -> key.location().toString().equals(target)).orElse(false);
                if (DailyQuests.report(player, DailyQuests.CAST_SPELLS, matcher, 1)) {
                    HubServer.pushDailies(player);
                }
            });
        }
    }

    /**
     * The only class touching TreeChop types. Chopping a log only shaves it (no drop, no mined stat) and the felled tree's
     * logs are harvested without the player either, so break_blocks never saw them. Each log the fall takes off counts
     * once, as its original block (a chopped log remembers it), the way mining it would have: the logs the tree drops.
     * The stump TreeChop leaves standing is still a log, so it doesn't count until it's mined (through the stat, like any
     * log mined normally: TreeChop off, sneaking). Chops before the fall drop nothing and count nothing.
     */
    private static final class TreeChopHook {
        private record Log(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block) {
        }

        /** A fell's logs with their original blocks, read before the fall (afterwards they're gone). */
        private static final java.util.Map<java.util.UUID, java.util.List<Log>> FELLING = new java.util.HashMap<>();

        static void register() {
            ht.treechop.api.TreeChopEvents.BEFORE_FELL.register((level, player, pos, fellData) -> {
                java.util.List<Log> logs = new java.util.ArrayList<>();
                for (net.minecraft.core.BlockPos log : fellData.getTree().getLogBlocksOrEmpty()) {
                    logs.add(new Log(log.immutable(), ht.treechop.common.chop.ChopUtil.getLogBlock(level, log)));
                }
                FELLING.put(player.getUUID(), logs);
                return true;
            });
            ht.treechop.api.TreeChopEvents.AFTER_FELL.register((level, player, pos, fellData) -> {
                java.util.List<Log> logs = FELLING.remove(player.getUUID());
                if (logs == null) {
                    return true;
                }
                // Only the logs the fall took off: the stump is still a log.
                java.util.Map<net.minecraft.world.level.block.Block, Integer> perBlock = new java.util.LinkedHashMap<>();
                for (Log log : logs) {
                    if (!ht.treechop.common.chop.ChopUtil.isBlockALog(level, log.pos())) {
                        perBlock.merge(log.block(), 1, Integer::sum);
                    }
                }
                boolean changed = false;
                for (var entry : perBlock.entrySet()) {
                    changed |= DailyQuests.report(player, DailyQuests.BREAK_BLOCKS,
                            DailyQuests.blockMatcher(entry.getKey()), entry.getValue());
                }
                if (changed) {
                    HubServer.pushDailies(player);
                }
                return true;
            });
        }
    }

    /**
     * From {@code ServerPlayer.awardStat}: block placing (ITEM_USED of a block item — seeds and
     * berries planted count), mining (BLOCK_MINED), fishing (FISH_CAUGHT), crafting (ITEM_CRAFTED: whatever calls
     * {@code ItemStack.onCraftedBy} — the crafting grid, furnaces, stonecutters, smithing, villager trades and mod
     * stations such as Farmer's Delight's cooking pot; a quest's {@code station} picks where), and the generic
     * {@code stat} objective for anything else vanilla tracks.
     */
    public static void onStat(ServerPlayer player, net.minecraft.stats.Stat<?> stat, int amount) {
        boolean changed = false;
        if (stat.getType() == net.minecraft.stats.Stats.ITEM_USED
                && stat.getValue() instanceof net.minecraft.world.item.BlockItem blockItem) {
            changed = DailyQuests.report(player, DailyQuests.PLACE_BLOCKS,
                    DailyQuests.blockMatcher(blockItem.getBlock()), amount);
        } else if (stat.getType() == net.minecraft.stats.Stats.BLOCK_MINED
                && stat.getValue() instanceof net.minecraft.world.level.block.Block block) {
            changed = DailyQuests.report(player, DailyQuests.BREAK_BLOCKS, DailyQuests.blockMatcher(block), amount);
        } else if (stat.getType() == net.minecraft.stats.Stats.CUSTOM
                && net.minecraft.stats.Stats.FISH_CAUGHT.equals(stat.getValue())) {
            changed = DailyQuests.report(player, DailyQuests.CATCH_FISH, target -> true, amount);
        } else if (stat.getType() == net.minecraft.stats.Stats.ITEM_CRAFTED
                && stat.getValue() instanceof net.minecraft.world.item.Item item) {
            changed = DailyQuests.report(player, DailyQuests.CRAFT_ITEMS, DailyQuests.itemMatcher(new ItemStack(item)), amount);
        }
        String name = stat.getName();
        // Most stats tick every tick (walking, play time): only those a stat quest names go to the quests.
        // …and of those, only stats one of the player's open quests counts right now (1.3.114: movement stats tick for
        // every moving player; going through both full reports each time was ~0.15 ms per tick at 15 players).
        if ((DailyQuestRegistry.countsStat(name) || net.ledok.eden_ld.hub.guild.quest.GuildQuestRegistry.countsStat(name))
                && ActiveStatQuests.wants(player, name)) {
            changed |= DailyQuests.report(player, DailyQuests.STAT, target -> DailyObjectives.statMatches(target, name), amount);
        }
        if (changed) {
            HubServer.pushDailies(player);
        }
    }

    public static void onAte(ServerPlayer player, ItemStack food) {
        if (DailyQuests.report(player, DailyQuests.EAT_FOOD, DailyQuests.itemMatcher(food), 1)) {
            HubServer.pushDailies(player);
        }
    }

}
