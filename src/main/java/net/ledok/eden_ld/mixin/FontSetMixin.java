package net.ledok.eden_ld.mixin;

import com.mojang.blaze3d.font.GlyphInfo;
import net.ledok.eden_ld.client.chat.EmojiFont;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An emoji's character draws as the emoji in every font, not only Eden's emoji font — so a
 * {@code :name:} the chat input turned into its character shows as the image there (and in
 * chat history, {@code /msg}, hub panels), with widths that match what is drawn. Only where the
 * font has no glyph of its own: a font or resource pack that draws something at that character
 * keeps it. Drawing one also tells {@link EmojiFont} it's on screen (animations pause otherwise).
 */
@Mixin(FontSet.class)
public abstract class FontSetMixin {

    @Shadow
    private BakedGlyph missingGlyph;

    @Inject(method = "getGlyphInfo", at = @At("RETURN"), cancellable = true)
    private void eden_ld$emojiInfo(int codepoint, boolean filterFishyGlyphs, CallbackInfoReturnable<GlyphInfo> cir) {
        if (cir.getReturnValue() != SpecialGlyphs.MISSING) {
            return;
        }
        FontSet emoji = EmojiFont.borrowingFrom((FontSet) (Object) this, codepoint);
        if (emoji != null) {
            cir.setReturnValue(emoji.getGlyphInfo(codepoint, filterFishyGlyphs));
        }
    }

    @Inject(method = "getGlyph", at = @At("HEAD"))
    private void eden_ld$markDrawn(int codepoint, CallbackInfoReturnable<BakedGlyph> cir) {
        // Text rendering asks for each glyph as it draws it: animated emojis only play while seen.
        EmojiFont.markDrawn(codepoint);
    }

    @Inject(method = "getGlyph", at = @At("RETURN"), cancellable = true)
    private void eden_ld$emojiGlyph(int codepoint, CallbackInfoReturnable<BakedGlyph> cir) {
        if (cir.getReturnValue() != missingGlyph) {
            return;
        }
        FontSet emoji = EmojiFont.borrowingFrom((FontSet) (Object) this, codepoint);
        if (emoji != null) {
            cir.setReturnValue(emoji.getGlyph(codepoint));
        }
    }
}
