package net.ledok.eden_ld.combat;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/** Dummmmmmy's target dummy ({@code dummmmmmy:target_dummy}): damage reduction from its armor, like a player's. */
public final class TargetDummies {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("dummmmmmy");
    private static final ResourceLocation DUMMY = ResourceLocation.fromNamespaceAndPath("dummmmmmy", "target_dummy");

    private TargetDummies() {
    }

    /** Whether Eden's damage reduction applies to this entity as a target dummy (server side, switch on). */
    public static boolean reduces(LivingEntity entity) {
        return LOADED && !entity.level().isClientSide() && EdenLD.CONFIG.combat.target_dummy_damage_reduction
                && DUMMY.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }
}
