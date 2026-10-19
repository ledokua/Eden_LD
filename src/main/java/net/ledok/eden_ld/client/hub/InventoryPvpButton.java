package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.client.ClientCombatTag;
import net.ledok.eden_ld.combat.PvpMode;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.mixin.AbstractContainerScreenAccessor;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.presentation.Spotlight;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * The PvP toggle in the player's inventory (docs/PLAN-pvp-toggle.md), right of the hub button and styled like it: the
 * Duels tab's crossed swords, lit while the player's PvP is on and dimmed while off, with a shade over it that shrinks
 * as the switch cooldown runs out (as an item's cooldown in the hotbar). The tooltip says the state, the cooldown or
 * combat time left, and when the dimension overrides the choice ("PvP is off here"). A click asks the server
 * ({@link HubPayloads.PvpToggle}), which checks the cooldown and the combat tag and answers in the action bar. Added by
 * {@link InventoryHubButton} only where the server runs the toggle.
 */
public final class InventoryPvpButton extends AbstractButton {

    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID,
            "textures/gui/icons/tab_duels.png");
    /** The switch cooldown, for the shade's height (PvpSwitch.COOLDOWN_SECONDS). */
    private static final float COOLDOWN_MILLIS = 60_000F;

    private final AbstractContainerScreenAccessor screen;
    private final int offsetX;
    private final int offsetY;
    /** What the tooltip was built for (state, mode, seconds left); it's built again only when that changes. */
    private long tooltipKey = -1;

    InventoryPvpButton(InventoryScreen screen, int offsetX, int offsetY) {
        super(0, 0, InventoryHubButton.SIZE, InventoryHubButton.SIZE, Component.translatable("gui.eden_ld.inventory.pvp"));
        this.screen = (AbstractContainerScreenAccessor) screen;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        place();
    }

    /** Tutorials can point at the button (NPCs_LD dialog lines). Client init. */
    public static void register() {
        Spotlight.registerLocator((screen, id) -> {
            if (!id.equals(HubSpotlights.INVENTORY_PVP_BUTTON) || !(screen instanceof InventoryScreen)) return null;
            for (var widget : Screens.getButtons(screen)) {
                if (widget instanceof InventoryPvpButton button && button.visible) {
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
        if (ClientPlayNetworking.canSend(HubPayloads.PvpToggle.TYPE)) {
            ClientPlayNetworking.send(new HubPayloads.PvpToggle(!ClientPvp.on()));
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        place();
        if (isHovered()) {
            updateTooltip();
        }
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    /** The crossed swords in place of a label: lit while on, dimmed while off, shaded during the cooldown. */
    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        boolean on = ClientPvp.on();
        if (!on) {
            graphics.setColor(0.45F, 0.45F, 0.45F, 0.8F);
        }
        graphics.blit(ICON, getX() + 1, getY() + 1, 0, 0, 16, 16, 16, 16);
        if (!on) {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        long cooldown = ClientPvp.cooldownMillis();
        if (cooldown > 0) {
            // As vanilla shades an item on cooldown: the bottom part, shrinking down as it runs out.
            int shade = Math.min(16, (int) Math.ceil(16 * cooldown / COOLDOWN_MILLIS));
            graphics.fill(getX() + 1, getY() + 17 - shade, getX() + 17, getY() + 17, 0x7FFFFFFF);
        }
    }

    /** Builds the tooltip again when what it says has changed (at most once a second while a timer runs). */
    private void updateTooltip() {
        boolean on = ClientPvp.on();
        PvpMode mode = ClientPvp.mode();
        long cooldown = seconds(ClientPvp.cooldownMillis());
        Minecraft minecraft = Minecraft.getInstance();
        long combat = minecraft.player != null && ClientCombatTag.isTagged(minecraft.player)
                ? seconds(ClientCombatTag.millisLeft()) : 0;
        long key = (on ? 1 : 0) | (long) mode.ordinal() << 1 | Math.min(cooldown, 0xFFFF) << 3
                | Math.min(combat, 0xFFFF) << 19;
        if (key == tooltipKey) {
            return;
        }
        tooltipKey = key;
        MutableComponent text = Component.translatable(on ? "gui.eden_ld.inventory.pvp.on" : "gui.eden_ld.inventory.pvp.off")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
        text.append("\n").append(Component.translatable(on ? "gui.eden_ld.inventory.pvp.on.hint"
                : "gui.eden_ld.inventory.pvp.off.hint").withStyle(ChatFormatting.GRAY));
        if (mode != PvpMode.TOGGLE) {
            text.append("\n").append(Component.translatable(mode == PvpMode.OFF ? "gui.eden_ld.inventory.pvp.here_off"
                    : "gui.eden_ld.inventory.pvp.here_on").withStyle(ChatFormatting.GOLD));
        }
        if (combat > 0) {
            text.append("\n").append(Component.translatable("gui.eden_ld.inventory.pvp.combat", combat)
                    .withStyle(ChatFormatting.RED));
        } else if (cooldown > 0) {
            text.append("\n").append(Component.translatable("gui.eden_ld.inventory.pvp.cooldown", cooldown)
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            text.append("\n").append(Component.translatable(on ? "gui.eden_ld.inventory.pvp.click_off"
                    : "gui.eden_ld.inventory.pvp.click_on").withStyle(ChatFormatting.DARK_GRAY));
        }
        setTooltip(Tooltip.create(text));
    }

    private static long seconds(long millis) {
        return (millis + 999) / 1000;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
