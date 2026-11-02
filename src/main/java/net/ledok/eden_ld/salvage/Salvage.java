package net.ledok.eden_ld.salvage;

import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredientSerializer;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Gear salvage with Create: a mechanical press turns loot gear into weapon or armor scrap of the tier its
 * power level falls in (I: 1–10 … VI: 51+), crushing wheels turn scrap into a chance of Limit Breaker
 * enhancement stones. Eden adds the 12 scrap items and the {@code eden_ld:power_level} ingredient
 * ({@link PowerLevelIngredient}); the recipes themselves are a DataRewriter file on the server
 * ({@code config/datarewriter/salvage.json5}), so their numbers change with {@code /reload}. Gear without a
 * power level (crafted) has no recipe, so crafting can't feed the press.
 */
public final class Salvage {

    /** Weapon scrap I–VI, index 0 = tier I. */
    public static final List<ScrapItem> WEAPON_SCRAP = new ArrayList<>();
    /** Armor scrap I–VI, index 0 = tier I. */
    public static final List<ScrapItem> ARMOR_SCRAP = new ArrayList<>();

    static {
        for (int tier = 1; tier <= PowerLevels.TIERS; tier++) {
            WEAPON_SCRAP.add(register("weapon_scrap_" + tier, tier));
        }
        for (int tier = 1; tier <= PowerLevels.TIERS; tier++) {
            ARMOR_SCRAP.add(register("armor_scrap_" + tier, tier));
        }
    }

    private Salvage() {
    }

    private static ScrapItem register(String id, int tier) {
        return Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, id),
                new ScrapItem(tier, new Item.Properties()));
    }

    /** Both sides: the items, and the ingredient type (recipes sync to clients with it). */
    public static void initialize() {
        CustomIngredientSerializer.register(PowerLevelIngredient.SERIALIZER);
    }
}
