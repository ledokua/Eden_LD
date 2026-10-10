package net.ledok.eden_ld.client.compat;

import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.client.hub.ModerationScreen;
import net.minecraft.world.item.ItemStack;

/**
 * Optional EMI support, loaded only by EMI (the "emi" entrypoint): its panels show beside the
 * Moderation window, and items drag from them onto the mail's attachment slots. With Stylish Effects,
 * its panels also keep clear of the effect widgets ({@link StylishEffectsEmi}).
 */
public class EdenEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addScreenBoundsProvider(ModerationScreen.class, screen -> {
            int[] b = screen.screenBounds();
            return new Bounds(b[0], b[1], b[2], b[3]);
        });
        registry.addDragDropHandler(ModerationScreen.class, (screen, ingredient, x, y) -> {
            ItemStack stack = first(ingredient);
            return !stack.isEmpty() && screen.emiDrop(x, y, stack);
        });
        if (FabricLoader.getInstance().isModLoaded("stylisheffects")) {
            StylishEffectsEmi.register(registry);
        }
    }

    private static ItemStack first(EmiIngredient ingredient) {
        for (EmiStack stack : ingredient.getEmiStacks()) {
            ItemStack item = stack.getItemStack();
            if (!item.isEmpty()) {
                return item;
            }
        }
        return ItemStack.EMPTY;
    }
}
