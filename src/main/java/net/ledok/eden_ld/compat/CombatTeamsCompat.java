package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.combat.PvpRelation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Who may hurt (or help) whom between players, their pets and their summons — party and guild friendly fire, run teams,
 * duels and each player's own PvP switch (docs/PLAN-pvp-toggle.md) — in one place, {@link #resolve}, which everything
 * asks: damage ({@code HubServer}), vanilla's player check and pets' targeting ({@code PlayerMixin}), projectiles
 * ({@code ProjectileMixin}), splash and lingering potions ({@code ThrownPotionPvpMixin},
 * {@code AreaEffectCloudPvpMixin}), Spell Engine's action gate ({@code SpellRelationMixin}) and the
 * {@code registerTeamMatcher} hooks of Spell Engine (spell targeting) and Better Combat (sweeps). Both mods consult
 * their matchers on the client (target selection, highlights) and the server (hits): server side the answer comes from
 * {@link net.ledok.eden_ld.combat.PlayerPvp}; client side from the synced data ({@code ClientPvp}: everyone's flag,
 * duels, the mode here, the player's own party and guild).
 * <p>
 * Friends count as teammates without friendly fire (Spell Engine {@code ALLY}, Better Combat {@code FRIENDLY}), enemies
 * as hostile; an ignored pair is reported as friends too (summons, Holy Barrier and sweeps leave them alone), and Spell
 * Engine's action gate refuses them outright — its relations have no "ignore", every one allows damage or healing.
 *
 * <p>The inner hook classes are the only ones importing Spell Engine / Better
 * Combat types, so this class is safe to load when either mod is absent.
 */
public final class CombatTeamsCompat {

    /**
     * Two sides' relation: teammates, whether hitting each other is allowed, and whether nothing at all lands between
     * them ({@code ignore}: no damage, heals, buffs or knockback).
     */
    public record Relation(boolean teammates, boolean friendlyFire, boolean ignore) {
        /** Heals and buffs land, damage doesn't. */
        public static final Relation FRIENDS = new Relation(true, false, false);
        /** Damage lands, heals and buffs don't. */
        public static final Relation ENEMIES = new Relation(false, true, false);
        /** Nothing lands either way (reported to the combat mods as friends). */
        public static final Relation IGNORE = new Relation(true, false, true);

        public static Relation of(PvpRelation relation) {
            return switch (relation) {
                case FRIENDS -> FRIENDS;
                case ENEMIES -> ENEMIES;
                case IGNORE -> IGNORE;
            };
        }

        /** Friends: helpful effects land, harmful ones don't. */
        public boolean friends() {
            return teammates && !ignore;
        }
    }

    private CombatTeamsCompat() {
    }

    public static void initialize() {
        if (FabricLoader.getInstance().isModLoaded("spell_engine")) {
            SpellEngineHook.register();
            EdenLD.LOGGER.info("[Party] Spell Engine team matcher registered.");
        }
        if (FabricLoader.getInstance().isModLoaded("bettercombat")) {
            BetterCombatHook.register();
            EdenLD.LOGGER.info("[Party] Better Combat team matcher registered.");
        }
    }

    /**
     * The relation between two entities, each standing in for its "side": a player is their own side, a tamed/owned
     * entity (pets, horses, summons) is its owner's. Null when either has no side, or both belong to the same player
     * (vanilla rules apply to your own pets); otherwise friends, enemies or ignore ({@link PvpRelation}) — judged where
     * {@code target} stands.
     */
    @Nullable
    public static Relation resolve(Entity attacker, Entity target) {
        UUID a = sideOf(attacker);
        UUID t = sideOf(target);
        if (a == null || t == null || a.equals(t)) {
            return null;
        }
        // A run or duel team (Arenas dungeons and raids, Duels' sides): pets and summons have no team of their own.
        boolean runTeam = sameTeamNoFriendlyFire(attacker, a, t);
        if (attacker.level().isClientSide()) {
            return net.ledok.eden_ld.client.hub.ClientPvp.relation(a, t, runTeam);
        }
        MinecraftServer server = attacker.getServer();
        if (server == null) {
            return null;
        }
        return Relation.of(net.ledok.eden_ld.combat.PlayerPvp.relation(server, target, a, t, runTeam));
    }

    /** Whether nothing lands between the two (an ignored pair): Spell Engine's action gate, potions. */
    public static boolean ignores(Entity attacker, Entity target) {
        Relation relation = resolve(attacker, target);
        return relation != null && relation.ignore();
    }

    /**
     * Whether a potion effect thrown or left by {@code owner} (a splash potion, a lingering cloud) lands on
     * {@code target}: never on someone they ignore, only beneficial ones on friends, only harmful and neutral ones on
     * enemies; as vanilla has it for everyone else (no thrower, mobs, the thrower themselves).
     */
    public static boolean effectLands(@Nullable Entity owner, Entity target, MobEffect effect) {
        Relation relation = owner == null ? null : resolve(owner, target);
        return relation == null || !relation.ignore() && relation.friends() == effect.isBeneficial();
    }

    /** Whether any of the effects lands on {@code target} ({@link #effectLands}). */
    public static boolean anyEffectLands(@Nullable Entity owner, Entity target, Iterable<MobEffectInstance> effects) {
        Relation relation = owner == null ? null : resolve(owner, target);
        if (relation == null) {
            return true;
        }
        if (relation.ignore()) {
            return false;
        }
        for (MobEffectInstance effect : effects) {
            if (relation.friends() == effect.getEffect().value().isBeneficial()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the two sides' players are on the same scoreboard team with friendly fire off (both must be loaded). */
    private static boolean sameTeamNoFriendlyFire(Entity context, UUID a, UUID t) {
        Player pa = context.level().getPlayerByUUID(a);
        Player pt = context.level().getPlayerByUUID(t);
        if (pa == null || pt == null) {
            return false;
        }
        net.minecraft.world.scores.PlayerTeam team = pa.getTeam();
        return team != null && team == pt.getTeam() && !team.isAllowFriendlyFire();
    }

    /**
     * Player uuid, or the owner's uuid for owned entities: vanilla's {@link OwnableEntity} (tamed animals, horses,
     * Spell Engine summons, Saint's Dragons) and the summons of mods that keep their own owner ({@link #MOD_OWNERS}).
     * Both sides — owners are synced (the Alter Ego's only server side).
     */
    @Nullable
    public static UUID sideOf(Entity entity) {
        if (entity instanceof Player player) {
            return player.getUUID();
        }
        if (entity instanceof OwnableEntity owned) {
            return owned.getOwnerUUID();
        }
        return MOD_OWNERS.get(entity.getClass()).apply(entity);
    }

    /**
     * Owned entities that aren't {@link OwnableEntity}, found by class name (no compile dependency) and read through
     * their own public owner getter: Draugr's Flame Atronach ({@code getOwnerUuid}), Archers Expansion's Alter Ego
     * decoy ({@code getOwner}, a living entity — a player counts). No owner for every other class; one lookup per
     * class.
     */
    private static final ClassValue<java.util.function.Function<Entity, UUID>> MOD_OWNERS = new ClassValue<>() {
        @Override
        protected java.util.function.Function<Entity, UUID> computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                switch (c.getName()) {
                    case "net.mebahelcreaturesdraugr.entity.custom.FlameAtronachEntity" -> {
                        return ownerGetter(c, "getOwnerUuid");
                    }
                    case "com.archers_expansion.entity.AlterEgoEntity" -> {
                        return ownerGetter(c, "getOwner");
                    }
                    default -> {
                    }
                }
            }
            return NO_OWNER;
        }
    };

    private static final java.util.function.Function<Entity, UUID> NO_OWNER = entity -> null;

    /** A public no-arg getter returning the owner's uuid, or the owner (a player's uuid counts); no owner if it's missing. */
    private static java.util.function.Function<Entity, UUID> ownerGetter(Class<?> type, String name) {
        java.lang.invoke.MethodHandle getter;
        try {
            getter = java.lang.invoke.MethodHandles.publicLookup().unreflect(type.getMethod(name));
        } catch (ReflectiveOperationException | RuntimeException e) {
            EdenLD.LOGGER.warn("[Party] {}#{} not found; its owner won't count for PvP.", type.getName(), name);
            return NO_OWNER;
        }
        return entity -> {
            try {
                Object owner = getter.invoke(entity);
                return owner instanceof UUID uuid ? uuid : owner instanceof Player player ? player.getUUID() : null;
            } catch (Throwable e) {
                return null;
            }
        };
    }

    private static final class SpellEngineHook {
        static void register() {
            net.spell_engine.internals.target.EntityRelations.registerTeamMatcher("eden_ld_party",
                    (attacker, target) -> {
                        Relation relation = resolve(attacker, target);
                        return relation == null ? null
                                : new net.spell_engine.internals.target.EntityRelations.TeamRelation(
                                        relation.teammates(), relation.friendlyFire());
                    });
        }
    }

    private static final class BetterCombatHook {
        static void register() {
            net.bettercombat.logic.TargetHelper.registerTeamMatcher("eden_ld_party",
                    (attacker, target) -> {
                        Relation relation = resolve(attacker, target);
                        return relation == null ? null
                                : new net.bettercombat.logic.TargetHelper.TeamRelation(
                                        relation.teammates(), relation.friendlyFire());
                    });
        }
    }
}
