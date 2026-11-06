package net.ledok.eden_ld.mixin;

import com.google.gson.JsonElement;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.HashMap;
import java.util.Map;

/**
 * Recipe-unlock advancements ({@code <namespace>:recipes/…}) aren't loaded ({@code saving.json}
 * {@code skip_recipe_advancements}, default on): on FallenEden they were 90% of every player's advancement file (2,214
 * of 2,458 entries in the biggest) and were checked on every inventory change. They only unlock recipes in the vanilla
 * recipe book; crafting doesn't need them (doLimitedCrafting is off) and EMI shows every recipe. Nothing in the pack
 * (FTB Quests, KubeJS, datapacks, mods' data) refers to them.
 */
@Mixin(ServerAdvancementManager.class)
public abstract class ServerAdvancementsRecipeMixin {

    @Unique
    private static boolean eden_ld$logged;

    @ModifyVariable(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("HEAD"), argsOnly = true)
    private Map<ResourceLocation, JsonElement> eden_ld$withoutRecipes(Map<ResourceLocation, JsonElement> advancements) {
        if (!EdenLD.CONFIG.saving.skip_recipe_advancements) {
            return advancements;
        }
        Map<ResourceLocation, JsonElement> kept = new HashMap<>(advancements);
        kept.keySet().removeIf(id -> id.getPath().startsWith("recipes/"));
        if (!eden_ld$logged) {
            eden_ld$logged = true;
            EdenLD.LOGGER.info("[Saving] Recipe advancements not loaded: {} of {} advancements left out.",
                    advancements.size() - kept.size(), advancements.size());
        }
        return kept;
    }

    /** A player file key: {@code namespace:recipes/…}. */
    @Unique
    static boolean isRecipeAdvancement(String id) {
        int colon = id.indexOf(':');
        return id.startsWith("recipes/", colon + 1);
    }
}
