package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.chat.EmojiFont;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Keeps Eden's runtime emoji font ({@link EmojiFont}) registered: a resource reload rebuilds
 * every font set from resource packs, so the emoji set is put back right after.
 */
@Mixin(FontManager.class)
public abstract class FontManagerMixin implements EmojiFont.FontSets {

    @Shadow
    private Map<ResourceLocation, FontSet> fontSets;

    @Override
    public Map<ResourceLocation, FontSet> eden_ld$fontSets() {
        return fontSets;
    }

    @Inject(method = "apply", at = @At("TAIL"))
    private void eden_ld$restoreEmoji(CallbackInfo ci) {
        EmojiFont.install();
    }
}
