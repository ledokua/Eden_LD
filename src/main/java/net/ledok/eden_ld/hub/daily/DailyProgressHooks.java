package net.ledok.eden_ld.hub.daily;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.ledok.eden_ld.hub.HubServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Eden's built-in progress sources: mob kills (projectiles count for their shooter),
 * crafting, — with Spell Engine — finished spell casts (targets: spell id or #spell tag), and — with TreeChop — the
 * logs of a felled tree (break_blocks).
 */
public final class DailyProgressHooks {

    /**
     * Review finding 52: players whose dailies moved, sent at most every {@link #PUSH_EVERY_TICKS} (a walking player's
     * travel quest moved them every tick: a full Dailies packet, and once free rerolls were used a balance lookup, about
     * 20 times a second).
     */
    private static final Set<UUID> DIRTY = new LinkedHashSet<>();
    private static final int PUSH_EVERY_TICKS = 10;

    /** Review finding 93: players inside {@code Player.eat} (its ITEM_USED stat is no placement). */
    private static final Set<UUID> EATING = new HashSet<>();

    /** Review finding 92: a block a player is breaking right now, as the break event saw it (cleared every tick). */
    private record Breaking(Block block, boolean counts) {
    }

    private static final Map<UUID, Breaking> BREAKING = new HashMap<>();

    private record Spot(ResourceKey<Level> dimension, long pos) {
    }

    /**
     * Review finding 92: the latest blocks players placed (any player; crops aside, they count by ripeness), so mining
     * one again isn't counted. In memory and bounded: a restart forgets them, and a piston-moved block isn't followed —
     * enough against the place-and-mine loop, at a few hundred KB at most.
     */
    private static final int PLACED_LIMIT = 4096;
    private static final Map<Spot, Block> PLACED = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Spot, Block> eldest) {
            return size() > PLACED_LIMIT;
        }
    };

    private DailyProgressHooks() {
    }

    public static void initialize() {
        DailyQuests.initialize();
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed) -> {
            if (killer instanceof ServerPlayer player && !(killed instanceof ServerPlayer)
                    && DailyQuests.report(player, DailyQuests.KILL_MOBS, DailyQuests.entityMatcher(killed.getType()), 1)) {
                changed(player);
            }
        });
        // Review finding 92: what is being broken (BEFORE runs ahead of the mined stat, in the same call); AFTER means
        // it is gone, so the spot isn't player-placed any more.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                BREAKING.put(serverPlayer.getUUID(), new Breaking(state.getBlock(), countsAsBroken(level, pos, state)));
            }
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!PLACED.isEmpty() && !level.isClientSide()) {
                PLACED.remove(new Spot(level.dimension(), pos.asLong()));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            BREAKING.clear();
            if (DIRTY.isEmpty() || server.getTickCount() % PUSH_EVERY_TICKS != 0) {
                return;
            }
            List<UUID> players = List.copyOf(DIRTY);
            DIRTY.clear();
            for (UUID id : players) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) {
                    HubServer.pushDailies(player);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            DIRTY.clear();
            EATING.clear();
            BREAKING.clear();
            PLACED.clear();
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
                    changed(player);
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
                        // A log a player placed there isn't counted (review finding 92, of 1.3.133: a "tree" of placed
                        // logs felled whole got around the place-and-mine check); it's gone now, so forgotten too.
                        Block placed = PLACED.isEmpty() ? null
                                : PLACED.remove(new Spot(level.dimension(), log.pos().asLong()));
                        if (placed != null && placed == log.block()) {
                            continue;
                        }
                        perBlock.merge(log.block(), 1, Integer::sum);
                    }
                }
                boolean changed = false;
                for (var entry : perBlock.entrySet()) {
                    changed |= DailyQuests.report(player, DailyQuests.BREAK_BLOCKS,
                            DailyQuests.blockMatcher(entry.getKey()), entry.getValue());
                }
                if (changed) {
                    changed(player);
                }
                return true;
            });
        }
    }

    /**
     * From {@code ServerPlayer.awardStat}: block placing (ITEM_USED of a block item — seeds and
     * berries planted count, eaten ones don't), mining (BLOCK_MINED: ripe crops only, not blocks a player placed), fishing (FISH_CAUGHT), crafting (ITEM_CRAFTED: whatever calls
     * {@code ItemStack.onCraftedBy} — the crafting grid, furnaces, stonecutters, smithing, villager trades and mod
     * stations such as Farmer's Delight's cooking pot; a quest's {@code station} picks where), and the generic
     * {@code stat} objective for anything else vanilla tracks.
     */
    public static void onStat(ServerPlayer player, net.minecraft.stats.Stat<?> stat, int amount) {
        boolean changed = false;
        if (stat.getType() == net.minecraft.stats.Stats.ITEM_USED
                && stat.getValue() instanceof net.minecraft.world.item.BlockItem blockItem) {
            if (!EATING.contains(player.getUUID())) {
                changed = DailyQuests.report(player, DailyQuests.PLACE_BLOCKS,
                        DailyQuests.blockMatcher(blockItem.getBlock()), amount);
            }
        } else if (stat.getType() == net.minecraft.stats.Stats.BLOCK_MINED
                && stat.getValue() instanceof net.minecraft.world.level.block.Block block) {
            // Review finding 92: an unripe crop or a block a player placed doesn't count (only known when the break
            // event saw this block this tick; any other source of the stat counts as before).
            Breaking breaking = BREAKING.remove(player.getUUID());
            if (breaking == null || breaking.block() != block || breaking.counts()) {
                changed = DailyQuests.report(player, DailyQuests.BREAK_BLOCKS, DailyQuests.blockMatcher(block), amount);
            }
        } else if (stat.getType() == net.minecraft.stats.Stats.CUSTOM
                && net.minecraft.stats.Stats.FISH_CAUGHT.equals(stat.getValue())) {
            changed = DailyQuests.report(player, DailyQuests.CATCH_FISH, target -> true, amount);
        } else if (stat.getType() == net.minecraft.stats.Stats.ITEM_CRAFTED
                && stat.getValue() instanceof net.minecraft.world.item.Item item && !Uncrafting.awarding()) {
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
            changed(player);
        }
    }

    public static void onAte(ServerPlayer player, ItemStack food) {
        if (DailyQuests.report(player, DailyQuests.EAT_FOOD, DailyQuests.itemMatcher(food), 1)) {
            changed(player);
        }
    }

    /** Review finding 52: the Dailies tab is sent on one of the next ticks, once however often it moved meanwhile. */
    private static void changed(ServerPlayer player) {
        DIRTY.add(player.getUUID());
    }

    /** {@code PlayerEatMixin} (review finding 93): the player starts or stops eating. */
    public static void eating(ServerPlayer player, boolean eating) {
        if (eating) {
            EATING.add(player.getUUID());
        } else {
            EATING.remove(player.getUUID());
        }
    }

    /** {@code BlockItemPlacedMixin} (review finding 92): a player placed this block here. */
    public static void placed(ServerPlayer player, BlockPos pos, BlockState state) {
        if (!isCrop(state.getBlock())) {
            PLACED.put(new Spot(player.level().dimension(), pos.asLong()), state.getBlock());
        }
    }

    /**
     * Review finding 92: whether breaking this counts for break_blocks. A crop only when ripe (a stem never: its melon
     * or pumpkin is the harvest), anything else unless a player placed this very block here.
     */
    private static boolean countsAsBroken(Level level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (isCrop(block)) {
            return ripe(state);
        }
        return PLACED.isEmpty() || PLACED.get(new Spot(level.dimension(), pos.asLong())) != block;
    }

    private static boolean isCrop(Block block) {
        return block instanceof CropBlock || block instanceof StemBlock || block instanceof AttachedStemBlock
                || block instanceof NetherWartBlock || block instanceof CocoaBlock || block instanceof SweetBerryBushBlock
                || block instanceof PitcherCropBlock;
    }

    private static boolean ripe(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        if (block instanceof StemBlock || block instanceof AttachedStemBlock) {
            return false;
        }
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && "age".equals(property.getName())) {
                return state.getValue(age) >= age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
            }
        }
        return true;
    }

}
