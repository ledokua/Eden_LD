package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.client.chat.EmojiFont;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Emojis (and Eden's text icons, {@code HubGlyphs}) keep their own colors in any text: Minecraft multiplies every glyph by the text color,
 * so an emoji in dark text (the hub's light themes, its chat field, signs) came out black.
 * Their glyphs are drawn white — shadows still dim as usual.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public abstract class FontRenderOutputMixin {

    @ModifyVariable(method = "accept", at = @At("HEAD"), argsOnly = true)
    private Style eden_ld$untintedEmoji(Style style, @Local(argsOnly = true, ordinal = 1) int codepoint) {
        return EmojiFont.isEmoji(codepoint) || net.ledok.eden_ld.client.HubGlyphs.isIcon(codepoint)
                ? style.withColor(0xFFFFFF) : style;
    }
}
