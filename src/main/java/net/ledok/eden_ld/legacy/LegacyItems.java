package net.ledok.eden_ld.legacy;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Items carried over from Yggdrasil_LD. They keep their original
 * {@code yggdrasil_ld:} registry ids so existing worlds and quests
 * (FTB tasks, loot tables) keep working. New Eden_LD content must
 * use the {@code eden_ld:} namespace instead.
 */
public class LegacyItems {

    /** Every legacy item, in creative tab order. */
    public static final List<Item> ALL = new ArrayList<>();

    public static final Item ICON = register("icon", new Item(new Item.Properties()) {
        @Override
        public boolean isFoil(ItemStack stack) {
            return true;
        }
    });

    // Quest and boss materials
    public static final Item ANCIENT_ICE_SHARD = simple("ancient_ice_shard");
    public static final Item NETHERITE_BOTTLE = simple("netherite_bottle");
    public static final Item BAT_WING = simple("bat_wing");
    public static final Item BLOOD_IN_A_BOTTLE = simple("blood_in_a_bottle");
    public static final Item BINDING_CHAIN = simple("binding_chain");
    public static final Item CURSED_SKULL = simple("cursed_skull");
    public static final Item ENT_ROOT = simple("ent_root");
    public static final Item SACRED_LEAF = simple("sacred_leaf");
    public static final Item REDSTONE_CRYSTAL = simple("redstone_crystal");
    public static final Item WITHERED_VINE = simple("withered_vine");
    public static final Item PILE_OF_ASH = simple("pile_of_ash");
    public static final Item PURE_GOLD_INGOT = simple("pure_gold_ingot");
    public static final Item HOLY_WATER_FLASK = simple("holy_water_flask");
    public static final Item NETHERITE_BOWL = simple("netherite_bowl");
    public static final Item RARE_CARROT = simple("rare_carrot");
    public static final Item RARE_MEAT = simple("rare_meat");


    private static Item simple(String id) {
        return register(id, new Item(new Item.Properties()));
    }

    private static Item register(String id, Item item) {
        Item registered = Registry.register(BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(EdenLD.LEGACY_NAMESPACE, id), item);
        ALL.add(registered);
        return registered;
    }

    public static void initialize() {
    }
}
