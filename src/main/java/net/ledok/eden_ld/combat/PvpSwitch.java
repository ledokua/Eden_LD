package net.ledok.eden_ld.combat;

/**
 * The rules of a player's own PvP switch ({@code /pvp on|off}, the inventory button): a minute's cooldown after any
 * switch, both ways (it survives relogging: the switch time is saved), and none while combat-tagged. Admins
 * ({@code /eden pvp player}) skip both. Plain code, for the tests.
 */
public final class PvpSwitch {

    /** After a switch, the next one waits this long (either way). */
    public static final long COOLDOWN_SECONDS = 60;

    /** What a switch request comes to. */
    public enum Result {
        /** Done. */
        SWITCHED,
        /** It already was that way: nothing changes, no cooldown starts. */
        ALREADY,
        /** Combat-tagged ({@code CombatTag}): no switching until it runs out. */
        IN_COMBAT,
        /** The last switch was less than {@link #COOLDOWN_SECONDS} ago. */
        COOLDOWN
    }

    private PvpSwitch() {
    }

    /**
     * Seconds left before the player may switch again, 0 when they may. {@code lastSwitch} is the epoch second of the
     * last switch (0: never); a clock that went back never makes the wait longer than the cooldown.
     */
    public static long cooldownLeft(long nowSeconds, long lastSwitch) {
        if (lastSwitch <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(COOLDOWN_SECONDS, lastSwitch + COOLDOWN_SECONDS - nowSeconds));
    }

    /** Whether a player whose PvP is {@code current} may switch it to {@code wanted} now. */
    public static Result check(boolean current, boolean wanted, long nowSeconds, long lastSwitch, long combatMillisLeft) {
        if (current == wanted) {
            return Result.ALREADY;
        }
        if (combatMillisLeft > 0) {
            return Result.IN_COMBAT;
        }
        if (cooldownLeft(nowSeconds, lastSwitch) > 0) {
            return Result.COOLDOWN;
        }
        return Result.SWITCHED;
    }
}
