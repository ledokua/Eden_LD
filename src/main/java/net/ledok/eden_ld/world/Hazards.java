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
    /** The effect tick being dealt now ("poison", "bleed"), for damage reduction ({@link #tick()}). */
    private static final ThreadLocal<String> TICK = new ThreadLocal<>();

    private Hazards() {
    }

    public static void initialize() {
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            EdenLD.CONFIG.reloadHazards();
            EdenLD.CONFIG.reloadCombat();
        });
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

    /**
     * Whether armor leaves this damage alone: every damage type with a hazard rule, while hazards are on, unless the rule
     * says {@code "armor": true}.
     */
    public static boolean ignoresArmor(DamageSource source) {
        EdenConfig.Hazards.Rule rule = rule(source);
        return rule != null && !rule.armor;
    }

    /**
     * Whether armor must reduce this hazard although vanilla lets the type through it (falls are in
     * {@code #minecraft:bypasses_armor}): its rule says {@code "armor": true} (1.3.123).
     */
    public static boolean armorReduces(DamageSource source) {
        EdenConfig.Hazards.Rule rule = rule(source);
        return rule != null && rule.armor;
    }

    /** The hazard rule for this damage's type, while hazards are on; null for none. */
    @org.jetbrains.annotations.Nullable
    private static EdenConfig.Hazards.Rule rule(DamageSource source) {
        EdenConfig.Hazards config = EdenLD.CONFIG.hazards;
        if (!config.enabled || config.damage_types == null) {
            return null;
        }
        ResourceKey<DamageType> type = source.typeHolder().unwrapKey().orElse(null);
        return type == null ? null : config.damage_types.get(type.location().toString());
    }

    /** A poison tick's damage (vanilla: 1); it never takes the last point of health. */
    public static float poison(LivingEntity target, float amount) {
        EdenConfig.Hazards config = EdenLD.CONFIG.hazards;
        if (!config.enabled || config.poison == null || target.level().isClientSide()) {
            return amount;
        }
        return apply(target, config.poison, amount, config.poison.pvp && byPlayer(target, MobEffects.POISON));
    }

    /**
     * A bleeding effect's tick or burst ({@code hazards.json} {@code bleed}, by key) instead of its flat {@code amount}:
     * the rule's health share, times {@code stacks} for per-stack rules, times {@code factor} (Spell Engine's movement
     * multiplier, else 1). The effect's own damage when the key has no rule.
     */
    public static float bleed(String key, LivingEntity target, int stacks, double factor, float amount) {
        EdenConfig.Hazards config = EdenLD.CONFIG.hazards;
        EdenConfig.Hazards.Rule rule = config.bleed == null ? null : config.bleed.get(key);
        if (!config.enabled || rule == null || target.level().isClientSide()) {
            return amount;
        }
        double damage = share(target, rule, 0, rule.pvp && recentlyHitByPlayer(target))
                * (rule.per_stack ? Math.max(1, stacks) : 1) * factor;
        return (float) (rule.lethal ? damage : Math.min(damage, Math.max(0, target.getHealth() - 1)));
    }

    /** Runs {@code hit} marked as an effect's tick ("poison", "bleed"): damage reduction looks it up. */
    public static <T> T asTick(String kind, java.util.function.Supplier<T> hit) {
        String outer = TICK.get();
        TICK.set(kind);
        try {
            return hit.get();
        } finally {
            if (outer == null) {
                TICK.remove();
            } else {
                TICK.set(outer);
            }
        }
    }

    /** The effect tick being dealt now ("poison", "bleed"), or null for any other damage. */
    @Nullable
    public static String tick() {
        return TICK.get();
    }

    private static float apply(LivingEntity target, EdenConfig.Hazards.Rule rule, float vanilla, boolean pvp) {
        // Never less than vanilla: a fresh 20-health player takes exactly vanilla damage.
        double damage = Math.max(vanilla, share(target, rule, vanilla, pvp));
        if (!rule.lethal) {
            damage = Math.min(damage, Math.max(0, target.getHealth() - 1));
        }
        return (float) damage;
    }

    /** The rule's share of the target's health, times the target's multiplier (and the PvP one). */
    private static double share(LivingEntity target, EdenConfig.Hazards.Rule rule, float vanilla, boolean pvp) {
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
        return share;
    }

    // ---------------------------------------------------------------- caused by another player

    /**
     * Whether damage that landed on a player came from another player: hit, shot or cast by one (the source's
     * attacker), by an online player's pet or summon (owned entities count as their owner, as for parties), or a
     * hazard another player caused as the hazard rules see it (rules with {@code "pvp": true}: their poison or wither,
     * fire they set, lightning they called, bleeding while they were hitting). False for anyone but players, and for a
     * player's own damage.
     */
    public static boolean fromOtherPlayer(LivingEntity target, DamageSource source) {
        if (!(target instanceof Player)) {
            return false;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof Player player) {
            return player != target;
        }
        if (attacker != null && target.getServer() != null) {
            java.util.UUID owner = net.ledok.eden_ld.compat.CombatTeamsCompat.sideOf(attacker);
            if (owner != null && !owner.equals(target.getUUID())
                    && target.getServer().getPlayerList().getPlayer(owner) != null) {
                return true;
            }
        }
        String tick = TICK.get();
        if ("poison".equals(tick)) {
            return byPlayer(target, MobEffects.POISON);
        }
        if ("bleed".equals(tick)) {
            return recentlyHitByPlayer(target);
        }
        EdenConfig.Hazards.Rule rule = rule(source);
        ResourceKey<DamageType> type = source.typeHolder().unwrapKey().orElse(null);
        return rule != null && rule.pvp && type != null && byPlayer(target, type);
    }

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
