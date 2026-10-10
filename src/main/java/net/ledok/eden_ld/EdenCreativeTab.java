package net.ledok.eden_ld;

import net.ledok.eden_ld.mana.LiquidMana;
import net.ledok.eden_ld.legacy.LegacyItems;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class EdenCreativeTab {

    public static final CreativeModeTab MAIN = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
            .title(Component.translatable("itemGroup.eden_ld.main"))
            .icon(() -> new ItemStack(LegacyItems.ICON))
            .displayItems((parameters, output) -> {
                for (Item item : LegacyItems.ALL) {
                    output.accept(new ItemStack(item));
                }
                output.accept(new ItemStack(LiquidMana.LIQUID_MANA_BUCKET));
            })
            .build();

    public static void initialize() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "main"), MAIN);
    }
}
