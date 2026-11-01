package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.chat.ChatLinks;
import net.ledok.eden_ld.client.chat.ChatTabActions;
import net.ledok.eden_ld.client.chat.GifViewer;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In chat (and the hub's party and guild chat), clicking a [GIF] or [Sticker] link opens Eden's
 * viewer instead of the "open link?" prompt. In the new chat look a line's [Local] tag switches
 * channel right here instead of running {@code /ch local} (ChatTabActions.answerIn). Xaero's Minimap [Add] on a shared
 * waypoint is a slash-less "command" ({@code xaero_waypoint_add:…}) that vanilla 1.21.1 refuses to run from a click;
 * it goes to Xaero's send-chat hook instead, which adds the waypoint (and sends nothing).
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final boolean XAERO_MINIMAP =
            net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("xaerominimap");

    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void eden_ld$viewGif(Style style, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ChatScreen || (Object) this instanceof net.ledok.eden_ld.client.hub.HubScreen)
                || style == null || style.getClickEvent() == null) {
            return;
        }
        ClickEvent click = style.getClickEvent();
        if (click.getAction() == ClickEvent.Action.RUN_COMMAND && click.getValue().startsWith("xaero_waypoint_add:")
                && XAERO_MINIMAP) {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.player != null) {
                // Xaero's ClientPacketListener#sendChat mixin takes it from here and cancels the send.
                minecraft.player.connection.sendChat(click.getValue());
            }
            cir.setReturnValue(true);
            return;
        }
        if (click.getAction() == ClickEvent.Action.OPEN_URL && ChatLinks.isViewable(click.getValue())) {
            GifViewer.open(click.getValue());
            cir.setReturnValue(true);
        } else if (click.getAction() == ClickEvent.Action.RUN_COMMAND && (Object) this instanceof ChatScreen
                && !Screen.hasShiftDown() && ChatTabActions.answerIn(click.getValue())) {
            // Shift-clicks stay vanilla's (they insert, never run).
            cir.setReturnValue(true);
        }
    }
}
