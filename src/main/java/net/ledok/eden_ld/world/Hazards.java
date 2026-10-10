package net.ledok.eden_ld.world;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.config.EdenConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import org.jetbrains.annotations.Nullable;

/**
 * Hazards scale with health ({@code docs/PLAN-hazards.md}, config {@code hazards.json}): fire, lava,
 * poison, falls and the like deal at least their vanilla damage, else a share of the target's health
 * times the target's multiplier — players 1, other living things 0.5, bosses 0.1 by default. When
 * another player caused a player's burning, freezing, lightning, wither or poison, the share is halved.
 * Armor doesn't reduce these (nor wear down from them); Protection enchantments and effects still do,
 * after this.
 */
public final class Hazards {

    /** Bosses: the smallest share. The pack's tag (global pack {@code falleneden_bosses}) lists them all. */
    public static final TagKey<EntityType<?>> BOSSES = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath("falleneden", "bosses"));
    /** Without the pack's tag: the bosses other mods tag. */
    private static final TagKey<EntityType<?>> COMMON_BOSSES = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath("c", "bosses"));
    /** The player whose Channeling trident called the lightning hitting someone now (server thread). */
    private static final ThreadLocal<Player> LIGHTNING_CAUSE = new ThreadLocal<>();

    private Hazards() {
    }

    public static void initialize() {
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> EdenLD.CONFIG.reloadHazards());
    }

    /** What a hazard does to {@code target} instead of vanilla's {@code amount} (LivingEntity#hurt). */
    public static float scale(LivingEntity target, DamageSource source, float amount) {
        EdenConfig.Hazards config = EdenLD.CONFIG.hazards;
        if (!config.enabled || amount <= 0 || target.level().isClientSide()) {
            return amount;
        }
        ResourceKey<DamageType> type = source.typeHolder().unwrapKey().orElse(null);
        EdenConfig.Hazards.Rule rule = type == null ? null : config.damage_types.get(type.location().toString());
        if (rule == null) {
            return amount;
        }
        return apply(target, rule, amount, rule.pvp && byPlayer(target, type));
    }

    /** Whether armor leaves this damage alone: every damage type with a hazard rule, while hazards are on. */
    public static boolean ignoresArmor(DamageSource source) {
        EdenConfig.Hazards config = EdenLD.CONFIG.hazards;
        if (!config.enabled) {
            return false;
        }
        ResourceKey<DamageType> type = source.typeHolder().unwrapKey().orElse(null);
        return type != null && config.damage_types.containsKey(type.location().toString());
    }

    /** A poison tick's damage (vanilla: 1); it never takes the last point of health. */
    public static float poison(LivingEntity target, float amount) {
        EdenConfig.Hazards config = EdenLD.CONFIG.hazards;
        if (!config.enabled || config.poison == null || target.level().isClientSide()) {
            return amount;
        }
        return apply(target, config.poison, amount, config.poison.pvp && byPlayer(target, MobEffects.POISON));
    }

    private static float apply(LivingEntity target, EdenConfig.Hazards.Rule rule, float vanilla, boolean pvp) {
        EdenConfig.Hazards config = EdenLD.CONFIG.hazards;
        double max = target.getMaxHealth();
        double health = target.getHealth();
        double share = (rule.max_percent * max + rule.current_percent * health
                + rule.missing_percent * Math.max(0, max - health) + rule.per_point_percent * vanilla * max) / 100.0;
        share *= target instanceof Player ? config.player
                : target.getType().is(BOSSES) || target.getType().is(COMMON_BOSSES) ? config.boss : config.mob;
        if (pvp) {
            share *= config.pvp;
        }
        // Never less than vanilla: a fresh 20-health player takes exactly vanilla damage.
        double damage = Math.max(vanilla, share);
        if (!rule.lethal) {
            damage = Math.min(damage, Math.max(0, health - 1));
        }
        return (float) damage;
    }

    // ---------------------------------------------------------------- caused by another player

    /** Whether another player caused this player's hazard of {@code type} (always false for others). */
    private static boolean byPlayer(LivingEntity target, ResourceKey<DamageType> type) {
        if (!(target instanceof Player)) {
            return false;
        }
        if (type.equals(DamageTypes.LIGHTNING_BOLT)) {
            Player cause = LIGHTNING_CAUSE.get();
            return cause != null && cause != target;
        }
        if (type.equals(DamageTypes.WITHER)) {
            return byPlayer(target, MobEffects.WITHER);
        }
        if (type.equals(DamageTypes.ON_FIRE)) {
            return target instanceof HazardState state && state.eden_ld$pvpFire() || recentlyHitByPlayer(target);
        }
        return recentlyHitByPlayer(target);
    }

    /** Whether another player gave this player the effect (poison, wither) they have now. */
    private static boolean byPlayer(LivingEntity target, Holder<MobEffect> effect) {
        return target instanceof Player && target instanceof HazardState state && state.eden_ld$pvpEffect(effect);
    }

    /** Hit by another player within the configured seconds (projectiles and spells count their caster). */
    public static boolean recentlyHitByPlayer(LivingEntity target) {
        return target.getLastHurtByMob() instanceof Player attacker && attacker != target
                && target.tickCount - target.getLastHurtByMobTimestamp() <= EdenLD.CONFIG.hazards.pvp_seconds * 20;
    }

    /** The player behind an effect's source: themselves, or the owner of their arrow, potion or cloud. */
    @Nullable
    public static Player playerBehind(@Nullable Entity source) {
        if (source instanceof Player player) {
            return player;
        }
        if (source instanceof Projectile projectile && projectile.getOwner() instanceof Player owner) {
            return owner;
        }
        if (source instanceof net.minecraft.world.entity.AreaEffectCloud cloud && cloud.getOwner() instanceof Player owner) {
            return owner;
        }
        return null;
    }

    /** Around a lightning strike's hit (Entity#thunderHit): who called it. */
    public static void lightning(@Nullable Player cause) {
        if (cause == null) {
            LIGHTNING_CAUSE.remove();
        } else {
            LIGHTNING_CAUSE.set(cause);
        }
    }

    /** What a living entity remembers for the PvP rule (mixed into LivingEntity). */
    public interface HazardState {
        /** Set alight while another player was hitting them; cleared when the fire goes out. */
        boolean eden_ld$pvpFire();

        void eden_ld$setPvpFire(boolean pvp);

        /** The effect came from another player (poison, wither). */
        boolean eden_ld$pvpEffect(Holder<MobEffect> effect);
    }
}
