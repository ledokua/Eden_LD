package net.ledok.eden_ld.hub.daily;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Crafts that undo another craft don't count for "craft" quests (review finding 54, 1.3.130): dried kelp from its
 * block, crops from their crates, honey bottles from a honey block, nuggets from an ingot. Otherwise nine dried kelp
 * crafted into a block and back made 9 "food crafted" per shift-click, forever. A crafting recipe undoes a craft when
 * one of its ingredients is itself crafted from the recipe's result. Crafting-grid crafts are marked by the result
 * slot ({@code ResultSlotUncraftMixin}) while vanilla awards the crafted stat; {@link DailyProgressHooks} skips them.
 */
public final class Uncrafting {

    /** Server thread: a crafting-grid craft that undoes another one is being awarded now. */
    private static boolean awarding;
    /** Result item → every item any crafting recipe for it takes; rebuilt when the recipe manager changes. */
    private static Map<Item, Set<Item>> madeFrom = Map.of();
    @Nullable
    private static RecipeManager indexed;

    private Uncrafting() {
    }

    /** Whether the craft being awarded now undoes another craft. */
    public static boolean awarding() {
        return awarding;
    }

    /** Runs the result slot's award with the mark set when {@code recipe} undoes another craft. */
    public static void award(Level level, @Nullable RecipeHolder<?> recipe, Runnable award) {
        if (level.isClientSide()) {
            // The client's predicted craft: no stats there, and the mark is the server thread's alone.
            award.run();
            return;
        }
        boolean undo = recipe != null && undoes(level, recipe.value());
        boolean outer = awarding;
        awarding = outer || undo;
        try {
            award.run();
        } finally {
            awarding = outer;
        }
    }

    private static boolean undoes(Level level, Recipe<?> recipe) {
        if (!(recipe instanceof CraftingRecipe)) {
            return false;
        }
        RegistryAccess registries = level.registryAccess();
        ItemStack result = recipe.getResultItem(registries);
        if (result.isEmpty()) {
            return false;
        }
        Map<Item, Set<Item>> index = index(level.getRecipeManager(), registries);
        for (Ingredient ingredient : recipe.getIngredients()) {
            for (ItemStack option : ingredient.getItems()) {
                Set<Item> inputs = index.get(option.getItem());
                if (inputs != null && inputs.contains(result.getItem())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<Item, Set<Item>> index(RecipeManager recipes, RegistryAccess registries) {
        if (indexed != recipes) {
            Map<Item, Set<Item>> map = new HashMap<>();
            for (RecipeHolder<CraftingRecipe> holder : recipes.getAllRecipesFor(RecipeType.CRAFTING)) {
                ItemStack result;
                try {
                    result = holder.value().getResultItem(registries);
                } catch (RuntimeException e) {
                    continue;
                }
                if (result.isEmpty()) {
                    continue;
                }
                Set<Item> inputs = map.computeIfAbsent(result.getItem(), item -> new HashSet<>());
                for (Ingredient ingredient : holder.value().getIngredients()) {
                    for (ItemStack option : ingredient.getItems()) {
                        inputs.add(option.getItem());
                    }
                }
            }
            madeFrom = map;
            indexed = recipes;
        }
        return madeFrom;
    }
}
