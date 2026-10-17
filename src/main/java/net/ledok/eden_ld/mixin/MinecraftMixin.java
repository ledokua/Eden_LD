package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.chat.EmojiFont;
import net.ledok.eden_ld.client.compat.EmiReloadFix;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reaches the game's FontManager for Eden's runtime emoji font ({@link EmojiFont}), and tells
 * {@link EmiReloadFix} when the client leaves its world: every disconnect (a dropped connection, a
 * server stop, the pause menu, joining another server) ends in {@code disconnect(Screen, boolean)},
 * and the server sending the client back to configuration in {@code clearClientLevel}. Tells the chat's new look
 * when the screen changes.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin implements EmojiFont.MinecraftFonts {

    @Shadow
    @Final
    private FontManager fontManager;

    @Shadow
    public net.minecraft.client.gui.screens.Screen screen;

    @Override
    public FontManager eden_ld$fontManager() {
        return fontManager;
    }

    /** The chat's new look decides here whether a closing chat fades out (see ChatLook). */
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void eden_ld$screenChanging(net.minecraft.client.gui.screens.Screen next, CallbackInfo ci) {
        net.ledok.eden_ld.client.chat.ui.ChatLook.screenChanging(screen, next);
    }

    @Inject(method = {"disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", "clearClientLevel"}, at = @At("HEAD"))
    private void eden_ld$leftWorld(CallbackInfo ci) {
        EmiReloadFix.leftWorld();
    }
}
