package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * «Звернення» in the player's inventory, left of the hub's button ({@link InventoryHubButton}): opens the hub on the
 * reports tab, and closing it comes back to the inventory. Only where the server has the tab (1.3.83+).
 */
final class InventoryReportButton extends AbstractButton {

    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID,
            "textures/gui/icons/tab_reports.png");

    private final AbstractContainerScreenAccessor screen;
    private final int offsetX;
    private final int offsetY;

    InventoryReportButton(InventoryScreen screen, int offsetX, int offsetY) {
        super(0, 0, InventoryHubButton.SIZE, InventoryHubButton.SIZE, Component.translatable("gui.eden_ld.hub.tab.reports"));
        this.screen = (AbstractContainerScreenAccessor) screen;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        setTooltip(Tooltip.create(Component.translatable("gui.eden_ld.inventory.reports")));
        place();
    }

    private void place() {
        setPosition(screen.eden_ld$leftPos() + offsetX, screen.eden_ld$topPos() + offsetY);
    }

    @Override
    public void onPress() {
        HubScreen.open("reports", true);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        place();
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        graphics.blit(ICON, getX() + 1, getY() + 1, 0, 0, 16, 16, 16, 16);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
