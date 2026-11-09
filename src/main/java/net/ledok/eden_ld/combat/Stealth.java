package net.ledok.eden_ld.combat;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One rule for "this player is in stealth" across the class mods. mrpg_lib's stealth (Archers Expansion's Infiltrator's
 * Vanish) hides the whole player from others, armor and held items included; Rogues' Stealth only made the body
 * see-through and left the armor showing. mrpg_lib asks {@link #isHidden} instead of its own check, so the effects in
 * {@code combat.json} {@code stealth_effects} (Rogues' Stealth) are drawn the same way. While hidden, the player also
 * sends no effect particles ({@code stealth_hides_effect_particles}), which gave the position away.
 */
public final class Stealth {

    private static final Class<?> MRPG_STEALTH = mrpgStealthClass();
    private static List<String> idSource;
    private static Set<ResourceLocation> ids = Set.of();
    private static boolean installed;

    private Stealth() {
    }

    /**
     * Both sides, once every mod has started (server starting, client started): mrpg_lib's own Spell Engine compat sets
     * its check during mod init, so a hook set earlier would be overwritten. The check it set is kept and ours added.
     */
    public static void initialize() {
        if (MRPG_STEALTH == null) {
            return;
        }
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> install());
    }

    /** Wraps mrpg_lib's current stealth check (also called from the client's start). */
    @SuppressWarnings("unchecked")
    public static synchronized void install() {
        if (MRPG_STEALTH == null || installed) {
            return;
        }
        try {
            java.lang.reflect.Field field = MRPG_STEALTH.getDeclaredField("activeCheck");
            field.setAccessible(true);
            Predicate<LivingEntity> previous = (Predicate<LivingEntity>) field.get(null);
            Predicate<LivingEntity> check = previous == null ? Stealth::isHidden
                    : entity -> previous.test(entity) || isHidden(entity);
            MRPG_STEALTH.getMethod("setActiveCheck", Predicate.class).invoke(null, check);
            installed = true;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            EdenLD.LOGGER.warn("Couldn't hook mrpg_lib's stealth check; Rogues' Stealth keeps its own look", e);
        }
    }

    /** Whether {@code entity} carries an mrpg_lib stealth effect or one of {@code stealth_effects}. */
    public static boolean isHidden(LivingEntity entity) {
        for (MobEffectInstance instance : entity.getActiveEffects()) {
            if (hides(instance.getEffect().value())) {
                return true;
            }
        }
        // A client knows other players' effects only from Spell Engine's synced list (Rogues and Archers Expansion
        // sync their stealth effects there).
        return SPELL_ENGINE && SyncedEffects.any(entity);
    }

    /** Whether {@code viewer} must not see {@code entity} at all: in stealth and invisible to them (not a teammate). */
    public static boolean hiddenFrom(LivingEntity entity, net.minecraft.world.entity.player.Player viewer) {
        return viewer != null && entity != viewer && entity.isInvisibleTo(viewer) && isHidden(entity);
    }

    private static boolean hides(net.minecraft.world.effect.MobEffect effect) {
        if (MRPG_STEALTH != null && MRPG_STEALTH.isInstance(effect)) {
            return true;
        }
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getKey(effect);
        return id != null && ids().contains(id);
    }

    private static final boolean SPELL_ENGINE = FabricLoader.getInstance().isModLoaded("spell_engine");

    /** Apart, so Spell Engine's classes only load when it's installed. */
    private static final class SyncedEffects {
        static boolean any(LivingEntity entity) {
            if (!(entity instanceof net.spell_engine.api.effect.Synchronized.Provider provider)) {
                return false;
            }
            for (net.spell_engine.api.effect.Synchronized.Effect effect : provider.SpellEngine_syncedStatusEffects()) {
                if (hides(effect.effect())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Set<ResourceLocation> ids() {
        List<String> source = EdenLD.CONFIG.combat.stealth_effects;
        if (source != idSource) {
            Set<ResourceLocation> parsed = new HashSet<>();
            if (source != null) {
                for (String id : source) {
                    ResourceLocation key = ResourceLocation.tryParse(id);
                    if (key != null) {
                        parsed.add(key);
                    }
                }
            }
            ids = parsed;
            idSource = source;
        }
        return ids;
    }

    private static Class<?> mrpgStealthClass() {
        if (!FabricLoader.getInstance().isModLoaded("mrpg_lib")) {
            return null;
        }
        try {
            return Class.forName("com.mrpg_lib.effect.StealthStatusEffect", false, Stealth.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
