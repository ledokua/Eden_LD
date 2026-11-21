package net.ledok.eden_ld.combat;

import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The gate for hits between sides that must not hurt each other (docs/PLAN-pvp-toggle.md): friends (party, guild, run
 * team) and ignored pairs (PvP off, a duel outsider), players and their pets alike ({@link CombatTeamsCompat#resolve}).
 * Damage asks it ({@code HubServer}'s ALLOW_DAMAGE), and so does Channeling lightning ({@code EntityHazardMixin}), whose
 * damage names no attacker. Pushes that don't go through a refused damage call — explosion knockback
 * ({@code ExplosionPvpMixin}) and the mace smash ({@code MaceItemPvpMixin}) — ask {@link #ignores}: only ignored pairs
 * are spared knockback, friends get "no damage" only (docs/PLAN-pvp-toggle.md). Review findings 57 and 58.
 * <p>
 * Beds and respawn anchors that blow up name no attacker on their damage, so the player using one is remembered while
 * the use runs ({@link #asBlockUser}) and stands in for the attacker of that blast. The damage itself stays
 * unattributed, so the user still takes their own blast.
 */
public final class PvpIgnore {

    /** The player whose bed or respawn anchor use is running now (server thread). */
    private static final ThreadLocal<Player> BLOCK_USER = new ThreadLocal<>();

    private PvpIgnore() {
    }

    /**
     * Whether {@code source} may hurt {@code entity}: always when nobody (or the entity itself) is behind it, else
     * only between sides with friendly fire.
     */
    public static boolean allowsDamage(LivingEntity entity, DamageSource source) {
        return allows(attacker(source), entity);
    }

    /**
     * Whether a hit, push or strike from {@code cause}'s side lands on {@code target}: false for friends and ignored
     * pairs, true for enemies, when either has no side, and for one's own pets.
     */
    public static boolean allows(@Nullable Entity cause, Entity target) {
        if (cause == null || cause == target) {
            return true;
        }
        CombatTeamsCompat.Relation relation = CombatTeamsCompat.resolve(cause, target);
        return relation == null || relation.friendlyFire();
    }

    /**
     * Whether nothing from {@code cause}'s side reaches {@code target}, pushes included: true only for an ignored pair
     * (PvP off, a duel outsider). Friends still push each other (a partymate's wind charge launches them); only their
     * damage is refused ({@link #allows}).
     */
    public static boolean ignores(@Nullable Entity cause, Entity target) {
        return cause != null && cause != target && CombatTeamsCompat.ignores(cause, target);
    }

    /**
     * Who is behind {@code source}: its attacker, or for a bed or respawn anchor blast with none, the player whose use
     * set it off (review finding 58).
     */
    @Nullable
    public static Entity attacker(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker == null && source.is(DamageTypes.BAD_RESPAWN_POINT)) {
            return BLOCK_USER.get();
        }
        return attacker;
    }

    /** Runs {@code use} (a bed or respawn anchor use) with {@code player} remembered as the one using it. */
    public static <T> T asBlockUser(Player player, Supplier<T> use) {
        Player outer = BLOCK_USER.get();
        BLOCK_USER.set(player);
        try {
            return use.get();
        } finally {
            if (outer == null) {
                BLOCK_USER.remove();
            } else {
                BLOCK_USER.set(outer);
            }
        }
    }
}
