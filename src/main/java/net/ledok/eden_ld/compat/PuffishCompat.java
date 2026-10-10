package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Pufferfish Skills: a skill tree's level (its experience level; the tree's total points when it
 * has no experience). The only class touching Pufferfish types is {@link Api}.
 */
public final class PuffishCompat {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("puffish_skills");

    private PuffishCompat() {
    }

    /** The player's level in the category, or -1 when Pufferfish Skills or the category is missing. */
    public static int level(ServerPlayer player, ResourceLocation category) {
        return LOADED ? Api.level(player, category) : -1;
    }

    private static final class Api {
        static int level(ServerPlayer player, ResourceLocation id) {
            return net.puffish.skillsmod.api.SkillsAPI.getCategory(id)
                    .map(category -> category.getExperience()
                            .map(experience -> experience.getLevel(player))
                            .orElseGet(() -> category.getPointsTotal(player)))
                    .orElse(-1);
        }
    }
}
