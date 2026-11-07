package net.ledok.eden_ld.item;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Half-made items for the server's Create recipes. The recipes themselves are DataRewriter files on the
 * server ({@code config/datarewriter/}); these items only give their sequenced assemblies a transitional
 * item of their own, so a half-made one doesn't stack or mix with the plain item it started from.
 */
public final class CraftingItems {

    /** Transitional item of {@code reset_scroll_assembly} (paper → Сувій забуття, Pufferfish's Skills Additions). */
    public static final Item UNFINISHED_RESET_SCROLL = register("unfinished_reset_scroll",
            new Item(new Item.Properties().stacksTo(1)));

    private CraftingItems() {
    }

    private static Item register(String id, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, id), item);
    }

    public static void initialize() {
    }
}
