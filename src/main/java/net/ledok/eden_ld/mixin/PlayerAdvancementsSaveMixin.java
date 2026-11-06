package net.ledok.eden_ld.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player's advancement file is written only when their progress changed ({@code saving.json}
 * {@code skip_unchanged_advancement_saves}): the 5-minute autosave rewrote every online player's file (up to 400 KB of
 * JSON each) every time. It starts out changed, so each player's file is written once per session; that also drops
 * entries of advancements that no longer exist. With {@code skip_recipe_advancements} the file's {@code recipes/}
 * entries are left out when it's read, as those advancements aren't loaded ({@link ServerAdvancementsRecipeMixin}).
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsSaveMixin {

    @Unique
    private boolean eden_ld$changed = true;

    @Inject(method = "award", at = @At("RETURN"))
    private void eden_ld$awarded(AdvancementHolder advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            eden_ld$changed = true;
        }
    }

    @Inject(method = "revoke", at = @At("RETURN"))
    private void eden_ld$revoked(AdvancementHolder advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            eden_ld$changed = true;
        }
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void eden_ld$saveOnlyChanged(CallbackInfo ci) {
        if (!eden_ld$changed && EdenLD.CONFIG.saving.skip_unchanged_advancement_saves) {
            ci.cancel();
            return;
        }
        eden_ld$changed = false;
    }

    /** The file as read: without its recipe advancements when those aren't loaded (no "doesn't exist" warning each). */
    @ModifyVariable(method = "load", at = @At("STORE"))
    private JsonElement eden_ld$withoutRecipes(JsonElement json) {
        if (EdenLD.CONFIG.saving.skip_recipe_advancements && json instanceof JsonObject object) {
            object.keySet().removeIf(ServerAdvancementsRecipeMixin::isRecipeAdvancement);
        }
        return json;
    }
}
