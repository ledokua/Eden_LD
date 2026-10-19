package net.ledok.eden_ld.combat;

/**
 * How two players' sides treat each other (docs/PLAN-pvp-toggle.md) — the one decision behind damage, projectiles,
 * pets, potions and the combat mods' targeting ({@code CombatTeamsCompat}). Plain code: the server and the client
 * feed it what they know, and the tests check the matrix.
 * <ul>
 *   <li>{@link #FRIENDS}: heals and buffs land, no damage.</li>
 *   <li>{@link #ENEMIES}: damage lands, heals and buffs don't.</li>
 *   <li>{@link #IGNORE}: nothing lands either way — no damage, heals, buffs, slows, pulls or knockback; area effects
 *       pass over them, projectiles fly through.</li>
 * </ul>
 */
public enum PvpRelation {
    FRIENDS,
    ENEMIES,
    IGNORE;

    /** No duel match ({@link #of}'s {@code matchA} / {@code matchB}). */
    public static final int NO_MATCH = 0;

    /**
     * The matrix.
     *
     * @param sameGroup   same party or guild, neither with friendly-fire protection suspended
     * @param sameRunTeam same scoreboard team with friendly fire off (Arenas run teams, a Duels side)
     * @param matchA      the running Duels match each is in, {@link #NO_MATCH} when none
     * @param onA         each one's effective state where the target stands (the world's mode, or their own flag in
     *                    a {@link PvpMode#TOGGLE} world)
     */
    public static PvpRelation of(boolean sameGroup, boolean sameRunTeam, int matchA, int matchB, boolean onA, boolean onB) {
        if (matchA != NO_MATCH || matchB != NO_MATCH) {
            // The same running match follows the duel's teams whatever the flags; a duelist and anyone outside the
            // match leave each other alone.
            if (matchA != matchB) {
                return IGNORE;
            }
            return sameRunTeam ? FRIENDS : ENEMIES;
        }
        if (sameRunTeam) {
            // Dungeons, raids, arenas: a run team stays together whatever the flags.
            return FRIENDS;
        }
        if (sameGroup) {
            // A PvP-off guildmate can't heal or buff a PvP-on fighter risk-free.
            return onA == onB ? FRIENDS : IGNORE;
        }
        return onA && onB ? ENEMIES : IGNORE;
    }

    /** Why an attacker's hit on someone is ignored — the action bar line it gets. */
    public enum Reason {
        /** The target is in a duel the attacker isn't part of. */
        TARGET_DUEL,
        /** The attacker is in a duel the target isn't part of. */
        OWN_DUEL,
        /** PvP is off in the dimension. */
        WORLD_OFF,
        /** The target has PvP off. */
        TARGET_OFF,
        /** The attacker has PvP off. */
        OWN_OFF
    }

    /**
     * The reason for an {@link #IGNORE} between an attacker ({@code A}) and a target ({@code B}), with the same inputs
     * as {@link #of} and the world's mode; the first that applies: a duel, the world, the target's state, the
     * attacker's own (only one of them can be on: two on would be enemies or friends).
     */
    public static Reason reason(int matchA, int matchB, PvpMode mode, boolean onA, boolean onB) {
        if (matchB != NO_MATCH && matchB != matchA) {
            return Reason.TARGET_DUEL;
        }
        if (matchA != NO_MATCH && matchA != matchB) {
            return Reason.OWN_DUEL;
        }
        if (mode == PvpMode.OFF) {
            return Reason.WORLD_OFF;
        }
        return onB ? Reason.OWN_OFF : Reason.TARGET_OFF;
    }
}
