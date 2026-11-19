package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Pufferfish Skills: a skill tree's level (its experience level; the tree's total points when it
 * has no experience), and a player's class branch (the rental picker's recommendation). The only class touching
 * Pufferfish types is {@link Api}.
 */
public final class PuffishCompat {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("puffish_skills");

    private PuffishCompat() {
    }

    /** The player's level in the category, or -1 when Pufferfish Skills or the category is missing. */
    public static int level(ServerPlayer player, ResourceLocation category) {
        return LOADED ? Api.level(player, category) : -1;
    }

    /**
     * The branch of a skill tree with the most unlocked nodes: node ids start with it ("rogue.t3.s2.b" → "rogue"); a
     * tie goes to the first by name. Empty when Pufferfish Skills or the tree is missing or nothing is unlocked.
     */
    public static String topBranch(ServerPlayer player, ResourceLocation category) {
        return LOADED ? Api.topBranch(player, category) : "";
    }

    private static final class Api {
        static String topBranch(ServerPlayer player, ResourceLocation id) {
            var tree = net.puffish.skillsmod.api.SkillsAPI.getCategory(id);
            if (tree.isEmpty()) {
                return "";
            }
            java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
            tree.get().streamUnlockedSkills(player).forEach(skill -> {
                String skillId = skill.getId();
                int dot = skillId.indexOf('.');
                counts.merge(dot > 0 ? skillId.substring(0, dot) : skillId, 1, Integer::sum);
            });
            String best = "";
            int most = 0;
            for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > most) {
                    best = entry.getKey();
                    most = entry.getValue();
                }
            }
            return best;
        }

        static int level(ServerPlayer player, ResourceLocation id) {
            return net.puffish.skillsmod.api.SkillsAPI.getCategory(id)
                    .map(category -> category.getExperience()
                            .map(experience -> experience.getLevel(player))
                            .orElseGet(() -> category.getPointsTotal(player)))
                    .orElse(-1);
        }
    }
}
