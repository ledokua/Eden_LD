package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.client.EdenLDClient;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.mixin.AbstractContainerScreenAccessor;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.presentation.Spotlight;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The hub's button in the player's inventory, above the panel's top-right edge in a row with Codex_LD's book
 * (which sits at 158, -17): under the crafting grid other mods' buttons came and went (Not Enough Recipe Book 0.6 put
 * one under ours). It opens the hub ({@link HubScreen#open}), and closing the hub comes back to the inventory. The PvP
 * button ({@link InventoryPvpButton}) sits right of it, between it and the book, where the server runs the toggle.
 */
public final class InventoryHubButton extends AbstractButton {

    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID,
            "textures/gui/icons/tab_home.png");
    static final int SIZE = 18;
    /** Between this button and the PvP button. */
    private static final int GAP = 2;
    /** Left of the Codex book, bottoms level with it (in the inventory's own units). */
    private static final int X = 118;
    private static final int Y = -19;

    private final AbstractContainerScreenAccessor screen;
    private final int offsetX;
    private final int offsetY;

    private InventoryHubButton(InventoryScreen screen) {
        super(0, 0, SIZE, SIZE, Component.translatable("gui.eden_ld.hub.title"));
        this.screen = (AbstractContainerScreenAccessor) screen;
        this.offsetX = X;
        this.offsetY = Y;
        setTooltip(Tooltip.create(Component.translatable("gui.eden_ld.inventory.hub",
                EdenLDClient.hubKey.getTranslatedKeyMessage())));
        place();
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // Only where the server runs Eden (elsewhere the hub would wait for data forever).
            if (screen instanceof InventoryScreen inventory && ClientPlayNetworking.canSend(HubPayloads.ProfileRequest.TYPE)) {
                InventoryHubButton hub = new InventoryHubButton(inventory);
                Screens.getButtons(screen).add(hub);
                // An older Eden server has no toggle: no button (its players count as PvP off).
                if (ClientPlayNetworking.canSend(HubPayloads.PvpToggle.TYPE)) {
                    Screens.getButtons(screen).add(new InventoryPvpButton(inventory, hub.offsetX + SIZE + GAP, hub.offsetY));
                }
            }
        });
        // Tutorials can point at the button like at any Hub part.
        Spotlight.registerLocator((screen, id) -> {
            if (!id.equals(HubSpotlights.INVENTORY_BUTTON) || !(screen instanceof InventoryScreen)) return null;
            for (var widget : Screens.getButtons(screen)) {
                if (widget instanceof InventoryHubButton button && button.visible) {
                    button.place();
                    return new Rect(button.getX(), button.getY(), button.getWidth(), button.getHeight());
                }
            }
            return null;
        });
    }

    /** Follows the panel, which a recipe book opening beside it moves. */
    private void place() {
        setPosition(screen.eden_ld$leftPos() + offsetX, screen.eden_ld$topPos() + offsetY);
    }

    @Override
    public void onPress() {
        HubScreen.open();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        place();
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    /** The hub's house icon in place of a label. */
    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        graphics.blit(ICON, getX() + 1, getY() + 1, 0, 0, 16, 16, 16, 16);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
