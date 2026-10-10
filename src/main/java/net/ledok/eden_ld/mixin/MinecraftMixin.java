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
 * and the server sending the client back to configuration in {@code clearClientLevel}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin implements EmojiFont.MinecraftFonts {

    @Shadow
    @Final
    private FontManager fontManager;

    @Override
    public FontManager eden_ld$fontManager() {
        return fontManager;
    }

    @Inject(method = {"disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", "clearClientLevel"}, at = @At("HEAD"))
    private void eden_ld$leftWorld(CallbackInfo ci) {
        EmiReloadFix.leftWorld();
    }
}
