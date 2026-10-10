package net.ledok.eden_ld.client.compat;

import dev.emi.trinkets.TrinketScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;

/**
 * How far Trinkets' slot columns reach left of the inventory, so Stylish Effects draws its effect widgets
 * past them instead of under them ({@code StylishEffectsRendererMixin}). Nothing without Trinkets.
 */
public final class TrinketsEffectsArea {

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("trinkets");

    private TrinketsEffectsArea() {
    }

    /**
     * Units to move the effects left on {@code screen}: Trinkets' columns, when the effects start at the
     * panel's left edge ({@code startX}); 0 for the right side, other screens or no extra columns.
     */
    public static int shift(Object screen, int startX) {
        return LOADED ? Columns.reach(screen, startX) : 0;
    }

    /** Apart, so Trinkets' classes only load when it's installed. */
    private static final class Columns {

        static int reach(Object screen, int startX) {
            if (!(screen instanceof TrinketScreen trinkets) || trinkets.trinkets$isRecipeBookOpen()
                    || screen instanceof CreativeModeInventoryScreen creative && !creative.isInventoryOpen()
                    || startX > trinkets.trinkets$getX()) {
                return 0;
            }
            int groups = trinkets.trinkets$getHandler().trinkets$getGroupCount();
            if (groups <= 0) {
                return 0;
            }
            // As Trinkets draws them (TrinketScreenManager#drawExtraGroups): columns of 4 going left,
            // 22 units for the first, 18 for each more.
            return 22 + 18 * ((groups - 1) / 4);
        }
    }
}
