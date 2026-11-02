package net.ledok.eden_ld.client.compat;

import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import net.ledok.eden_ld.salvage.PowerLevelIngredient;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * Labels recipes that take gear by power level ({@link PowerLevelIngredient}, the salvage press) with the level
 * range, top left: EMI shows such an ingredient as its base items, folded into their tag, so every press tier
 * would look the same. Create's recipes in EMI have no backing recipe, only an id built from it
 * ({@code create:/pressing/<namespace>/<path>}), so they're found by that id's end.
 */
final class SalvageEmi {

    private static final int LABEL_COLOR = 0x888888; // Create's own recipe labels

    private SalvageEmi() {
    }

    static void register(EmiRegistry registry) {
        Map<ResourceLocation, PowerLevelIngredient> byId = new HashMap<>();
        Map<String, PowerLevelIngredient> byIdEnd = new HashMap<>();
        for (RecipeHolder<?> holder : registry.getRecipeManager().getRecipes()) {
            PowerLevelIngredient level = powerLevel(holder);
            if (level != null) {
                byId.put(holder.id(), level);
                byIdEnd.put("/" + holder.id().getNamespace() + "/" + holder.id().getPath(), level);
            }
        }
        if (byId.isEmpty()) {
            return;
        }
        registry.addRecipeDecorator((recipe, widgets) -> {
            PowerLevelIngredient level = find(recipe, byId, byIdEnd);
            if (level != null) {
                widgets.addText(Component.translatable("emi.eden_ld.power_level", level.rangeText()),
                        2, 2, LABEL_COLOR, false);
            }
        });
    }

    private static PowerLevelIngredient powerLevel(RecipeHolder<?> holder) {
        try {
            for (Ingredient ingredient : holder.value().getIngredients()) {
                if (ingredient.getCustomIngredient() instanceof PowerLevelIngredient level) {
                    return level;
                }
            }
        } catch (RuntimeException ignored) {
            // another mod's recipe that can't list its ingredients: not ours anyway
        }
        return null;
    }

    private static PowerLevelIngredient find(EmiRecipe recipe, Map<ResourceLocation, PowerLevelIngredient> byId,
                                             Map<String, PowerLevelIngredient> byIdEnd) {
        RecipeHolder<?> backing = recipe.getBackingRecipe();
        if (backing != null) {
            return byId.get(backing.id());
        }
        ResourceLocation id = recipe.getId();
        if (id == null) {
            return null;
        }
        String path = id.getPath();
        for (Map.Entry<String, PowerLevelIngredient> entry : byIdEnd.entrySet()) {
            if (path.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
