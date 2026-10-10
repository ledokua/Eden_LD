package net.ledok.eden_ld.hub.moderation;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.flag.FeatureFlags;

/**
 * The slot-less, client-only menu behind the Moderation window. EMI (like JEI/REI) only attaches
 * its item panels to container screens, so the window is one; the server never opens this menu.
 * Its own registered type lets overlay mods tell the window apart.
 */
public final class ModerationMenu extends AbstractContainerMenu {

    public static final MenuType<ModerationMenu> TYPE = new MenuType<>(ModerationMenu::new, FeatureFlags.VANILLA_SET);

    public ModerationMenu(int containerId, Inventory inventory) {
        super(TYPE, containerId);
    }

    public static void register() {
        Registry.register(BuiltInRegistries.MENU, ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "moderation"), TYPE);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
