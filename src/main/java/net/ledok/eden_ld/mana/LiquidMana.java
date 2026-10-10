package net.ledok.eden_ld.mana;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * Liquid Mana: a healing fluid (see {@link LiquidManaBlock}), under {@code eden_ld:} ids
 * (moved from the legacy {@code yggdrasil_ld:} namespace on 2026-09-28).
 */
public class LiquidMana {


    public static final FlowingFluid STILL_LIQUID_MANA = Registry.register(BuiltInRegistries.FLUID,
            id("liquid_mana"), new LiquidManaFluid.Still());
    public static final FlowingFluid FLOWING_LIQUID_MANA = Registry.register(BuiltInRegistries.FLUID,
            id("flowing_liquid_mana"), new LiquidManaFluid.Flowing());

    public static final Block LIQUID_MANA_BLOCK = Registry.register(BuiltInRegistries.BLOCK,
            id("liquid_mana_block"),
            new LiquidManaBlock(STILL_LIQUID_MANA, BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)
                    .noCollission().strength(100.0F).noLootTable()));

    public static final Item LIQUID_MANA_BUCKET = Registry.register(BuiltInRegistries.ITEM,
            id("liquid_mana_bucket"),
            new BucketItem(STILL_LIQUID_MANA, new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, path);
    }


    public static void initialize() {
    }
}
