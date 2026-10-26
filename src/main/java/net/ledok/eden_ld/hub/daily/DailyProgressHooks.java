package net.ledok.eden_ld.hub.daily;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.ledok.eden_ld.hub.HubServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Eden's built-in progress sources: mob kills (projectiles count for their shooter),
 * crafting, and — with Spell Engine — finished spell casts (targets: spell id or #spell tag).
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
        changed |= DailyQuests.report(player, DailyQuests.STAT, target -> DailyObjectives.statMatches(target, name), amount);
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
