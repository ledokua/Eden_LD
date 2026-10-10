package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.client.EdenLDClient;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The hub's button in the player's inventory, under the crafting grid — where the recipe book button
 * would be; Not Enough Recipe Book removes that on Fallen Eden, and where it's kept the hub button sits
 * beside it. It opens the hub ({@link HubScreen#open}), and closing the hub comes back to the inventory.
 */
public final class InventoryHubButton extends AbstractButton {

    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID,
            "textures/gui/icons/tab_home.png");
    private static final int SIZE = 18;
    /** Under the crafting grid's left column (in the inventory's own units). */
    private static final int X = 97;
    private static final int Y = 58;
    /** Beside a recipe book button (vanilla's sits at 104, 61). */
    private static final int X_BESIDE_RECIPE_BOOK = 126;
    private static final int Y_BESIDE_RECIPE_BOOK = 61;

    private final AbstractContainerScreenAccessor screen;
    private final int offsetX;
    private final int offsetY;

    private InventoryHubButton(InventoryScreen screen, boolean recipeBook) {
        super(0, 0, SIZE, SIZE, Component.translatable("gui.eden_ld.hub.title"));
        this.screen = (AbstractContainerScreenAccessor) screen;
        this.offsetX = recipeBook ? X_BESIDE_RECIPE_BOOK : X;
        this.offsetY = recipeBook ? Y_BESIDE_RECIPE_BOOK : Y;
        setTooltip(Tooltip.create(Component.translatable("gui.eden_ld.inventory.hub",
                EdenLDClient.hubKey.getTranslatedKeyMessage())));
        place();
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // Only where the server runs Eden (elsewhere the hub would wait for data forever).
            if (screen instanceof InventoryScreen inventory && ClientPlayNetworking.canSend(HubPayloads.ProfileRequest.TYPE)) {
                Screens.getButtons(screen).add(new InventoryHubButton(inventory, recipeBook(inventory)));
            }
        });
    }

    /** Whether the inventory kept vanilla's recipe book button (Not Enough Recipe Book's other modes). */
    private static boolean recipeBook(InventoryScreen inventory) {
        int left = ((AbstractContainerScreenAccessor) inventory).eden_ld$leftPos();
        return Screens.getButtons(inventory).stream()
                .anyMatch(widget -> widget instanceof ImageButton && widget.getX() == left + 104);
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
